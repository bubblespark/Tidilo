package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EngagementDao {
    @Insert
    suspend fun insert(event: EngagementEventEntity)

    @Query("SELECT * FROM engagement_events WHERE bookmarkId = :bookmarkId ORDER BY at, id")
    suspend fun eventsFor(bookmarkId: String): List<EngagementEventEntity>

    @Query("DELETE FROM engagement_events WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Deletes events whose bookmark is neither active nor in Trash. */
    @Query("DELETE FROM engagement_events WHERE bookmarkId NOT IN (SELECT id FROM bookmarks UNION SELECT id FROM trashed_bookmarks)")
    suspend fun deleteOrphans(): Int
}
