package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.OgImageManager

import androidx.compose.runtime.Immutable

/**
 * Tag classification: 3-tier structure of category, subcategory, and entities.
 * category / subcategory default to "None" when unset.
 */
@Immutable
data class Tag(
    val category: String? = DEFAULT_TAG,
    val subcategory: String? = DEFAULT_TAG,
    val entities: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_TAG = "None"
        const val MAX_ENTITIES = EntityTagPolicy.MAX_ENTITIES

        fun create(
            category: String?,
            subcategory: String?,
            entities: List<String> = emptyList()
        ): Tag {
            val cat = category?.trim()?.removePrefix("#")?.trim()?.ifBlank { DEFAULT_TAG } ?: DEFAULT_TAG
            val sub = subcategory?.trim()?.removePrefix("#")?.trim()?.ifBlank { DEFAULT_TAG } ?: DEFAULT_TAG
            val cleanEntities = entities.mapNotNull { EntityTagPolicy.clean(it) }
            return Tag(category = cat, subcategory = sub, entities = cleanEntities)
        }

        /** Builds a tag from stored or previously cleaned display values without cleaning twice. */
        fun fromDisplayValues(category: String?, subcategory: String?, entities: List<String> = emptyList()): Tag =
            create(category, subcategory).copy(entities = entities)

        fun createEmpty(): Tag = Tag(category = null, subcategory = null, entities = emptyList())

        /**
         * Adds an entity tag to the current list if not already present and limit has not been reached.
         */
        fun addEntity(current: List<String>, newEntity: String): List<String> =
            EntityTagPolicy.addEntity(current, newEntity)

        /**
         * Toggles an entity tag in the current list: removes if present, adds if absent and below MAX_ENTITIES.
         */
        fun toggleEntity(current: List<String>, entity: String): List<String> =
            EntityTagPolicy.toggleEntity(current, entity)
    }
}

/**
 * Bookmark item representation.
 * Supports bilingual (Japanese and English) titles, descriptions, and tags.
 */
@Immutable
data class BookmarkItem(
    val id: String,
    val title: String,
    val url: String,
    val description: String,
    val tag: Tag,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val note: String = "",
    val isNsfw: Boolean = false,
    val isAiSkipped: Boolean = false,
    val ogImageUrl: String? = null,
    val rssFeedId: String? = null,
    val rssPublishedAt: Long? = null,
    val domain: String? = DomainIconManager.extractDomain(url),
    val ogImageFilename: String? = ogImageUrl?.takeIf { it.isNotBlank() }?.let { ogUrl ->
        val resolved = OgImageManager.resolveImageUrl(url, ogUrl)
        OgImageManager.computeImageKey(resolved)
    }
)

/**
 * Trashed bookmark item held in the trash container.
 * Retains deletion timestamp (trashedAt) and is purged after 30 days.
 */
@Immutable
data class TrashedBookmarkItem(
    val bookmark: BookmarkItem,
    val trashedAt: Long = System.currentTimeMillis()
)

