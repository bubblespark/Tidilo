package me.bubblespark.tidilo.data.db

import me.bubblespark.tidilo.data.DomainIconManager
import androidx.room.ColumnInfo
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
        Index(value = ["ogImageKey"]),
        Index(value = ["rssFeedId"]),
        Index(value = ["url"], unique = false),
        Index(value = ["createdAt"]),
        Index(value = ["category"]),
        Index(value = ["subcategory"]),
        Index(value = ["isNsfw"]),
        Index(value = ["isAiSkipped"]),
        Index(value = ["domain"])
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
    val ogImageUrl: String? = null,
    val domain: String? = DomainIconManager.extractDomain(url),
    @ColumnInfo(defaultValue = "''")
    val searchTitle: String = bookmarkSearchKey(title),
    @ColumnInfo(defaultValue = "''")
    val searchDescription: String = bookmarkSearchKey(description),
    @ColumnInfo(defaultValue = "''")
    val searchUrl: String = bookmarkSearchKey(url),
    val searchCategory: String? = category?.let(::bookmarkSearchKey),
    val searchSubcategory: String? = subcategory?.let(::bookmarkSearchKey),
    val rssFeedId: String? = null,
    val rssPublishedAt: Long? = null,
    val ogImageKey: String? = bookmarkOgImageKey(url, ogImageUrl)
) {
    /** Recomputes derived fields after copies, ensuring every DAO write stays consistent. */
    internal fun logicalTag(): Tag = logicalBookmarkTag(category, subcategory, entities, isAiSkipped)

    fun withSearchFields(): BookmarkEntity = copy(
        ogImageKey = bookmarkOgImageKey(url, ogImageUrl),
        domain = DomainIconManager.extractDomain(url),
        searchTitle = bookmarkSearchKey(title), searchDescription = bookmarkSearchKey(description),
        searchUrl = bookmarkSearchKey(url), searchCategory = logicalTag().category?.let(::bookmarkSearchKey),
        searchSubcategory = logicalTag().subcategory?.let(::bookmarkSearchKey)
    )

    fun toBookmarkItem(): BookmarkItem {
        return BookmarkItem(
            id = id,
            title = title,
            url = url,
            description = description,
            tag = logicalTag(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            note = note,
            isNsfw = isNsfw,
            isAiSkipped = isAiSkipped,
            rssFeedId = rssFeedId,
            rssPublishedAt = rssPublishedAt,
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
                rssFeedId = item.rssFeedId,
                rssPublishedAt = item.rssPublishedAt,
                ogImageUrl = item.ogImageUrl
            )
        }
    }
}

/** Mirrors the tags shown on cards while leaving the stored legacy values intact. */
internal fun logicalBookmarkTag(category: String?, subcategory: String?, entities: List<String>, isAiSkipped: Boolean): Tag =
    if (isAiSkipped && category == null && subcategory == null && entities.isEmpty()) Tag.createEmpty()
    else Tag.create(category, subcategory, entities)
