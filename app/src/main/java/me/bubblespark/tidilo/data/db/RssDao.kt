package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RssDao {
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

    @Query("DELETE FROM rss_image_cleanup WHERE imageUrl = :url")
    suspend fun removeCleanup(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url = :url UNION ALL SELECT 1 FROM import_tasks WHERE url = :url)")
    suspend fun urlExists(url: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND id <> :id UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url = :url)")
    suspend fun rssWriteConflict(url: String, id: String): Boolean

    @Query("SELECT * FROM import_tasks WHERE rssFeedId = :feedId AND status IN ('READY', 'WAITING_AI')")
    suspend fun pending(feedId: String): List<ImportTaskEntity>

    @Query("""SELECT * FROM import_tasks WHERE rssFeedId = :feedId AND status = 'ERROR'
        AND articlePrepared = 0 AND acquisitionAttempts = 0 AND rssItemKey IS NOT NULL AND errorMessage IS NULL""")
    suspend fun unstartedAcquisitions(feedId: String): List<ImportTaskEntity>

    @Query("SELECT * FROM bookmarks WHERE rssFeedId = :feedId ORDER BY COALESCE(rssPublishedAt, createdAt) DESC, createdAt DESC, id DESC LIMIT -1 OFFSET :limit")
    suspend fun excess(feedId: String, limit: Int): List<BookmarkEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE ogImageKey = :key UNION ALL SELECT 1 FROM trashed_bookmarks WHERE ogImageKey = :key UNION ALL SELECT 1 FROM import_tasks WHERE ogImageKey = :key AND status <> 'COMPLETED')")
    suspend fun imageReferenced(key: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE domain = :domain UNION ALL SELECT 1 FROM trashed_bookmarks WHERE url LIKE :pattern UNION ALL SELECT 1 FROM import_tasks WHERE url LIKE :pattern AND status <> 'COMPLETED')")
    suspend fun domainReferenced(domain: String, pattern: String): Boolean
}
