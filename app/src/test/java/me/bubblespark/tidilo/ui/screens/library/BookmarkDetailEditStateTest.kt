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
    fun syncWithBookmark_updatesTagAndPrivate_whenNoUserChanges() {
        val initialBookmark = createBookmark()
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isPrivate)

        val updatedTag = Tag("Media", "Game", listOf("Nintendo"))
        val updatedBookmark = initialBookmark.copy(
            tag = updatedTag,
            isPrivate = true,
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("Tag should be updated to latest bookmark tag", updatedTag, edit.editedTag.value)
        assertEquals("Original tag should be updated to latest bookmark tag", updatedTag, edit.originalTag)
        assertEquals("Private should be updated to latest bookmark isPrivate", true, edit.editedIsPrivate.value)
        assertEquals("Original Private should be updated to latest bookmark isPrivate", true, edit.originalPrivate)
    }

    @Test
    fun syncWithBookmark_preservesUserEditedNote_whenNoteHasUnsavedChanges() {
        val initialBookmark = createBookmark(note = "Initial note")
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isPrivate)

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
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isPrivate)

        val updatedBookmark = initialBookmark.copy(
            note = "External updated note",
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("Unedited note should sync with latest bookmark", "External updated note", edit.noteText.value)
        assertEquals("Original note baseline should track the new remote note", "External updated note", edit.originalNote)
    }

    @Test
    fun syncWithBookmark_preservesUserEditedTag_whenTagHasUnsavedChanges() {
        val initialBookmark = createBookmark()
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isPrivate)

        // User manually customized tag
        val userEditedTag = Tag("Tool", "Tech", listOf("Custom"))
        edit.editedTag.value = userEditedTag

        val updatedBookmark = initialBookmark.copy(
            tag = Tag("Media", "Game", listOf("Nintendo")),
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("User's manually edited tag must not be overwritten", userEditedTag, edit.editedTag.value)
        assertEquals("Original tag baseline should track the new remote tag", updatedBookmark.tag, edit.originalTag)
    }
}
