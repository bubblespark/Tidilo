package me.bubblespark.tidilo.data.rss

import androidx.room.withTransaction
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.parser.RssEntry
import java.util.UUID
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.RssImageCleanupEntity
import me.bubblespark.tidilo.data.db.RssSeenItemEntity
import me.bubblespark.tidilo.ui.screens.library.Tag

internal class RssIngestionStore(
    private val database: AppDatabase,
    private val aiAnalysisMode: () -> me.bubblespark.tidilo.config.AiAnalysisMode = { AppConfig.aiAnalysisMode.value }
) {
    private val rss = database.rssDao()

    suspend fun registrationSnapshot(url: String): RssRegistrationSnapshot? = database.withTransaction {
        val old = rss.feedByUrl(url) ?: return@withTransaction null
        require(old.deleted) { "This feed is already registered" }
        val count = rss.savedCount(old.id)
        if (count == 0) null else RssRegistrationSnapshot(old.id, old.revision, count)
    }

    /** Recheck consent after network validation before enabling any retention or acquisition. */
    suspend fun registerFeed(feed: RssFeedEntity, confirmed: RssRegistrationSnapshot?) = database.withTransaction {
        val actual = registrationSnapshot(feed.url)
        if (actual != null && actual != confirmed) throw RssRegistrationConfirmationRequired(actual)
        val old = rss.feedByUrl(feed.url)
        require(feed.id == (old?.id ?: feed.id) && feed.revision == (old?.revision ?: -1) + 1) {
            "Subscription changed. Please try again."
        }
        rss.putFeed(feed)
    }

    /** Stage source identities before committing XML validators; failures remain independent of future XML. */
    suspend fun stage(feed: RssFeedEntity, entries: List<RssEntry>, now: Long): Boolean = database.withTransaction {
        val current = rss.feed(feed.id) ?: return@withTransaction false
        if (current.deleted || !current.enabled || current.revision != feed.revision) return@withTransaction false
        require(current.retentionLimit == null || current.retentionLimit in 10..500)
        val effectiveMode = effectiveRssImportMode(current.mode, aiAnalysisMode())
        val fresh = entries.distinctBy { it.url }.filter { entry ->
            if (rss.hasSeen(feed.id, entry.key, entry.url)) false
            else if (rss.urlExists(entry.url)) {
                val queued = database.importTaskDao().getTaskByUrl(entry.url)
                if (queued == null || queued.rssFeedId != feed.id || queued.articlePrepared) {
                    rss.recordSeen(RssSeenItemEntity(feed.id, entry.key, entry.url))
                }
                false
            } else true
        }.map { entry ->
            ImportTaskEntity(UUID.randomUUID().toString(), entry.url, entry.title, "ERROR", "Waiting for article acquisition",
                assignedTags = entry.categories, createdAt = now, rssFeedId = feed.id, rssPublishedAt = entry.publishedAt,
                intendedMode = effectiveMode, rssItemKey = entry.key)
        }
        if (effectiveMode == "AI") {
            val previous = rss.pending(feed.id)
            val staged = rss.unstartedAcquisitions(feed.id).filter { it.intendedMode == "AI" }
            val selected = selectRssPending(previous + staged, fresh)
            for (dropped in previous + staged + fresh) if (selected.none { it.id == dropped.id }) {
                rss.recordSeen(RssSeenItemEntity(feed.id, dropped.rssItemKey ?: dropped.url, dropped.url))
                database.importTaskDao().deleteById(dropped.id)
            }
            database.importTaskDao().insertAll(selected)
        } else database.importTaskDao().insertAll(fresh)
        true
    }

    /** The bookmark/ready task and consumed identity become durable in the same transaction. */
    suspend fun complete(feed: RssFeedEntity, task: ImportTaskEntity,
        article: me.bubblespark.tidilo.parser.PreparedArticle, promote: Boolean = false): Boolean = database.withTransaction {
        val current = rss.feed(feed.id) ?: return@withTransaction false
        if (current.deleted || !current.enabled || current.revision != feed.revision) return@withTransaction false
        val actual = database.importTaskDao().getTaskById(task.id) ?: return@withTransaction false
        if (actual.articlePrepared || actual.intendedMode != task.intendedMode) return@withTransaction false
        val effectiveMode = effectiveRssImportMode(actual.intendedMode, aiAnalysisMode())
        val nsfw = article.isNsfw || DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(article.url))
        if (effectiveMode == "AI" && article.bodySnippet.isBlank() && article.description.isBlank()) {
            throw IllegalArgumentException("Article contains no extracted content")
        }
        if ((!nsfw || AppConfig.isNsfwContentBookmarkable.value) && !rss.rssWriteConflict(article.url, task.id)) {
            if (effectiveMode == "DIRECT") {
                database.bookmarkDao().insertOrUpdate(BookmarkEntity(task.id, article.title, article.url, article.description,
                    entities = actual.assignedTags.take(Tag.MAX_ENTITIES), createdAt = task.createdAt,
                    updatedAt = System.currentTimeMillis(), isNsfw = nsfw, isAiSkipped = true,
                    ogImageUrl = article.ogImageUrl, rssFeedId = feed.id, rssPublishedAt = task.rssPublishedAt))
                database.importTaskDao().deleteById(task.id)
            } else {
                val updated = task.copy(url = article.url, title = article.title, siteName = article.siteName,
                    description = article.description, bodySnippet = article.bodySnippet, ogImageUrl = article.ogImageUrl,
                    ogImageKey = me.bubblespark.tidilo.data.db.bookmarkOgImageKey(article.url, article.ogImageUrl),
                    isNsfw = nsfw, articlePrepared = true, errorMessage = null,
                    status = if (actual.status == "WAITING_AI" || (promote && actual.status == "PROCESSING_AI")) "WAITING_AI" else "READY",
                    statusText = "Ready for AI Analysis")
                database.importTaskDao().insertOrUpdate(updated)
                reconcilePending()
            }
        } else database.importTaskDao().deleteById(task.id)
        rss.recordSeen(RssSeenItemEntity(feed.id, task.rssItemKey ?: task.url, task.url))
        if (article.url != task.url) rss.recordSeen(RssSeenItemEntity(feed.id, "url:${article.url}", article.url))
        true
    }

    suspend fun failArticle(feed: RssFeedEntity, task: ImportTaskEntity, error: Exception, retryable: Boolean) = database.withTransaction {
        val current = rss.feed(feed.id) ?: return@withTransaction
        if (current.deleted || current.revision != feed.revision) return@withTransaction
        val actual = database.importTaskDao().getTaskById(task.id) ?: return@withTransaction
        if (actual.articlePrepared) return@withTransaction
        val attempts = actual.acquisitionAttempts + 1
        database.importTaskDao().insertOrUpdate(actual.copy(status = "ERROR", statusText = "Article acquisition failed",
            intendedMode = effectiveRssImportMode(actual.intendedMode, aiAnalysisMode()),
            errorMessage = error.message ?: "Could not fetch article", acquisitionAttempts = attempts,
            acquisitionRetryAt = if (retryable && attempts < 5) System.currentTimeMillis() + 60_000L * attempts else Long.MAX_VALUE))
    }

    /** XML failure disables only the still-current subscription; article failures do not use this path. */
    suspend fun disableAfterXmlFailure(feed: RssFeedEntity): Boolean = database.withTransaction {
        val current = rss.feed(feed.id) ?: return@withTransaction false
        if (current.deleted || !current.enabled || current.revision != feed.revision) return@withTransaction false
        rss.putFeed(current.copy(enabled = false, revision = current.revision + 1,
            lastAttemptAt = System.currentTimeMillis(), error = "XML_FETCH_FAILED",
            acquisitionId = null, acquisitionMobileOverride = false, acquisitionXmlDone = false))
        true
    }

    suspend fun enforceRetention(feedId: String, expectedRevision: Long? = null) = database.withTransaction {
        rss.feed(feedId)?.takeIf { !it.deleted && it.enabled && (expectedRevision == null || it.revision == expectedRevision) }?.let { enforceRetentionLocked(it) }
    }

    suspend fun restoreTask(task: ImportTaskEntity) = database.withTransaction {
        val feed = task.rssFeedId?.let { rss.feed(it) } ?: return@withTransaction
        if (task.status != "ERROR" && (feed.deleted || feed.mode != "AI")) return@withTransaction
        if (rss.rssWriteConflict(task.url, task.id)) return@withTransaction
        database.importTaskDao().insertOrUpdate(task)
        reconcilePending()
    }

    private suspend fun enforceRetentionLocked(feed: RssFeedEntity) {
        val limit = feed.retentionLimit ?: return
        require(limit in 10..500)
        for (bookmark in rss.excess(feed.id, limit)) {
            bookmark.ogImageUrl?.let { rss.enqueueCleanup(RssImageCleanupEntity(it, bookmark.url)) }
            database.bookmarkDao().deleteById(bookmark.id)
        }
    }

    /** Applies the same cap after cancellation, quota rollback, or swipe Undo. */
    suspend fun reconcilePending() = database.withTransaction {
        for (feed in rss.activeFeeds()) {
            val previous = rss.pending(feed.id)
            val kept = selectRssPending(previous, emptyList())
            database.importTaskDao().deleteByIds(previous.filter { old -> kept.none { it.id == old.id } }.map { it.id })
        }
    }
}

internal class RssImportSkipped : Exception("RSS item already exists or its subscription was removed")

internal data class RssRegistrationSnapshot(val feedId: String, val revision: Long, val savedCount: Int)
internal class RssRegistrationConfirmationRequired(val snapshot: RssRegistrationSnapshot) :
    Exception("Existing feed articles require confirmation")
