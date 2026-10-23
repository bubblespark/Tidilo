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

internal class RssIngestionStore(private val database: AppDatabase) {
    private val rss = database.rssDao()

    /** Receipt and content changes commit together; stale fetches cannot resurrect deleted feeds. */
    suspend fun ingest(feed: RssFeedEntity, entries: List<RssEntry>, now: Long): Boolean = database.withTransaction {
        val current = rss.feed(feed.id) ?: return@withTransaction false
        if (current.deleted || !current.enabled || current.revision != feed.revision) return@withTransaction false
        require(current.mode in setOf("AI", "DIRECT"))
        val fresh = mutableListOf<ImportTaskEntity>()
        for (entry in entries.distinctBy { it.url }) {
            if (rss.hasSeen(feed.id, entry.key, entry.url)) continue
            val nsfw = DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(entry.url))
            if (nsfw && !AppConfig.isNsfwContentBookmarkable.value) continue
            rss.recordSeen(RssSeenItemEntity(feed.id, entry.key, entry.url))
            if (rss.urlExists(entry.url)) continue
            val task = ImportTaskEntity(UUID.randomUUID().toString(), entry.url, entry.title, "READY", "Ready for AI Analysis",
                createdAt = now, isNsfw = nsfw, siteName = current.title, description = entry.description,
                bodySnippet = entry.bodySnippet, ogImageUrl = entry.imageUrl, rssFeedId = current.id, rssPublishedAt = entry.publishedAt)
            if (current.mode == "DIRECT") {
                database.bookmarkDao().insertOrUpdate(BookmarkEntity(task.id, task.title, task.url, task.description,
                    createdAt = now, updatedAt = now, isNsfw = nsfw, isAiSkipped = true,
                    ogImageUrl = task.ogImageUrl, rssFeedId = feed.id, rssPublishedAt = entry.publishedAt))
            } else fresh.add(task)
        }
        if (current.mode == "AI") {
            val previous = rss.pending(feed.id)
            val kept = selectRssPending(previous, fresh)
            database.importTaskDao().deleteByIds(previous.filter { old -> kept.none { it.id == old.id } }.map { it.id })
            database.importTaskDao().insertAll(kept)
        }
        enforceRetentionLocked(current)
        true
    }

    suspend fun enforceRetention(feedId: String) = database.withTransaction {
        rss.feed(feedId)?.let { enforceRetentionLocked(it) }
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
