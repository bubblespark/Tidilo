package me.bubblespark.tidilo.ui.screens.library

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
        const val MAX_ENTITIES = 10

        fun create(
            category: String?,
            subcategory: String?,
            entities: List<String> = emptyList()
        ): Tag {
            val cat = category?.trim()?.removePrefix("#")?.trim()?.ifBlank { DEFAULT_TAG } ?: DEFAULT_TAG
            val sub = subcategory?.trim()?.removePrefix("#")?.trim()?.ifBlank { DEFAULT_TAG } ?: DEFAULT_TAG
            val cleanEntities = entities
                .map { it.trim().removePrefix("#").trim() }
                .filter { it.isNotBlank() }
            return Tag(category = cat, subcategory = sub, entities = cleanEntities)
        }

        fun createEmpty(): Tag = Tag(category = null, subcategory = null, entities = emptyList())

        /**
         * Adds an entity tag to the current list if not already present and limit has not been reached.
         */
        fun addEntity(current: List<String>, newEntity: String): List<String> {
            val clean = newEntity.trim().removePrefix("#").trim()
            if (clean.isBlank()) return current
            if (current.size >= MAX_ENTITIES) return current
            if (current.any { it.equals(clean, ignoreCase = true) }) return current
            return current + clean
        }

        /**
         * Toggles an entity tag in the current list: removes if present, adds if absent and below MAX_ENTITIES.
         */
        fun toggleEntity(current: List<String>, entity: String): List<String> {
            val clean = entity.trim().removePrefix("#").trim()
            if (clean.isBlank()) return current
            return if (current.any { it.equals(clean, ignoreCase = true) }) {
                current.filterNot { it.equals(clean, ignoreCase = true) }
            } else if (current.size < MAX_ENTITIES) {
                current + clean
            } else {
                current
            }
        }
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
    val isAiSkipped: Boolean = false
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

