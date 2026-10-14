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

    @Query("SELECT * FROM trashed_bookmarks ORDER BY trashedAt DESC")
    fun getAllTrashFlow(): Flow<List<TrashedBookmarkEntity>>

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
