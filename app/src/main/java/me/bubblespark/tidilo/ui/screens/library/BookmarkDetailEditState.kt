package me.bubblespark.tidilo.ui.screens.library

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/** A note autosave snapshot; [edits] identifies the edit it persists. */
internal data class NoteWrite(val text: String, val edits: Map<Unit, Long>)

/**
 * Saves the autosaved note draft and the unconfirmed tag-dialog draft as one unit.
 * Tags and Private status are not drafted at screen level: the tag dialog's OK persists them directly.
 *
 * The note follows the stored value until the user types; from then on it is Edited and Room emissions
 * never replace it, until a write of the latest edit succeeds (see [EditedFields]).
 */
@Stable
internal class BookmarkDetailEditState(initialNote: String, noteEdited: Boolean = false) {
    val noteText = mutableStateOf(initialNote)
    private val noteEdits = EditedFields(if (noteEdited) listOf(Unit) else emptyList())
    val isNoteEdited: Boolean get() = noteEdits.isEdited(Unit)
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

    /** Records a user edit and returns the snapshot to autosave. */
    fun onNoteTyped(text: String): NoteWrite {
        noteText.value = text
        noteEdits.markEdited(listOf(Unit))
        return NoteWrite(text, noteEdits.snapshot())
    }

    /** The write still owed for an Edited note, or null while the note follows the stored value. */
    fun pendingNoteWrite(): NoteWrite? = if (noteEdits.isEmpty) null else NoteWrite(noteText.value, noteEdits.snapshot())

    /** Shows a stored note only while the user has no unsaved edit. */
    fun onNoteStored(note: String) {
        if (!isNoteEdited) noteText.value = note
    }

    /** After a successful write the note follows Room again unless the user typed since. */
    fun onNoteWritten(write: NoteWrite) {
        noteEdits.onWritten(write.edits)
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
                putString("note", state.noteText.value)
                putBoolean("noteEdited", state.isNoteEdited)
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
            restore = { saved -> BookmarkDetailEditState(saved.getString("note").orEmpty(), saved.getBoolean("noteEdited")).apply {
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
