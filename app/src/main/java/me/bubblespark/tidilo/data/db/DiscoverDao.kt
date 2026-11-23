package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Discover storage: engine input, daily snapshot and weekly map, suppressions, topic mutes, and the
 * live shelves. Event type literals: 2 DETAIL, 3 BROWSER_OPEN, 8 IMPRESSION, 9 DECK_FLICK.
 */
@Dao
interface DiscoverDao {
    // ---- Engine input ----

    @Query("""SELECT b.id, b.createdAt, b.lastInteractedAt, b.isPrivate, b.ogImageUrl IS NOT NULL AS hasOgImage,
        b.domain, b.subcategory, b.rssFeedId,
        (b.isLiked OR EXISTS (SELECT 1 FROM bookmark_collection_cross_ref r WHERE r.bookmarkId = b.id)) AS isProtected
        FROM bookmarks b""")
    suspend fun candidates(): List<DiscoverCandidateProjection>

    @Query("SELECT bookmarkId, skeletonKey, value FROM bookmark_entities ORDER BY bookmarkId, position")
    suspend fun entityRefs(): List<DiscoverEntityProjection>

    /** Events inside the retention window plus the latest open of each bookmark at any age. */
    @Query("SELECT bookmarkId, type, at FROM engagement_events WHERE at >= :cutoff OR type IN (2, 3)")
    suspend fun events(cutoff: Long): List<DiscoverEventProjection>

    @Query("SELECT DISTINCT bookmarkId FROM engagement_events WHERE type = 9 AND at >= :since")
    suspend fun flickedSince(since: Long): List<String>

    @Query("SELECT EXISTS (SELECT 1 FROM engagement_events WHERE bookmarkId = :bookmarkId AND type = :type AND at >= :since)")
    suspend fun hasEventSince(bookmarkId: String, type: Int, since: Long): Boolean

    // ---- Snapshot ----

