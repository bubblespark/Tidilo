package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag

/**
 * Room entity representing a bookmark in the local SQLite database.
 */
@Entity(
    tableName = "bookmarks",
    indices = [
        Index(value = ["url"], unique = false),
        Index(value = ["createdAt"]),
        Index(value = ["category"]),
        Index(value = ["subcategory"]),
        Index(value = ["isNsfw"]),
        Index(value = ["isAiSkipped"])
    ]
)
data class BookmarkEntity(
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
    val note: String = "",
    val isNsfw: Boolean = false,
    val isAiSkipped: Boolean = false,
    val ogImageUrl: String? = null
) {
    fun toBookmarkItem(): BookmarkItem {
        return BookmarkItem(
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
            isAiSkipped = isAiSkipped,
            ogImageUrl = ogImageUrl
        )
    }

    companion object {
        fun fromBookmarkItem(item: BookmarkItem): BookmarkEntity {
            return BookmarkEntity(
                id = item.id,
                title = item.title,
                url = item.url,
                description = item.description,
                category = item.tag.category,
                subcategory = item.tag.subcategory,
                entities = item.tag.entities,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                note = item.note,
                isNsfw = item.isNsfw,
                isAiSkipped = item.isAiSkipped,
                ogImageUrl = item.ogImageUrl
            )
        }
    }
}
