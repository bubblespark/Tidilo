package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem

/**
 * Room entity representing a trashed bookmark held for retention before permanent purge.
 */
@Entity(
    tableName = "trashed_bookmarks",
    indices = [
        Index(value = ["trashedAt"])
    ]
)
data class TrashedBookmarkEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String,
    val description: String,
    val category: String? = null,
    val subcategory: String? = null,
    val entities: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val note: String,
    val isNsfw: Boolean,
    val isAiSkipped: Boolean = false,
    val trashedAt: Long
) {
    fun toTrashedBookmarkItem(): TrashedBookmarkItem {
        val bookmark = BookmarkItem(
            id = id,
            title = title,
            url = url,
            description = description,
            tag = if (isAiSkipped && category == null && subcategory == null && entities.isEmpty()) {
                Tag.createEmpty()
            } else {
                Tag.create(
                    category = category,
                    subcategory = subcategory,
                    entities = entities
                )
            },
            createdAt = createdAt,
            updatedAt = updatedAt,
            note = note,
            isNsfw = isNsfw,
            isAiSkipped = isAiSkipped
        )
        return TrashedBookmarkItem(
            bookmark = bookmark,
            trashedAt = trashedAt
        )
    }

    companion object {
        fun fromTrashedBookmarkItem(item: TrashedBookmarkItem): TrashedBookmarkEntity {
            val b = item.bookmark
            return TrashedBookmarkEntity(
                id = b.id,
                title = b.title,
                url = b.url,
                description = b.description,
                category = b.tag.category,
                subcategory = b.tag.subcategory,
                entities = b.tag.entities,
                createdAt = b.createdAt,
                updatedAt = b.updatedAt,
                note = b.note,
                isNsfw = b.isNsfw,
                isAiSkipped = b.isAiSkipped,
                trashedAt = item.trashedAt
            )
        }
    }
}
