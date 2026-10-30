package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.room.withTransaction
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.parser.RssFeedParser

/** Owns RSS acquisition and writes; inference remains owned by the foreground import repository. */
internal class RssRepository internal constructor(private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context),
    private val requestOverride: (suspend (String, Map<String, String>, Boolean) -> RssHttpPage)? = null,
    private val networkOverride: me.bubblespark.tidilo.parser.ArticleNetwork? = null,
    private val scheduleChanged: suspend () -> Unit = { RssScheduler.reschedule(context) },
    private val enqueueInitial: suspend (String, String, Boolean) -> Unit = { feedId, operationId, mobile ->
        RssScheduler.fetchInitial(context, feedId, operationId, mobile)
    },
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val dao = db.rssDao()
    private val preferences = RssPreferences.getInstance(context)
    private fun fetcher(registration: Boolean = false, override: Boolean = false) = RssFeedFetcher { url, headers ->
        val wifiOnly = !registration && !override && preferences.state.value.wifiOnly
        requestOverride?.invoke(url, headers, wifiOnly)
            ?: RssHttpClient(context) { !registration && !override && preferences.state.value.wifiOnly }.request(url, headers)
    }

    fun articleNetwork(overrideOperation: String? = null, deadline: Long = Long.MAX_VALUE,
        feed: RssFeedEntity? = null): me.bubblespark.tidilo.parser.ArticleNetwork {
        val delegate = networkOverride ?: object : me.bubblespark.tidilo.parser.ArticleNetwork {
            override val scopeKey = overrideOperation?.let { "rss:initial:$it" } ?: "rss:wifi:${preferences.state.value.wifiOnly}"
            override suspend fun fetch(url: String): me.bubblespark.tidilo.parser.ArticleResponse {
                val response = RssHttpClient(context) { overrideOperation == null && preferences.state.value.wifiOnly }
                    .request(url, emptyMap(), 10 * 1024 * 1024)
                return me.bubblespark.tidilo.parser.ArticleResponse(response.url, response.status, response.bytes, response.contentType)
            }
        }
        return object : me.bubblespark.tidilo.parser.ArticleNetwork {
            override val scopeKey = delegate.scopeKey
            override suspend fun fetch(url: String): me.bubblespark.tidilo.parser.ArticleResponse {
                // Includes every optional icon/image/root-page request, not only the article window.
                if (elapsedMillis() + 35_000L >= deadline) throw RssAcquisitionYield()
                if (feed != null) {
                    val current = dao.feed(feed.id)
                    check(current != null && current.enabled && !current.deleted && current.revision == feed.revision &&
                        (overrideOperation == null || (current.acquisitionId == overrideOperation && current.acquisitionMobileOverride))) {
                        "Subscription changed; acquisition permission ended"
                    }
                }
                return delegate.fetch(url)
            }
        }
    }
    private val operation = Mutex()
    private val store = RssIngestionStore(db)
    val feeds = dao.observeFeeds()
    val settings = preferences.state

    suspend fun register(url: String, mode: String, limit: Int?, confirmed: RssRegistrationSnapshot? = null): String = withContext(Dispatchers.IO) {
        preferences.initialize()
        val normalized = RssFeedParser.httpUrl(url, url) ?: throw IllegalArgumentException("Enter an HTTP or HTTPS feed URL")
        require(mode in setOf("AI", "DIRECT"))
        require(limit == null || limit in 10..500)
        operation.withLock {
            val old = dao.feedByUrl(normalized)
            require(old == null || old.deleted) { "This feed is already registered" }
            val snapshot = store.registrationSnapshot(normalized)
            if (snapshot != null && snapshot != confirmed) throw RssRegistrationConfirmationRequired(snapshot)
            val fetched = fetcher(registration = true).fetch(normalized, null, null)
            val document = requireNotNull(fetched.document) { "Feed returned no document" }
            val feed = RssFeedEntity(old?.id ?: UUID.randomUUID().toString(), normalized, document.title, mode, limit,
                revision = (old?.revision ?: -1) + 1, iconUrl = document.iconUrl, siteUrl = document.siteUrl)
            // XML validation discovers feed metadata only; ingestion has its own operation and validators.
            store.registerFeed(feed, confirmed)
            scheduleChanged()
            feed.id
        }
    }

    suspend fun edit(id: String, mode: String, limit: Int?, convertPending: Boolean) = withContext(Dispatchers.IO) {
        require(mode in setOf("AI", "DIRECT"))
        require(limit == null || limit in 10..500)
        run {
            db.withTransaction {
                val previous = requireNotNull(dao.feed(id))
                require(!previous.deleted)
                val updated = previous.copy(mode = mode, retentionLimit = limit, revision = previous.revision + 1, acquisitionId = null, acquisitionMobileOverride = false, acquisitionXmlDone = false)
                dao.putFeed(updated)
                if (previous.mode == "AI" && mode == "DIRECT") {
                    val pending = dao.pending(id) + dao.unstartedAcquisitions(id)
                    if (convertPending) for (task in pending) {
                        val converted = task.copy(intendedMode = "DIRECT", articlePrepared = false,
                            status = "ERROR", statusText = "Waiting for Direct article acquisition",
                            acquisitionAttempts = 0, acquisitionRetryAt = 0)
                        db.importTaskDao().insertOrUpdate(converted)
                        if (task.articlePrepared) store.complete(updated, converted,
                            me.bubblespark.tidilo.parser.PreparedArticle(task.url, task.title, task.siteName,
                                task.description, task.bodySnippet, task.ogImageUrl, task.isNsfw))
                    } else db.importTaskDao().deleteByIds(pending.map { it.id })
                }
            }
            cleanup()
            scheduleChanged()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        run {
            db.withTransaction {
                val feed = requireNotNull(dao.feed(id))
                dao.putFeed(feed.copy(deleted = true, revision = feed.revision + 1, acquisitionId = null, acquisitionMobileOverride = false, acquisitionXmlDone = false))
                db.importTaskDao().deleteByIds((dao.pending(id) + dao.unstartedAcquisitions(id)).map { it.id })
            }
            scheduleChanged()
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val feed = requireNotNull(dao.feed(id))
            require(!feed.deleted)
            dao.putFeed(feed.copy(enabled = enabled, revision = feed.revision + 1, acquisitionId = null, acquisitionMobileOverride = false, acquisitionXmlDone = false))
        }
        scheduleChanged()
    }

    suspend fun requestInitialFetch(feedId: String, mobileAllowed: Boolean) = withContext(Dispatchers.IO) {
        preferences.initialize()
        val operationId = operation.withLock {
            db.withTransaction {
                val feed = requireNotNull(dao.feed(feedId))
                require(feed.enabled && !feed.deleted)
                val id = feed.acquisitionId ?: UUID.randomUUID().toString()
                dao.putFeed(feed.copy(acquisitionId = id, acquisitionMobileOverride = mobileAllowed,
                    acquisitionRequestedAt = System.currentTimeMillis()))
                id
            }
        }
        enqueueInitial(feedId, operationId, mobileAllowed)
    }

    /** A stopped initial operation keeps only its own permission; ordinary requests cannot borrow it. */
    suspend fun resumeAcquisitions() = withContext(Dispatchers.IO) {
        preferences.initialize()
        for (feed in dao.enabledFeeds()) {
            if (feed.acquisitionId != null) enqueueInitial(feed.id, feed.acquisitionId, feed.acquisitionMobileOverride)
        }
    }

    /** XML success and article completion are separate durable checkpoints. */
    suspend fun fetchAll(since: Long, onlyFeedId: String? = null, authorizedOperation: String? = null,
        notificationRunId: String? = null): Boolean = withContext(Dispatchers.IO) {
        me.bubblespark.tidilo.TidiloApplication.instance.awaitAiAnalysisInitialization()
        preferences.initialize()
        operation.withLock {
            val deadline = elapsedMillis() + 6 * 60_000L
            var incomplete = false
            for (snapshot in dao.enabledFeeds().filter { onlyFeedId == null || it.id == onlyFeedId }) {
                if (onlyFeedId != null && snapshot.acquisitionId != authorizedOperation) continue
                val retryTasks = db.importTaskDao().getAllTasks().any {
                    it.rssFeedId == snapshot.id && !it.articlePrepared && it.status != "PROCESSING_AI" &&
                        (it.intendedMode == "DIRECT" || snapshot.mode == "AI") && it.acquisitionAttempts < 5 && it.acquisitionRetryAt != Long.MAX_VALUE
                }
                if (snapshot.acquisitionId == null && !retryTasks && (snapshot.lastSuccessAt ?: Long.MIN_VALUE) >= since) continue
                var feed = db.withTransaction {
                    val current = dao.feed(snapshot.id)?.takeIf { !it.deleted && it.enabled && it.revision == snapshot.revision }
                        ?: return@withTransaction null
                    if (current.acquisitionId != null) current else current.copy(acquisitionId = UUID.randomUUID().toString(),
                        acquisitionRequestedAt = since, acquisitionXmlDone = false).also { dao.putFeed(it) }
                } ?: continue
                val mobileOperation = feed.acquisitionId?.takeIf { it == authorizedOperation && feed.acquisitionMobileOverride }
                val network = articleNetwork(mobileOperation, deadline, feed)
                if (!feed.acquisitionXmlDone) {
                    if (elapsedMillis() + 35_000L >= deadline) throw RssAcquisitionYield()
                    val fetched = try { fetcher(override = mobileOperation != null).fetch(feed.url, feed.etag, feed.lastModified) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (waiting: RssWifiUnavailable) { throw waiting }
                    catch (_: Exception) {
                        store.disableAfterXmlFailure(feed, notificationRunId)
                        // The worker realigns scheduling after it publishes the aggregated result.
                        continue
                    }
                    val now = System.currentTimeMillis()
                    val committed = db.withTransaction {
                        if (!store.stage(feed, fetched.document?.entries.orEmpty(), now, notificationRunId)) return@withTransaction false
                        val current = requireNotNull(dao.feed(feed.id))
                        dao.putFeed(current.copy(title = fetched.document?.title ?: current.title,
                            iconUrl = if (fetched.document == null) current.iconUrl else fetched.document.iconUrl,
                            siteUrl = if (fetched.document == null) current.siteUrl else fetched.document.siteUrl,
                            etag = if (fetched.page.status == 304) current.etag else fetched.page.etag,
                            lastModified = if (fetched.page.status == 304) current.lastModified else fetched.page.lastModified,
                            lastAttemptAt = now, error = null, acquisitionXmlDone = true))
                        true
                    }
                    if (!committed) continue
                    feed = dao.feed(feed.id) ?: continue
                }
                val tasks = db.importTaskDao().getAllTasks().filter {
                    it.rssFeedId == feed.id && !it.articlePrepared && it.status != "PROCESSING_AI" &&
                        (it.intendedMode == "DIRECT" || feed.mode == "AI") && it.acquisitionAttempts < 5 &&
                        it.acquisitionRetryAt <= System.currentTimeMillis()
                }
                for (window in tasks.chunked(4)) {
                    if (elapsedMillis() + 35_000L >= deadline) throw RssAcquisitionYield()
                    val current = dao.feed(feed.id)
                    if (current == null || current.deleted || !current.enabled || current.revision != feed.revision) break
                    coroutineScope { window.map { task -> async { acquireTask(feed, task, network, runId = notificationRunId) } }.awaitAll() }
                    store.enforceRetention(feed.id, feed.revision)
                }
                val pending = db.importTaskDao().getAllTasks().any {
                    it.rssFeedId == feed.id && !it.articlePrepared && it.status != "PROCESSING_AI" &&
                        (it.intendedMode == "DIRECT" || feed.mode == "AI") && it.acquisitionAttempts < 5 && it.acquisitionRetryAt != Long.MAX_VALUE
                }
                incomplete = incomplete || pending
                db.withTransaction {
                    dao.feed(feed.id)?.takeIf { !it.deleted && it.enabled && it.revision == feed.revision }?.let {
                        if (!pending) dao.putFeed(it.copy(lastSuccessAt = System.currentTimeMillis(), acquisitionId = null,
                            acquisitionMobileOverride = false, acquisitionXmlDone = false))
                    }
                }
                store.enforceRetention(feed.id, feed.revision)
                acquired.tryEmit(Unit)
            }
            cleanup()
            !incomplete
        }
    }

    /** Explicit retry preserves the task's intended mode and never starts inference itself. */
    suspend fun retryAcquisition(taskId: String, promote: Boolean = false) = withContext(Dispatchers.IO) {
        me.bubblespark.tidilo.TidiloApplication.instance.awaitAiAnalysisInitialization()
        preferences.initialize()
        operation.withLock {
            val task = db.importTaskDao().getTaskById(taskId) ?: return@withLock
            val feed = task.rssFeedId?.let { dao.feed(it) } ?: return@withLock
            if (feed.deleted || !feed.enabled) {
                if (!task.articlePrepared) db.importTaskDao().updateStatus(task.id, "ERROR", "Subscription unavailable for acquisition")
                return@withLock
            }
            if (task.articlePrepared) return@withLock
            if (task.intendedMode == "AI" && feed.mode != "AI") {
                db.importTaskDao().updateStatus(task.id, "ERROR", "Subscription no longer uses AI Analyze")
                return@withLock
            }
            db.importTaskDao().insertOrUpdate(task.copy(acquisitionAttempts = 0, acquisitionRetryAt = 0))
            try {
                acquireTask(feed, task.copy(acquisitionAttempts = 0, acquisitionRetryAt = 0), articleNetwork(feed = feed), promote)
            } catch (_: RssWifiUnavailable) {
                // Connectivity waits belong to this task, not the foreground AI batch or uncaught handler.
                db.withTransaction {
                    val current = dao.feed(feed.id)
                    if (current != null && current.enabled && !current.deleted && current.revision == feed.revision) {
                        db.importTaskDao().updateStatus(task.id, "ERROR", "Waiting for an allowed network",
                            "Connect to a network allowed by RSS settings, then retry.")
                    }
                }
                return@withLock
            }
            acquired.tryEmit(Unit)
        }
    }

    private suspend fun acquireTask(feed: RssFeedEntity, task: me.bubblespark.tidilo.data.db.ImportTaskEntity,
        network: me.bubblespark.tidilo.parser.ArticleNetwork, promote: Boolean = false, runId: String? = null) {
        try {
            store.claimNotificationTask(runId, task.id)
            val parsed = me.bubblespark.tidilo.parser.ArticleAcquirer.acquire(task.url, network)
            val article = me.bubblespark.tidilo.parser.ArticlePreparation.prepare(parsed)
            if (!store.complete(feed, task, article, promote, runId)) return
            val domain = DomainIconManager.extractDomain(article.url)
            DomainPolicyManager.registerDomain(domain, false, article.siteName)
            if (article.isNsfw && !AppConfig.isNsfwContentBookmarkable.value) return
            // Optional enrichment cannot turn a committed article into an acquisition error.
            try {
                DomainIconManager.fetchAndSaveDomainIcon(context, article.url, parsed.appleTouchIconUrl, parsed.faviconUrl,
                    parsed.iconCandidates, network)
                OgImageManager.processAndSaveOgImage(context, article.url, article.ogImageUrl, network)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing cache recovery can retry optional images later. */ }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (waiting: RssWifiUnavailable) { throw waiting }
        catch (error: Exception) {
            val retryable = error is IOException && (error !is RssHttpFailure || error.retryable) &&
                (error !is me.bubblespark.tidilo.parser.ArticleHttpFailure || error.retryable)
            store.failArticle(feed, task, error, retryable, runId)
        }
    }

    suspend fun cleanup() = withContext(Dispatchers.IO) {
        for (item in dao.cleanupItems()) {
            try {
                db.withTransaction {
                    val resolved = OgImageManager.resolveImageUrl(item.articleUrl, item.imageUrl)
                    val key = OgImageManager.computeImageKey(resolved)
                    if (!dao.imageReferenced(key)) {
                        val domain = DomainIconManager.extractDomain(item.articleUrl)
                        if (domain != null) OgImageManager.deleteUnreferencedImage(context, domain, key,
                            !dao.domainReferenced(domain, "%$domain%"))
                    }
                    dao.removeCleanup(item.imageUrl)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The durable outbox retains failed removals for the next run. */ }
        }
    }

    companion object {
        private val acquired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val acquisitions = acquired.asSharedFlow()
        @Volatile private var instance: RssRepository? = null
        fun getInstance(context: Context): RssRepository = instance ?: synchronized(this) {
            instance ?: RssRepository(context.applicationContext).also { instance = it }
        }
    }
}

/** A time-slice yield preserves progress without spending the publisher-error retry budget. */
internal class RssAcquisitionYield : CancellationException("Acquisition time slice complete")
