package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One timestamped engagement with a bookmark.
 *
 * [type] and [surface] hold `EngagementType.code` and `EngagementSurface.code`.
 * There is no foreign key: events outlive a move to Trash and are pruned by maintenance.
 */
@Entity(
    tableName = "engagement_events",
    indices = [Index("bookmarkId"), Index("at")]
)
data class EngagementEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookmarkId: String,
    val type: Int,
    val surface: Int,
    val at: Long
)