    @Query("SELECT * FROM discover_snapshot_meta WHERE scope = :scope AND day = :day")
    suspend fun meta(scope: Int, day: Long): DiscoverSnapshotMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: DiscoverSnapshotMetaEntity)

    @Query("DELETE FROM discover_snapshot WHERE day = :day")
    suspend fun deleteSnapshotDay(day: Long)

    @Insert
    suspend fun insertSnapshot(rows: List<DiscoverSnapshotEntity>)

    @Query("DELETE FROM discover_map WHERE week = :week")
    suspend fun deleteMapWeek(week: Long)

    @Insert
    suspend fun insertMap(rows: List<DiscoverMapEntity>)

    @Query("SELECT bookmarkId FROM discover_snapshot WHERE shelf = 1 AND day >= :sinceDay AND day < :beforeDay")
    suspend fun heroIds(sinceDay: Long, beforeDay: Long): List<String>

    @Query("SELECT * FROM discover_snapshot WHERE shelf = 8 AND day >= :sinceDay AND day < :beforeDay ORDER BY day, position")
    suspend fun connectionRows(sinceDay: Long, beforeDay: Long): List<DiscoverSnapshotEntity>

    @Query("SELECT * FROM discover_snapshot WHERE day = :day ORDER BY shelf, position")
    fun observeSnapshot(day: Long): Flow<List<DiscoverSnapshotEntity>>

    @Query("SELECT * FROM bookmarks WHERE id IN (SELECT bookmarkId FROM discover_snapshot WHERE day = :day)")
    fun observeSnapshotBookmarks(day: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM discover_map WHERE week = :week ORDER BY position")
    fun observeMap(week: Long): Flow<List<DiscoverMapEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE (:showPrivate OR NOT isPrivate)")
    fun observeLibraryCount(showPrivate: Boolean): Flow<Int>

    // ---- Suppressions and mutes ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuppression(suppression: RediscoverySuppressionEntity)

    @Query("DELETE FROM rediscovery_suppressions WHERE bookmarkId = :bookmarkId")
    suspend fun deleteSuppression(bookmarkId: String)

    @Query("SELECT bookmarkId FROM rediscovery_suppressions WHERE permanent OR until > :now")
    suspend fun activeSuppressedIds(now: Long): List<String>

    @Query("SELECT * FROM rediscovery_suppressions")
    fun observeSuppressions(): Flow<List<RediscoverySuppressionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMute(mute: RediscoveryTopicMuteEntity)

    @Query("DELETE FROM rediscovery_topic_mutes WHERE axis = :axis AND `key` = :key")
    suspend fun deleteMute(axis: Int, key: String)

    @Query("SELECT * FROM rediscovery_topic_mutes")
    suspend fun mutes(): List<RediscoveryTopicMuteEntity>

    @Query("SELECT * FROM rediscovery_topic_mutes")
    fun observeMutes(): Flow<List<RediscoveryTopicMuteEntity>>

    // ---- Live shelves ----

    @Query("""SELECT * FROM bookmarks WHERE rssFeedId IS NULL AND createdAt >= :since AND (:showPrivate OR NOT isPrivate)
        AND id NOT IN (SELECT bookmarkId FROM rediscovery_suppressions WHERE permanent OR until > :now)
        ORDER BY createdAt DESC, id DESC LIMIT :limit""")
    fun observeJustSaved(since: Long, now: Long, showPrivate: Boolean, limit: Int): Flow<List<BookmarkEntity>>

    @Query("""SELECT b.*, (b.isLiked OR EXISTS (SELECT 1 FROM bookmark_collection_cross_ref r WHERE r.bookmarkId = b.id)) AS isProtected
        FROM bookmarks b WHERE b.rssFeedId IS NOT NULL AND b.lastInteractedAt IS NULL AND (:showPrivate OR NOT b.isPrivate)
        AND b.id NOT IN (SELECT bookmarkId FROM rediscovery_suppressions WHERE permanent OR until > :now)
        ORDER BY b.createdAt DESC, b.id DESC LIMIT :limit""")
    fun observeFreshRss(now: Long, showPrivate: Boolean, limit: Int): Flow<List<FreshRssRow>>

    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun bookmarksByIds(ids: List<String>): List<BookmarkEntity>

    // ---- Maintenance and reset ----

    @Query("DELETE FROM discover_snapshot WHERE day < :beforeDay")
    suspend fun pruneSnapshot(beforeDay: Long): Int

    @Query("DELETE FROM discover_map WHERE week < :beforeWeek")
    suspend fun pruneMap(beforeWeek: Long): Int

    @Query("DELETE FROM discover_snapshot_meta WHERE (scope = 0 AND day < :beforeDay) OR (scope = 1 AND day < :beforeWeek)")
    suspend fun pruneMeta(beforeDay: Long, beforeWeek: Long): Int

    @Query("DELETE FROM rediscovery_suppressions WHERE NOT permanent AND (until IS NULL OR until <= :now)")
    suspend fun deleteExpiredSuppressions(now: Long): Int

    /** Suppressions whose bookmark is neither active nor in Trash. */
    @Query("DELETE FROM rediscovery_suppressions WHERE bookmarkId NOT IN (SELECT id FROM bookmarks UNION SELECT id FROM trashed_bookmarks)")
    suspend fun deleteOrphanSuppressions(): Int

    @Query("DELETE FROM discover_snapshot")
    suspend fun clearSnapshot()

    @Query("DELETE FROM discover_map")
    suspend fun clearMap()

    @Query("DELETE FROM discover_snapshot_meta")
    suspend fun clearMeta()

    @Query("DELETE FROM rediscovery_suppressions")
    suspend fun clearSuppressions()

    @Query("DELETE FROM rediscovery_topic_mutes")
    suspend fun clearMutes()

    @Query("DELETE FROM engagement_events")
    suspend fun clearEvents()
}
