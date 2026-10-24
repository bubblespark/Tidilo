package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for import_tasks table.
 */
@Dao
interface ImportTaskDao {

    @Query("SELECT * FROM import_tasks ORDER BY createdAt DESC")
    fun getAllTasksFlow(): Flow<List<ImportTaskEntity>>

    @Query("SELECT * FROM import_tasks ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<ImportTaskEntity>

    @Query("SELECT * FROM import_tasks WHERE status IN (:statuses) ORDER BY createdAt DESC")
    fun getTasksByStatusesFlow(statuses: List<String>): Flow<List<ImportTaskEntity>>

    @Query("SELECT * FROM import_tasks WHERE status IN (:statuses) ORDER BY createdAt DESC")
    suspend fun getTasksByStatuses(statuses: List<String>): List<ImportTaskEntity>

    @Query("SELECT * FROM import_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): ImportTaskEntity?

    @Query("SELECT * FROM import_tasks WHERE url = :url LIMIT 1")
    suspend fun getTaskByUrl(url: String): ImportTaskEntity?

    @Query("UPDATE import_tasks SET rssFeedId = NULL WHERE id = :id")
    suspend fun clearRssFeedId(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: ImportTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<ImportTaskEntity>)

    @Update
    suspend fun update(task: ImportTaskEntity)

    @Query("UPDATE import_tasks SET status = :status, statusText = :statusText, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, statusText: String, errorMessage: String? = null)

    // A single statement claims only existing work; ingestion/edit transactions can remove only unstarted work.
    @Query("""UPDATE import_tasks SET status = 'PROCESSING_AI', statusText = :statusText, errorMessage = NULL
        WHERE id = :id AND status IN ('WAITING_AI', 'PROCESSING_AI') AND
        (rssFeedId IS NULL OR (articlePrepared = 1 AND intendedMode = 'AI' AND EXISTS (SELECT 1 FROM rss_feeds f WHERE f.id = rssFeedId AND f.deleted = 0
            AND (f.mode = 'AI' OR import_tasks.status = 'PROCESSING_AI'))))""")
    suspend fun claimForAi(id: String, statusText: String): Int

    @Query("""UPDATE import_tasks SET status = 'ERROR', statusText = 'Subscription unavailable',
        errorMessage = 'This RSS subscription was removed or no longer uses AI Analyze.'
        WHERE rssFeedId IS NOT NULL AND status IN ('WAITING_AI', 'PROCESSING_AI') AND
        NOT EXISTS (SELECT 1 FROM rss_feeds f WHERE f.id = rssFeedId AND f.deleted = 0
            AND (f.mode = 'AI' OR import_tasks.status = 'PROCESSING_AI'))""")
    suspend fun rejectUnavailableRss()

    @Query("DELETE FROM import_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM import_tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM import_tasks WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("SELECT COUNT(*) FROM import_tasks")
    suspend fun getCount(): Int
}
