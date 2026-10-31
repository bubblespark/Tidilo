package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem

/**
 * Room entity representing a trashed bookmark held for retention before permanent purge.
 */
@Entity(
    tableName = "trashed_bookmarks",
    indices = [
        Index(value = ["ogImageKey"]),
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
    @ColumnInfo(defaultValue = "0") val isLiked: Boolean = false,
    @ColumnInfo(defaultValue = "0") val browserOpenCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val detailViewCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val summaryPeekCount: Int = 0,
    val lastInteractedAt: Long? = null,
    val ogImageUrl: String? = null,
    val trashedAt: Long,
    val rssFeedId: String? = null,
    val rssPublishedAt: Long? = null,
    val ogImageKey: String? = bookmarkOgImageKey(url, ogImageUrl)
) {
    fun toTrashedBookmarkItem(): TrashedBookmarkItem {
        val bookmark = BookmarkItem(
            id = id,
            title = title,
            url = url,
            description = description,
            tag = logicalBookmarkTag(category, subcategory, entities, isAiSkipped),
            createdAt = createdAt,
            updatedAt = updatedAt,
            note = note,
            isNsfw = isNsfw,
            isAiSkipped = isAiSkipped,
            isLiked = isLiked,
            browserOpenCount = browserOpenCount,
            detailViewCount = detailViewCount,
            summaryPeekCount = summaryPeekCount,
            lastInteractedAt = lastInteractedAt,
            rssFeedId = rssFeedId,
            rssPublishedAt = rssPublishedAt,
            ogImageUrl = ogImageUrl
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
                isLiked = b.isLiked,
                browserOpenCount = b.browserOpenCount,
                detailViewCount = b.detailViewCount,
                summaryPeekCount = b.summaryPeekCount,
                lastInteractedAt = b.lastInteractedAt,
                rssFeedId = b.rssFeedId,
                rssPublishedAt = b.rssPublishedAt,
                ogImageUrl = b.ogImageUrl,
                trashedAt = item.trashedAt
            )
        }
    }
}
