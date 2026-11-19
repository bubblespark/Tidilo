package me.bubblespark.tidilo.ui.screens.library

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver

/**
 * Saves the autosaved note draft and the unconfirmed tag-dialog draft as one unit.
 * Tags and Private status are not drafted at screen level: the tag dialog's OK persists them directly.
 */
@Stable
internal class BookmarkDetailEditState(initialNote: String) {
    var originalNote by mutableStateOf(initialNote)
    val noteText = mutableStateOf(originalNote)
    val showTagEditDialog = mutableStateOf(false)
    val showAddEntityDialog = mutableStateOf(false)
    val showEntityBottomSheet = mutableStateOf(false)
    val draftCategory = mutableStateOf<String?>(null)
    val draftSubcategory = mutableStateOf<String?>(null)
    val draftEntities = mutableStateOf<List<String>>(emptyList())
    val draftIsPrivate = mutableStateOf(false)
    val draftReplacements = mutableStateOf<Map<String, String>>(emptyMap())
    val tagSaveFailed = mutableStateOf(false)
    val entityInput = mutableStateOf("")
    val conflictInput = mutableStateOf<String?>(null)
    val conflictExisting = mutableStateOf<String?>(null)

    /**
     * Synchronizes remote bookmark updates while preserving a note draft that is not yet stored.
     * With [keepDraft] (a note write is pending or running) the visible note is never replaced, even when
     * it equals the old baseline: it may be a revert that still has to be written.
     */
    fun syncWithBookmark(bookmark: BookmarkItem, keepDraft: Boolean = false) {
        if (!keepDraft && noteText.value == originalNote) {
            noteText.value = bookmark.note
        }
        originalNote = bookmark.note
    }

    /** Starts a tag-dialog draft from the stored classification. */
    fun openTagEditor(tag: Tag, isPrivate: Boolean) {
        draftCategory.value = tag.category
        draftSubcategory.value = tag.subcategory
        draftEntities.value = tag.entities
        draftIsPrivate.value = isPrivate
        draftReplacements.value = emptyMap()
        tagSaveFailed.value = false
        showTagEditDialog.value = true
    }

    /** Discards the whole tag-dialog draft, including nested picker selections and staged replacements. */
    fun closeTagEditor() {
        showTagEditDialog.value = false
        showAddEntityDialog.value = false
        showEntityBottomSheet.value = false
        draftReplacements.value = emptyMap()
        tagSaveFailed.value = false
        entityInput.value = ""
        conflictInput.value = null
        conflictExisting.value = null
    }

    companion object {
        val Saver = Saver<BookmarkDetailEditState, Bundle>(
            save = { state -> Bundle().apply {
                putString("originalNote", state.originalNote)
                putString("note", state.noteText.value)
                putBoolean("editor", state.showTagEditDialog.value)
                putBoolean("add", state.showAddEntityDialog.value)
                putBoolean("picker", state.showEntityBottomSheet.value)
                putTag("draft", Tag(state.draftCategory.value, state.draftSubcategory.value, state.draftEntities.value))
                putBoolean("draftPrivate", state.draftIsPrivate.value)
                putMap("draftPending", state.draftReplacements.value)
                putBoolean("tagSaveFailed", state.tagSaveFailed.value)
                putString("input", state.entityInput.value)
                putString("conflictInput", state.conflictInput.value)
                putString("conflictExisting", state.conflictExisting.value)
            } },
            restore = { saved -> BookmarkDetailEditState(saved.getString("originalNote").orEmpty()).apply {
                noteText.value = saved.getString("note").orEmpty()
                showTagEditDialog.value = saved.getBoolean("editor")
                showAddEntityDialog.value = saved.getBoolean("add")
                showEntityBottomSheet.value = saved.getBoolean("picker")
                val draft = saved.tag("draft")
                draftCategory.value = draft.category
                draftSubcategory.value = draft.subcategory
                draftEntities.value = draft.entities
                draftIsPrivate.value = saved.getBoolean("draftPrivate")
                draftReplacements.value = saved.map("draftPending")
                tagSaveFailed.value = saved.getBoolean("tagSaveFailed")
                entityInput.value = saved.getString("input").orEmpty()
                conflictInput.value = saved.getString("conflictInput")
                conflictExisting.value = saved.getString("conflictExisting")
            } }
        )
        private fun Bundle.putTag(key: String, tag: Tag) {
            putString("$key.category", tag.category)
            putString("$key.subcategory", tag.subcategory)
            putStringArrayList("$key.entities", ArrayList(tag.entities))
        }
        private fun Bundle.tag(key: String) = Tag(getString("$key.category"), getString("$key.subcategory"), getStringArrayList("$key.entities").orEmpty())
        private fun Bundle.putMap(key: String, map: Map<String, String>) {
            putStringArrayList("$key.keys", ArrayList(map.keys))
            putStringArrayList("$key.values", ArrayList(map.values))
        }
        private fun Bundle.map(key: String) = getStringArrayList("$key.keys").orEmpty().zip(getStringArrayList("$key.values").orEmpty()).toMap()
    }
}
