package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.room.withTransaction
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
internal class RssRepository private constructor(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dao = db.rssDao()
    private val preferences = RssPreferences.getInstance(context)
    private val client = RssHttpClient(context) { preferences.state.value.wifiOnly }
    private val fetcher = RssFeedFetcher(client::request)
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
            val fetched = fetcher.fetch(normalized, null, null)
            val document = requireNotNull(fetched.document) { "Feed returned no document" }
            val feed = RssFeedEntity(old?.id ?: UUID.randomUUID().toString(), normalized, document.title, mode, limit,
                revision = (old?.revision ?: -1) + 1)
            // A validated subscription is retained even if its first article-metadata request fails.
            store.registerFeed(feed, confirmed)
            try {
                ingestFetched(feed, fetched)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { recordError(feed, error) }
            RssScheduler.reschedule(context)
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
                val updated = previous.copy(mode = mode, retentionLimit = limit, revision = previous.revision + 1)
                dao.putFeed(updated)
                if (previous.mode == "AI" && mode == "DIRECT") {
                    val pending = dao.pending(id)
                    if (convertPending) for (task in pending) {
                        val nsfw = task.isNsfw || DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(task.url))
                        if ((!nsfw || AppConfig.isNsfwContentBookmarkable.value) && !dao.rssWriteConflict(task.url, task.id)) {
                            db.bookmarkDao().insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity(
                                task.id, task.title, task.url, task.description, createdAt = task.createdAt, updatedAt = System.currentTimeMillis(),
                                isNsfw = nsfw, isAiSkipped = true, ogImageUrl = task.ogImageUrl,
                                rssFeedId = id, rssPublishedAt = task.rssPublishedAt))
                        }
                    }
                    db.importTaskDao().deleteByIds(pending.map { it.id })
                }
                store.enforceRetention(id)
            }
            cleanup()
            RssScheduler.reschedule(context)
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        run {
            db.withTransaction {
                val feed = requireNotNull(dao.feed(id))
                dao.putFeed(feed.copy(deleted = true, revision = feed.revision + 1))
                db.importTaskDao().deleteByIds(dao.pending(id).map { it.id })
            }
            RssScheduler.reschedule(context)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val feed = requireNotNull(dao.feed(id))
            require(!feed.deleted)
            dao.putFeed(feed.copy(enabled = enabled, revision = feed.revision + 1))
        }
        RssScheduler.reschedule(context)
    }

    /** Successful feeds are skipped on retries of the same scheduled/manual request. */
    suspend fun fetchAll(since: Long): Boolean = withContext(Dispatchers.IO) {
        preferences.initialize()
        operation.withLock {
            var transientFailure = false
            for (feed in dao.enabledFeeds()) {
                if (dao.feed(feed.id)?.let { it.enabled && !it.deleted && it.revision == feed.revision } != true) continue
                if ((feed.lastSuccessAt ?: Long.MIN_VALUE) >= since) continue
                try {
                    val fetched = fetcher.fetch(feed.url, feed.etag, feed.lastModified)
                    ingestFetched(feed, fetched)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (wifi: RssWifiUnavailable) { throw wifi }
                catch (error: Exception) {
                    recordError(feed, error)
                    if (error is IOException && (error !is RssHttpFailure || error.retryable)) transientFailure = true
                }
            }
            cleanup()
            !transientFailure
        }
    }

    private suspend fun ingestFetched(feed: RssFeedEntity, fetched: RssFetchedFeed) {
        val entries = fetched.document?.entries.orEmpty()
        val now = System.currentTimeMillis()
        var accepted = true
        if (feed.mode == "DIRECT") {
            // Bounded windows limit resource use, not ingestion: every supplied entry is processed.
            for (window in entries.chunked(20)) {
                val current = dao.feed(feed.id)
                if (current == null || current.deleted || !current.enabled || current.revision != feed.revision) return
                val semaphore = Semaphore(4)
                val enriched = coroutineScope {
                    window.map { entry -> async {
                        semaphore.withPermit {
                            if (dao.hasSeen(feed.id, entry.key, entry.url) || dao.urlExists(entry.url)) entry
                            else fetcher.enrich(entry)
                        }
                    } }.awaitAll()
                }
                if (!store.ingest(feed, enriched, now)) return
            }
            if (entries.isEmpty()) accepted = store.ingest(feed, emptyList(), now)
        } else {
            val unseen = entries.filter { !dao.hasSeen(feed.id, it.key, it.url) && !dao.urlExists(it.url) }
            val selected = unseen.sortedWith(compareByDescending<me.bubblespark.tidilo.parser.RssEntry> { it.publishedAt ?: now }.thenBy { it.url }).take(5)
            val enriched = selected.associate { it.url to fetcher.enrich(it) }
            accepted = store.ingest(feed, entries.map { enriched[it.url] ?: it }, now)
        }
        if (accepted) {
            db.withTransaction {
                val current = dao.feed(feed.id)
                if (current != null && !current.deleted && current.enabled && current.revision == feed.revision) {
                    dao.putFeed(current.copy(title = fetched.document?.title ?: current.title,
                        etag = fetched.page.etag ?: current.etag, lastModified = fetched.page.lastModified ?: current.lastModified,
                        lastAttemptAt = now, lastSuccessAt = now, error = null))
                }
            }
            acquired.tryEmit(Unit)
        }
    }

    private suspend fun recordError(feed: RssFeedEntity, error: Exception) = db.withTransaction {
        dao.feed(feed.id)?.takeIf { !it.deleted && it.revision == feed.revision }?.let {
            dao.putFeed(it.copy(lastAttemptAt = System.currentTimeMillis(), error = error.message ?: "Could not fetch feed"))
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
