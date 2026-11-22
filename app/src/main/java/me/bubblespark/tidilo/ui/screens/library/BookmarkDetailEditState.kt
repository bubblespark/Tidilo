package me.bubblespark.tidilo.ui.screens.library

import android.os.Bundle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.entitySkeletonKey

/** A note autosave snapshot; [edits] identifies the edit it persists. */
internal data class NoteWrite(val text: String, val edits: Map<Unit, Long>)

/** Entities and replacements contain display spelling, never raw input. */
internal data class EntityTagDraft(val entities: List<String>, val replacements: Map<String, String> = emptyMap())
internal data class EntityTagConflict(val entered: String, val existing: String, val skeletonKey: String)
internal data class TagCommit(val tag: Tag, val isPrivate: Boolean, val replacements: Map<String, String>)

internal fun entityTagConflict(input: String, draft: EntityTagDraft, library: Map<String, String>): EntityTagConflict? {
    val entered = EntityTagPolicy.clean(input) ?: return null
    val key = entitySkeletonKey(entered)
    val existing = draft.replacements[key] ?: library[key]
        ?: draft.entities.firstOrNull { entitySkeletonKey(it) == key } ?: return null
    return if (entered == existing) null else EntityTagConflict(entered, existing, key)
}

internal fun applyEntityTagChoice(
    draft: EntityTagDraft,
    input: String,
    existing: String?,
    replaceAcrossLibrary: Boolean
): EntityTagDraft {
    val entered = EntityTagPolicy.clean(input) ?: return draft
    val key = entitySkeletonKey(entered)
    val containsKey = draft.entities.any { entitySkeletonKey(it) == key }
    if (!containsKey && draft.entities.size >= EntityTagPolicy.MAX_ENTITIES) return draft
    val value = if (replaceAcrossLibrary) entered else existing ?: entered
    val entities = if (containsKey) draft.entities.map { if (entitySkeletonKey(it) == key) value else it }
    else draft.entities + value
    return EntityTagDraft(entities, if (replaceAcrossLibrary) draft.replacements + (key to value) else draft.replacements)
}

private fun Char.isAsciiAlphanumeric(): Boolean = code < 128 && isLetterOrDigit()

/**
 * Appends a tapped suggestion [token] to [currentInput].
 * Inserts a single half-width space only when both the last character of [currentInput]
 * and the first character of [token] are ASCII alphanumeric characters.
 */
internal fun appendEntitySuggestion(currentInput: String, token: String): String {
    val cleanToken = token.trim()
    if (cleanToken.isEmpty()) return currentInput
    if (currentInput.isBlank()) return cleanToken
    val lastChar = currentInput.last()
    if (lastChar.isWhitespace()) return currentInput + cleanToken
    val needsSpace = lastChar.isAsciiAlphanumeric() && cleanToken.first().isAsciiAlphanumeric()
    return if (needsSpace) "$currentInput $cleanToken" else "$currentInput$cleanToken"
}

/**
 * Saves the autosaved note draft and the unconfirmed tag-dialog draft as one unit.
 * Tags and Private status are not drafted at screen level: the tag dialog's OK persists them directly.
 *
 * The note follows the stored value until the user types; from then on it is Edited and Room emissions
 * never replace it, until a write of the latest edit succeeds (see [EditedFields]).
 */
@Stable
internal class BookmarkDetailEditState(initialNote: String, noteEdited: Boolean = false) {
    var noteText by mutableStateOf(initialNote)
        private set
    private val noteEdits = EditedFields(if (noteEdited) listOf(Unit) else emptyList())
    val isNoteEdited: Boolean get() = noteEdits.isEdited(Unit)
    var showTagEditDialog by mutableStateOf(false)
        private set
    var showAddEntityDialog by mutableStateOf(false)
        private set
    var showEntityBottomSheet by mutableStateOf(false)
        private set
    var draftCategory by mutableStateOf<String?>(null)
        private set
    var draftSubcategory by mutableStateOf<String?>(null)
        private set
    var draftEntities by mutableStateOf<List<String>>(emptyList())
        private set
    var draftIsPrivate by mutableStateOf(false)
        private set
    var draftReplacements by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var tagSaveFailed by mutableStateOf(false)
        private set
    var entityInput by mutableStateOf("")
        private set
    private var conflictInput by mutableStateOf<String?>(null)
    private var conflictExisting by mutableStateOf<String?>(null)

    val pendingConflict: EntityTagConflict?
        get() {
            val raw = conflictInput ?: return null
            val existing = conflictExisting ?: return null
            val entered = EntityTagPolicy.clean(raw) ?: return null
            return EntityTagConflict(entered = entered, existing = existing, skeletonKey = entitySkeletonKey(entered))
        }

