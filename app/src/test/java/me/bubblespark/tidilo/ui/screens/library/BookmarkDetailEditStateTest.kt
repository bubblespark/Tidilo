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
    fun noteFollowsStoredValueUntilTheUserTypes() {
        val edit = BookmarkDetailEditState("Initial")
        edit.onNoteStored("External")
        assertEquals("External", edit.noteText.value)
        assertEquals(null, edit.pendingNoteWrite())
    }

    @Test
    fun storedValueNeverReplacesAnUnsavedEdit() {
        val edit = BookmarkDetailEditState("A")
        edit.onNoteTyped("Draft")
        edit.onNoteStored("External")
        assertEquals("Draft", edit.noteText.value)
    }

    @Test
    fun revertDuringAnInFlightWriteStaysOwedAndVisible() {
        // A -> B (write starts) -> back to A before B completes.
        val edit = BookmarkDetailEditState("A")
        val writeB = edit.onNoteTyped("B")
        val writeA = edit.onNoteTyped("A")
        edit.onNoteWritten(writeB)
        edit.onNoteStored("B")
        assertEquals("The revert is still shown", "A", edit.noteText.value)
        assertEquals("The revert is still owed", writeA, edit.pendingNoteWrite())
        edit.onNoteWritten(writeA)
        assertEquals(null, edit.pendingNoteWrite())
        edit.onNoteStored("A")
        assertEquals("A", edit.noteText.value)
    }

    @Test
    fun anOlderScreensWriteCannotReplaceARevertThatIsStillOwed() {
        // The recreated screen read A, the old screen is still writing B, the user edits then reverts to A.
        val edit = BookmarkDetailEditState("A")
        edit.onNoteTyped("C")
        edit.onNoteTyped("A")
        edit.onNoteStored("B")
        assertEquals("A", edit.noteText.value)
        assertEquals("A", edit.pendingNoteWrite()?.text)
    }

    @Test
    fun failedWriteKeepsTheDraftVisibleAndOwed() {
        val edit = BookmarkDetailEditState("A")
        val write = edit.onNoteTyped("Reverted")
        // The write failed (onNoteWritten not called); a late emission arrives.
        edit.onNoteStored("B")
        assertEquals("Reverted", edit.noteText.value)
        assertEquals(write, edit.pendingNoteWrite())
    }

    @Test
    fun restoredEditedNoteIsStillOwed() {
        val edit = BookmarkDetailEditState("Unsaved", noteEdited = true)
        edit.onNoteStored("Stored")
        assertEquals("Unsaved", edit.noteText.value)
        assertEquals("Unsaved", edit.pendingNoteWrite()?.text)
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
}
