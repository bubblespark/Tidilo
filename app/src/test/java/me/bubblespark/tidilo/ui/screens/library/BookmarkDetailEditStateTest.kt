package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkDetailEditStateTest {

    private fun createBookmark(
        id: String = "bm1",
        note: String = "Original note",
        tag: Tag = Tag("Article", "Tech", listOf("AI")),
        isPrivate: Boolean = false
    ): BookmarkItem = BookmarkItem(
        id = id,
        title = "Title",
        url = "https://example.com",
        description = "Description",
        tag = tag,
        createdAt = 1000L,
        updatedAt = 1000L,
        note = note,
        isPrivate = isPrivate
    )

    @Test
    fun syncWithBookmark_preservesUserEditedNote_whenNoteHasUnsavedChanges() {
        val initialBookmark = createBookmark(note = "Initial note")
        val edit = BookmarkDetailEditState(initialBookmark.note)

        // User typed a draft note
        edit.noteText.value = "User draft note in progress"

        val updatedBookmark = initialBookmark.copy(
            note = "External updated note",
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("User's in-progress note draft must not be overwritten", "User draft note in progress", edit.noteText.value)
        assertEquals("Original note baseline should track the new remote note", "External updated note", edit.originalNote)
    }

    @Test
    fun syncWithBookmark_updatesNote_whenUserHasNotEditedNote() {
        val initialBookmark = createBookmark(note = "Initial note")
        val edit = BookmarkDetailEditState(initialBookmark.note)

        val updatedBookmark = initialBookmark.copy(
            note = "External updated note",
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("Unedited note should sync with latest bookmark", "External updated note", edit.noteText.value)
        assertEquals("Original note baseline should track the new remote note", "External updated note", edit.originalNote)
    }

    @Test
    fun openTagEditor_startsDraftFromStoredValues() {
        val bookmark = createBookmark(tag = Tag("Media", "Game", listOf("Nintendo")), isPrivate = true)
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.draftReplacements.value = mapOf("stale" to "Stale")
        edit.tagSaveFailed.value = true

        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)

        assertEquals("Media", edit.draftCategory.value)
        assertEquals("Game", edit.draftSubcategory.value)
        assertEquals(listOf("Nintendo"), edit.draftEntities.value)
        assertEquals(true, edit.draftIsPrivate.value)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements.value)
        assertEquals(false, edit.tagSaveFailed.value)
        assertEquals(true, edit.showTagEditDialog.value)
    }

    @Test
    fun closeTagEditor_discardsNestedPickerSelectionsAndStagedReplacements() {
        val bookmark = createBookmark()
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        edit.noteText.value = "Independent note draft"
        // Nested picker OK only edits the parent draft.
        edit.showEntityBottomSheet.value = true
        edit.draftEntities.value = listOf("AI", "Picked")
        edit.draftReplacements.value = mapOf("ai" to "A.I.")
        edit.showEntityBottomSheet.value = false

        edit.closeTagEditor()

        assertEquals(false, edit.showTagEditDialog.value)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements.value)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        assertEquals("Reopening starts again from the stored tag", listOf("AI"), edit.draftEntities.value)
        assertEquals("Cancelling the tag dialog never touches the note", "Independent note draft", edit.noteText.value)
    }

    @Test
    fun syncWithBookmark_keepsAPendingRevertEvenWhenItEqualsTheOldBaseline() {
        val bookmark = createBookmark(note = "A")
        val edit = BookmarkDetailEditState(bookmark.note)
        // The user reverted to A while another screen's write of B was still running; A is pending.
        edit.noteText.value = "A"

        edit.syncWithBookmark(bookmark.copy(note = "B", updatedAt = 2000L), keepDraft = true)

        assertEquals("A pending revert must not be replaced by the older write", "A", edit.noteText.value)
        assertEquals("The baseline still follows Room so A is written", "B", edit.originalNote)
    }
}