    internal data class SavedSnapshot(
        val note: String,
        val noteEdited: Boolean,
        val showTagEditDialog: Boolean,
        val showAddEntityDialog: Boolean,
        val showEntityBottomSheet: Boolean,
        val draftTag: Tag,
        val draftIsPrivate: Boolean,
        val draftReplacements: Map<String, String>,
        val tagSaveFailed: Boolean,
        val entityInput: String,
        val conflictInput: String?,
        val conflictExisting: String?
    )

    /** Records a user edit and returns the snapshot to autosave. */
    fun onNoteTyped(text: String): NoteWrite {
        noteText = text
        noteEdits.markEdited(listOf(Unit))
        return NoteWrite(text, noteEdits.snapshot())
    }

    /** The write still owed for an Edited note, or null while the note follows the stored value. */
    fun pendingNoteWrite(): NoteWrite? = if (noteEdits.isEmpty) null else NoteWrite(noteText, noteEdits.snapshot())

    /** Shows a stored note only while the user has no unsaved edit. */
    fun onNoteStored(note: String) {
        if (!isNoteEdited) noteText = note
    }

    /** After a successful write the note follows Room again unless the user typed since. */
    fun onNoteWritten(write: NoteWrite) {
        noteEdits.onWritten(write.edits)
    }

    /** Starts a tag-dialog draft from the stored classification. */
    fun openTagEditor(tag: Tag, isPrivate: Boolean) {
        draftCategory = tag.category
        draftSubcategory = tag.subcategory
        draftEntities = tag.entities
        draftIsPrivate = isPrivate
        draftReplacements = emptyMap()
        tagSaveFailed = false
        showTagEditDialog = true
    }

    fun updateDraftCategory(category: String?) {
        draftCategory = category
    }

    fun updateDraftSubcategory(subcategory: String?) {
        draftSubcategory = subcategory
    }

    fun updateDraftPrivate(isPrivate: Boolean) {
        draftIsPrivate = isPrivate
    }

    /** Clears any prior save error and snapshots the immutable [TagCommit] to persist. */
    fun prepareTagCommit(): TagCommit {
        tagSaveFailed = false
        return TagCommit(
            tag = Tag.fromDisplayValues(draftCategory, draftSubcategory, draftEntities),
            isPrivate = draftIsPrivate,
            replacements = draftReplacements
        )
    }

    fun onTagSaveFailed() {
        tagSaveFailed = true
    }

    /** Cancels the tag-dialog draft unless a commit is currently in flight. */
    fun dismissTagEditor(isSaving: Boolean = false) {
        if (!isSaving) closeTagEditor()
    }

    /** Discards the whole tag-dialog draft, including nested picker selections and staged replacements. */
    fun closeTagEditor() {
        showTagEditDialog = false
        showAddEntityDialog = false
        showEntityBottomSheet = false
        draftReplacements = emptyMap()
        tagSaveFailed = false
        entityInput = ""
        conflictInput = null
        conflictExisting = null
    }

    fun openAddEntityDialog() {
        entityInput = ""
        showAddEntityDialog = true
    }

    fun dismissAddEntityDialog() {
        showAddEntityDialog = false
    }

    fun updateEntityInput(text: String) {
        entityInput = text
    }

    fun appendEntitySuggestionToInput(token: String) {
        entityInput = appendEntitySuggestion(entityInput, token)
    }

    /**
     * Submits [rawInput] from the Add Entity dialog against [librarySpellings], either staging a
     * pending spelling conflict or adding the normalized entity to [draftEntities].
     */
    fun submitEntityInput(librarySpellings: Map<String, String> = emptyMap(), rawInput: String = entityInput) {
        showAddEntityDialog = false
        val draft = EntityTagDraft(draftEntities, draftReplacements)
        val conflict = entityTagConflict(rawInput, draft, librarySpellings)
        if (conflict != null) {
            conflictInput = rawInput
            conflictExisting = conflict.existing
        } else {
            val next = applyEntityTagChoice(draft, rawInput, null, false)
            draftEntities = next.entities
        }
    }

    fun resolveEntityConflict(replaceAcrossLibrary: Boolean) {
        val raw = conflictInput ?: return
        val existing = conflictExisting
        val next = applyEntityTagChoice(
            draft = EntityTagDraft(draftEntities, draftReplacements),
            input = raw,
            existing = existing,
            replaceAcrossLibrary = replaceAcrossLibrary
        )
        draftEntities = next.entities
        draftReplacements = next.replacements
        conflictInput = null
        conflictExisting = null
    }

    fun dismissEntityConflict() {
        conflictInput = null
        conflictExisting = null
    }

    fun openEntityPicker() {
        showEntityBottomSheet = true
    }

    fun dismissEntityPicker() {
        showEntityBottomSheet = false
    }

