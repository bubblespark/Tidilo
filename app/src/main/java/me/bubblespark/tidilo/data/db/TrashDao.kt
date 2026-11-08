package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for trashed_bookmarks table.
 */
@Dao
interface TrashDao {

    @Query("SELECT t.*, EXISTS(SELECT 1 FROM bookmarks b WHERE b.url = t.url) AS duplicateInLibrary FROM trashed_bookmarks t WHERE (:showNsfw OR NOT t.isNsfw) ORDER BY t.trashedAt DESC, t.id DESC")
    fun observeTrash(showNsfw: Boolean): Flow<List<TrashBookmarkRow>>

    @Query("SELECT * FROM trashed_bookmarks WHERE id = :id")
    fun observeById(id: String): Flow<TrashedBookmarkEntity?>

    @Query("SELECT * FROM trashed_bookmarks WHERE id = :id")
    suspend fun getById(id: String): TrashedBookmarkEntity?

    @Query("DELETE FROM trashed_bookmarks WHERE id = :id")
    suspend fun deleteByIdCounted(id: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM import_tasks WHERE url = :url AND status IN ('QUEUED', 'PROCESSING', 'ERROR'))")
    suspend fun hasConflictingImport(url: String): Boolean

    @Query("SELECT * FROM trashed_bookmarks ORDER BY trashedAt DESC")
    suspend fun getAllTrash(): List<TrashedBookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashedBookmarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TrashedBookmarkEntity>)

    @Query("DELETE FROM trashed_bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM trashed_bookmarks WHERE trashedAt < :cutoff")
    suspend fun purgeExpired(cutoff: Long): Int

    @Query("DELETE FROM trashed_bookmarks WHERE id NOT IN (SELECT id FROM trashed_bookmarks ORDER BY trashedAt DESC LIMIT :maxCapacity)")
    suspend fun enforceCapacity(maxCapacity: Int)

    @Query("SELECT COUNT(*) FROM trashed_bookmarks")
    suspend fun getCount(): Int

    @Query("DELETE FROM trashed_bookmarks")
    suspend fun deleteAll()
}
