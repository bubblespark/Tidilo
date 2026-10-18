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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: ImportTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<ImportTaskEntity>)

    @Update
    suspend fun update(task: ImportTaskEntity)

    @Query("UPDATE import_tasks SET status = :status, statusText = :statusText, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, statusText: String, errorMessage: String? = null)

    @Query("DELETE FROM import_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM import_tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM import_tasks WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("SELECT COUNT(*) FROM import_tasks")
    suspend fun getCount(): Int
}
