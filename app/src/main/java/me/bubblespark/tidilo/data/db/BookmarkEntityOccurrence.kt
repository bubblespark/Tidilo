package me.bubblespark.tidilo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.bubblespark.tidilo.data.entitySkeletonKey

/** Stores ordered canonical display values and indexed identity for active bookmarks. */
@Entity(
    tableName = "bookmark_entities",
    primaryKeys = ["bookmarkId", "position"],
    foreignKeys = [ForeignKey(
        entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmarkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookmarkId"), Index("skeletonKey")]
)
data class BookmarkEntityOccurrence(
    val bookmarkId: String,
    val position: Int,
    val value: String,
    val searchValue: String,
    @ColumnInfo(defaultValue = "''") val skeletonKey: String = entitySkeletonKey(value)
)

data class DomainCount(val domain: String, val count: Int)
data class TagCount(val value: String, val count: Int)
