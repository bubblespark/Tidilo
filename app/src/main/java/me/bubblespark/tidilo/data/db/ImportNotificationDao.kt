package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for import_notifications table.
 */
@Dao
interface ImportNotificationDao {

    @Query("SELECT * FROM import_notifications ORDER BY createdAt DESC, id DESC LIMIT 50")
    fun observeNotifications(): Flow<List<ImportNotificationEntity>>

    @Query("SELECT COUNT(*) FROM import_notifications WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: ImportNotificationEntity): Long

    @Query("UPDATE import_notifications SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    @Query("DELETE FROM import_notifications")
    suspend fun deleteAll()

    @Query("DELETE FROM import_notifications WHERE id = :id")
    suspend fun deleteById(id: Long)
}