    /** Returns picker entities overlaying [draftReplacements] onto [librarySpellings], deduplicated by skeleton key. */
    fun pickerEntities(librarySpellings: Map<String, String>): List<String> {
        val allLibraryEntities = librarySpellings.map { (key, value) -> draftReplacements[key] ?: value }
        return (draftEntities + allLibraryEntities).distinctBy(::entitySkeletonKey)
    }

    fun togglePickerEntity(entity: String) {
        draftEntities = EntityTagPolicy.toggleDisplayEntity(draftEntities, entity)
    }

    fun removePickerEntity(entity: String) {
        val targetKey = entitySkeletonKey(entity)
        draftEntities = draftEntities.filterNot { entitySkeletonKey(it) == targetKey }
    }

    internal fun toSavedSnapshot(): SavedSnapshot = SavedSnapshot(
        note = noteText,
        noteEdited = isNoteEdited,
        showTagEditDialog = showTagEditDialog,
        showAddEntityDialog = showAddEntityDialog,
        showEntityBottomSheet = showEntityBottomSheet,
        draftTag = Tag(draftCategory, draftSubcategory, draftEntities),
        draftIsPrivate = draftIsPrivate,
        draftReplacements = draftReplacements,
        tagSaveFailed = tagSaveFailed,
        entityInput = entityInput,
        conflictInput = conflictInput,
        conflictExisting = conflictExisting
    )

    companion object {
        internal fun fromSavedSnapshot(saved: SavedSnapshot): BookmarkDetailEditState =
            BookmarkDetailEditState(saved.note, saved.noteEdited).apply {
                showTagEditDialog = saved.showTagEditDialog
                showAddEntityDialog = saved.showAddEntityDialog
                showEntityBottomSheet = saved.showEntityBottomSheet
                draftCategory = saved.draftTag.category
                draftSubcategory = saved.draftTag.subcategory
                draftEntities = saved.draftTag.entities
                draftIsPrivate = saved.draftIsPrivate
                draftReplacements = saved.draftReplacements
                tagSaveFailed = saved.tagSaveFailed
                entityInput = saved.entityInput
                conflictInput = saved.conflictInput
                conflictExisting = saved.conflictExisting
            }

        val Saver = Saver<BookmarkDetailEditState, Bundle>(
            save = { state ->
                val snapshot = state.toSavedSnapshot()
                Bundle().apply {
                    putString("note", snapshot.note)
                    putBoolean("noteEdited", snapshot.noteEdited)
                    putBoolean("editor", snapshot.showTagEditDialog)
                    putBoolean("add", snapshot.showAddEntityDialog)
                    putBoolean("picker", snapshot.showEntityBottomSheet)
                    putTag("draft", snapshot.draftTag)
                    putBoolean("draftPrivate", snapshot.draftIsPrivate)
                    putMap("draftPending", snapshot.draftReplacements)
                    putBoolean("tagSaveFailed", snapshot.tagSaveFailed)
                    putString("input", snapshot.entityInput)
                    putString("conflictInput", snapshot.conflictInput)
                    putString("conflictExisting", snapshot.conflictExisting)
                }
            },
            restore = { saved ->
                fromSavedSnapshot(
                    SavedSnapshot(
                        note = saved.getString("note").orEmpty(),
                        noteEdited = saved.getBoolean("noteEdited"),
                        showTagEditDialog = saved.getBoolean("editor"),
                        showAddEntityDialog = saved.getBoolean("add"),
                        showEntityBottomSheet = saved.getBoolean("picker"),
                        draftTag = saved.tag("draft"),
                        draftIsPrivate = saved.getBoolean("draftPrivate"),
                        draftReplacements = saved.map("draftPending"),
                        tagSaveFailed = saved.getBoolean("tagSaveFailed"),
                        entityInput = saved.getString("input").orEmpty(),
                        conflictInput = saved.getString("conflictInput"),
                        conflictExisting = saved.getString("conflictExisting")
                    )
                )
            }
        )

        private fun Bundle.putTag(key: String, tag: Tag) {
            putString("$key.category", tag.category)
            putString("$key.subcategory", tag.subcategory)
            putStringArrayList("$key.entities", ArrayList(tag.entities))
        }

        private fun Bundle.tag(key: String) = Tag(
            getString("$key.category"),
            getString("$key.subcategory"),
            getStringArrayList("$key.entities").orEmpty()
        )

        private fun Bundle.putMap(key: String, map: Map<String, String>) {
            putStringArrayList("$key.keys", ArrayList(map.keys))
            putStringArrayList("$key.values", ArrayList(map.values))
        }

        private fun Bundle.map(key: String) =
            getStringArrayList("$key.keys").orEmpty().zip(getStringArrayList("$key.values").orEmpty()).toMap()
    }
}
