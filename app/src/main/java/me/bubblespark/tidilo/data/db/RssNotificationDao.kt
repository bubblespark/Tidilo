package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Durable accounting shares the article commit so retries never count an item twice. */
@Entity(tableName = "rss_notification_runs")
data class RssNotificationRunEntity(
    @PrimaryKey val id: String,
    val workId: String,
    val requestedAt: Long,
    val showSummary: Boolean,
    val completed: Boolean = false,
    val published: Boolean = false,
    val pendingAi: Int = 0
)

@Entity(tableName = "rss_notification_items", primaryKeys = ["runId", "itemId"])
data class RssNotificationItemEntity(
    val runId: String,
    val itemId: String,
    val outcome: String,
    val detail: String = ""
)

@Dao
interface RssNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRun(run: RssNotificationRunEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItem(item: RssNotificationItemEntity)
    @Query("SELECT * FROM rss_notification_runs WHERE workId = :workId AND completed = 0 LIMIT 1")
    suspend fun unfinishedRun(workId: String): RssNotificationRunEntity?
    @Query("SELECT * FROM rss_notification_runs WHERE workId = :workId AND requestedAt = :requestedAt LIMIT 1")
    suspend fun runFor(workId: String, requestedAt: Long): RssNotificationRunEntity?
    @Query("SELECT * FROM rss_notification_runs WHERE workId = :workId ORDER BY requestedAt DESC LIMIT 1")
    suspend fun latestRun(workId: String): RssNotificationRunEntity?
    @Query("""SELECT n.runId FROM rss_notification_items n JOIN rss_notification_runs r ON r.id = n.runId
        WHERE n.itemId = :itemId AND r.completed = 0 ORDER BY r.requestedAt LIMIT 1""")
    suspend fun pendingOwner(itemId: String): String?
    @Query("""UPDATE rss_notification_items SET outcome = 'SKIPPED', detail = '' WHERE itemId = :itemId
        AND runId IN (SELECT id FROM rss_notification_runs WHERE completed = 0)""")
    suspend fun discardItem(itemId: String)
    @Query("""UPDATE rss_notification_runs SET completed = 1, pendingAi =
        (SELECT COUNT(*) FROM rss_notification_items n JOIN import_tasks t ON t.id = n.itemId
         WHERE n.runId = rss_notification_runs.id AND n.outcome = 'AI' AND t.status = 'READY')
        WHERE completed = 0 AND NOT EXISTS (SELECT 1 FROM rss_feeds WHERE enabled = 1 AND deleted = 0)""")
    suspend fun finishRunsWithoutEnabledFeeds()
    @Query("SELECT * FROM rss_notification_runs WHERE completed = 1 AND published = 0 ORDER BY requestedAt")
    suspend fun unpublishedRuns(): List<RssNotificationRunEntity>
    @Query("SELECT * FROM rss_notification_items WHERE runId = :runId")
    suspend fun items(runId: String): List<RssNotificationItemEntity>
    @Query("""SELECT COUNT(*) FROM rss_notification_items n JOIN import_tasks t ON t.id = n.itemId
        WHERE n.runId = :runId AND n.outcome = 'AI' AND t.status = 'READY'""")
    suspend fun pendingAiCount(runId: String): Int
    @Query("""UPDATE rss_notification_runs SET completed = 1, pendingAi =
        (SELECT COUNT(*) FROM rss_notification_items n JOIN import_tasks t ON t.id = n.itemId
         WHERE n.runId = :runId AND n.outcome = 'AI' AND t.status = 'READY')
        WHERE id = :runId AND completed = 0""")
    suspend fun finish(runId: String)
    @Query("UPDATE rss_notification_runs SET published = 1 WHERE id = :runId")
    suspend fun markPublished(runId: String)
    @Query("DELETE FROM rss_notification_items WHERE runId IN (SELECT id FROM rss_notification_runs WHERE published = 1 AND requestedAt < :before)")
    suspend fun pruneItems(before: Long)
    @Query("DELETE FROM rss_notification_runs WHERE published = 1 AND requestedAt < :before")
    suspend fun pruneRuns(before: Long)
}
