package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkDetailEditStateTest {

    private fun createBookmark(
        id: String = "bm1",
        note: String = "Original note",
        tag: Tag = Tag("Article", "Tech", listOf("AI")),
        isNsfw: Boolean = false
    ): BookmarkItem = BookmarkItem(
        id = id,
        title = "Title",
        url = "https://example.com",
        description = "Description",
        tag = tag,
        createdAt = 1000L,
        updatedAt = 1000L,
        note = note,
        isNsfw = isNsfw
    )

    @Test
    fun syncWithBookmark_updatesTagAndNsfw_whenNoUserChanges() {
        val initialBookmark = createBookmark()
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isNsfw)

        val updatedTag = Tag("Media", "Game", listOf("Nintendo"))
        val updatedBookmark = initialBookmark.copy(
            tag = updatedTag,
            isNsfw = true,
            updatedAt = 2000L
        )

        edit.syncWithBookmark(updatedBookmark)

        assertEquals("Tag should be updated to latest bookmark tag", updatedTag, edit.editedTag.value)
        assertEquals("Original tag should be updated to latest bookmark tag", updatedTag, edit.originalTag)
        assertEquals("NSFW should be updated to latest bookmark isNsfw", true, edit.editedIsNsfw.value)
        assertEquals("Original NSFW should be updated to latest bookmark isNsfw", true, edit.originalNsfw)
    }

    @Test
    fun syncWithBookmark_preservesUserEditedNote_whenNoteHasUnsavedChanges() {
        val initialBookmark = createBookmark(note = "Initial note")
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isNsfw)

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
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isNsfw)

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
        val edit = BookmarkDetailEditState(initialBookmark.note, initialBookmark.tag, initialBookmark.isNsfw)

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
