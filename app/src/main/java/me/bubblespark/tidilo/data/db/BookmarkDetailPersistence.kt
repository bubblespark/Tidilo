package me.bubblespark.tidilo.data.db

import androidx.room.withTransaction
import me.bubblespark.tidilo.ui.screens.library.Tag

/** Applies explicit library spellings before re-resolving a possibly stale display-value draft. */
internal suspend fun AppDatabase.updateBookmarkDetails(
    id: String, note: String, tag: Tag, isNsfw: Boolean,
    replacements: Map<String, String>, updatedAt: Long
) = withTransaction {
    val dao = bookmarkDao()
    requireNotNull(dao.getBookmarkById(id)) { "Bookmark no longer exists" }
    dao.replaceEntitySpellings(replacements, updatedAt)
    dao.updateNoteAndTag(id, note, tag.category, tag.subcategory, tag.entities, isNsfw, updatedAt)
}
