package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RssDao {
    @Query("""SELECT b.* FROM bookmarks b WHERE b.rssFeedId IS NOT NULL AND b.isLiked = 0
        AND NOT EXISTS (SELECT 1 FROM bookmark_collection_cross_ref r WHERE r.bookmarkId = b.id)
        AND ((b.lastInteractedAt IS NULL AND b.createdAt <= :unreadCutoff)
            OR (b.lastInteractedAt IS NOT NULL AND b.lastInteractedAt <= :readCutoff))
        ORDER BY b.id LIMIT 100""")
    suspend fun expiredRssBookmarks(unreadCutoff: Long, readCutoff: Long): List<BookmarkEntity>

    @Query("""SELECT f.*, COUNT(*) AS count FROM rss_feeds f JOIN bookmarks b ON b.rssFeedId=f.id
        WHERE (:showNsfw OR NOT b.isNsfw) GROUP BY f.id""")
    fun observeLibraryGroups(showNsfw: Boolean): Flow<List<RssFeedGroup>>

    @Query("SELECT * FROM rss_feeds ORDER BY title COLLATE NOCASE, id")
    fun observeFeeds(): Flow<List<RssFeedEntity>>

    @Query("SELECT * FROM rss_feeds WHERE deleted = 0 ORDER BY id")
    suspend fun activeFeeds(): List<RssFeedEntity>

    @Query("SELECT * FROM rss_feeds WHERE deleted = 0 AND enabled = 1 ORDER BY id")
    suspend fun enabledFeeds(): List<RssFeedEntity>

    @Query("SELECT * FROM rss_feeds WHERE id = :id")
    suspend fun feed(id: String): RssFeedEntity?

    @Query("SELECT * FROM rss_feeds WHERE url = :url")
    suspend fun feedByUrl(url: String): RssFeedEntity?

    @Query("SELECT COUNT(*) FROM bookmarks WHERE rssFeedId = :feedId")
    suspend fun savedCount(feedId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFeed(feed: RssFeedEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM rss_seen_items WHERE feedId = :feedId AND (itemKey = :key OR url = :url))")
    suspend fun hasSeen(feedId: String, key: String, url: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordSeen(item: RssSeenItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueCleanup(item: RssImageCleanupEntity)

    @Query("SELECT * FROM rss_image_cleanup")
    suspend fun cleanupItems(): List<RssImageCleanupEntity>

    @Query("DELETE FROM rss_image_cleanup WHERE imageUrl = :url AND articleUrl = :articleUrl")
    suspend fun removeCleanup(url: String, articleUrl: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url = :url UNION ALL SELECT 1 FROM import_tasks WHERE url = :url)")
    suspend fun urlExists(url: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND id <> :id UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url = :url)")
    suspend fun rssWriteConflict(url: String, id: String): Boolean

    @Query("SELECT * FROM import_tasks WHERE rssFeedId = :feedId AND status IN ('QUEUED', 'PROCESSING')")
    suspend fun pending(feedId: String): List<ImportTaskEntity>

    @Query("""SELECT * FROM import_tasks WHERE rssFeedId = :feedId AND status = 'ERROR'
        AND articlePrepared = 0 AND acquisitionAttempts = 0 AND rssItemKey IS NOT NULL AND errorMessage IS NULL""")
    suspend fun unstartedAcquisitions(feedId: String): List<ImportTaskEntity>


    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE ogImageKey = :key UNION ALL SELECT 1 FROM trashed_bookmarks WHERE ogImageKey = :key UNION ALL SELECT 1 FROM import_tasks WHERE ogImageKey = :key AND status <> 'COMPLETED')")
    suspend fun imageReferenced(key: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE domain = :domain UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url LIKE :pattern UNION ALL SELECT 1 FROM import_tasks WHERE url LIKE :pattern AND status <> 'COMPLETED')")
    suspend fun domainReferenced(domain: String, pattern: String): Boolean

    @Query("""SELECT ogImageKey AS imageKey, url, COALESCE(lastInteractedAt, createdAt) AS lastAt FROM bookmarks
        UNION ALL SELECT ogImageKey AS imageKey, url, 9223372036854775807 AS lastAt FROM trashed_bookmarks
        UNION ALL SELECT ogImageKey AS imageKey, url, 9223372036854775807 AS lastAt FROM import_tasks WHERE status <> 'COMPLETED'""")
    suspend fun imageRetentionReferences(): List<OgCacheReference>
}

data class OgCacheReference(val imageKey: String?, val url: String, val lastAt: Long)
