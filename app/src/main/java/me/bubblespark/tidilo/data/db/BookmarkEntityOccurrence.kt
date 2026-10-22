package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Keeps original occurrence counts, including duplicate values within one bookmark. */
@Entity(
    tableName = "bookmark_entities",
    primaryKeys = ["bookmarkId", "position"],
    foreignKeys = [ForeignKey(
        entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmarkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookmarkId")]
)
data class BookmarkEntityOccurrence(
    val bookmarkId: String,
    val position: Int,
    val value: String,
    val searchValue: String
)

data class DomainCount(val domain: String, val count: Int)
data class TagCount(val value: String, val count: Int)
