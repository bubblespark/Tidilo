package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("External", edit.noteText)
        assertNull(edit.pendingNoteWrite())
    }

    @Test
    fun storedValueNeverReplacesAnUnsavedEdit() {
        val edit = BookmarkDetailEditState("A")
        edit.onNoteTyped("Draft")
        edit.onNoteStored("External")
        assertEquals("Draft", edit.noteText)
    }

    @Test
    fun revertDuringAnInFlightWriteStaysOwedAndVisible() {
        // A -> B (write starts) -> back to A before B completes.
        val edit = BookmarkDetailEditState("A")
        val writeB = edit.onNoteTyped("B")
        val writeA = edit.onNoteTyped("A")
        edit.onNoteWritten(writeB)
        edit.onNoteStored("B")
        assertEquals("The revert is still shown", "A", edit.noteText)
        assertEquals("The revert is still owed", writeA, edit.pendingNoteWrite())
        edit.onNoteWritten(writeA)
        assertNull(edit.pendingNoteWrite())
        edit.onNoteStored("A")
        assertEquals("A", edit.noteText)
    }

    @Test
    fun anOlderScreensWriteCannotReplaceARevertThatIsStillOwed() {
        // The recreated screen read A, the old screen is still writing B, the user edits then reverts to A.
        val edit = BookmarkDetailEditState("A")
        edit.onNoteTyped("C")
        edit.onNoteTyped("A")
        edit.onNoteStored("B")
        assertEquals("A", edit.noteText)
        assertEquals("A", edit.pendingNoteWrite()?.text)
    }

    @Test
    fun failedWriteKeepsTheDraftVisibleAndOwed() {
        val edit = BookmarkDetailEditState("A")
        val write = edit.onNoteTyped("Reverted")
        // The write failed (onNoteWritten not called); a late emission arrives.
        edit.onNoteStored("B")
        assertEquals("Reverted", edit.noteText)
        assertEquals(write, edit.pendingNoteWrite())
    }

    @Test
    fun restoredEditedNoteIsStillOwed() {
        val edit = BookmarkDetailEditState("Unsaved", noteEdited = true)
        edit.onNoteStored("Stored")
        assertEquals("Unsaved", edit.noteText)
        assertEquals("Unsaved", edit.pendingNoteWrite()?.text)
    }

    @Test
    fun openTagEditor_startsDraftFromStoredValues() {
        val bookmark = createBookmark(tag = Tag("Media", "Game", listOf("Nintendo")), isPrivate = true)
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.openTagEditor(Tag("Article", "Tech", listOf("OpenAI")), isPrivate = false)
        edit.submitEntityInput(mapOf("openai" to "OpenAI"), "open-ai")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        edit.onTagSaveFailed()

        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)

        assertEquals("Media", edit.draftCategory)
        assertEquals("Game", edit.draftSubcategory)
        assertEquals(listOf("Nintendo"), edit.draftEntities)
        assertTrue(edit.draftIsPrivate)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements)
        assertFalse(edit.tagSaveFailed)
        assertTrue(edit.showTagEditDialog)
    }

    @Test
    fun closeTagEditor_discardsNestedPickerSelectionsAndStagedReplacements() {
        val bookmark = createBookmark()
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        edit.onNoteTyped("Independent note draft")
        // Nested picker and conflict edits only mutate the parent dialog draft.
        edit.openEntityPicker()
        edit.togglePickerEntity("Picked")
        edit.dismissEntityPicker()
        edit.openAddEntityDialog()
        edit.updateEntityInput("A.I.")
        edit.submitEntityInput(mapOf("ai" to "AI"))
        edit.resolveEntityConflict(replaceAcrossLibrary = true)

        edit.closeTagEditor()

        assertFalse(edit.showTagEditDialog)
        assertFalse(edit.showAddEntityDialog)
        assertFalse(edit.showEntityBottomSheet)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements)
        assertNull(edit.pendingConflict)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        assertEquals("Reopening starts again from the stored tag", listOf("AI"), edit.draftEntities)
        assertEquals("Cancelling the tag dialog never touches the note", "Independent note draft", edit.noteText)
    }

    @Test
    fun dismissTagEditor_ignoresCancelWhileSaving() {
        val bookmark = createBookmark()
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        edit.updateDraftCategory("Tool")
        edit.updateDraftSubcategory("Business")
        edit.updateDraftPrivate(true)

        edit.dismissTagEditor(isSaving = true)
        assertTrue(edit.showTagEditDialog)
        assertEquals("Tool", edit.draftCategory)
        assertEquals("Business", edit.draftSubcategory)
        assertTrue(edit.draftIsPrivate)

        edit.dismissTagEditor(isSaving = false)
        assertFalse(edit.showTagEditDialog)
    }

    @Test
    fun beginTagCommit_clearsFailureAndBuildsImmutableTag() {
        val bookmark = createBookmark()
        val edit = BookmarkDetailEditState(bookmark.note)
        edit.openTagEditor(bookmark.tag, bookmark.isPrivate)
        edit.updateDraftCategory("Tool")
        edit.updateDraftSubcategory("Business")
        edit.updateDraftPrivate(true)
        edit.submitEntityInput(mapOf("ai" to "AI"), "A.I.")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        edit.onTagSaveFailed()
        assertTrue(edit.tagSaveFailed)

        val commit = edit.beginTagCommit()

        assertFalse(edit.tagSaveFailed)
        assertEquals(Tag("Tool", "Business", listOf("A.I.")), commit.tag)
        assertTrue(commit.isPrivate)
        assertEquals(mapOf("ai" to "A.I."), commit.replacements)
    }

    @Test
    fun submitEntityInput_addsNonConflictingEntityAndClosesAddDialog() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag("Article", "Tech", emptyList()), isPrivate = false)
        edit.openAddEntityDialog()
        edit.updateEntityInput("  #Kotlin  ")

        edit.submitEntityInput(librarySpellings = emptyMap())

        assertFalse(edit.showAddEntityDialog)
        assertNull(edit.pendingConflict)
        assertEquals(listOf("Kotlin"), edit.draftEntities)
    }

    @Test
    fun useExistingAddsCanonicalSpellingWithoutRename() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, emptyList()), isPrivate = false)
        edit.submitEntityInput(mapOf("openai" to "OpenAI"), "open-ai")
        assertEquals(EntityTagConflict("open-ai", "OpenAI", "openai"), edit.pendingConflict)

        edit.resolveEntityConflict(replaceAcrossLibrary = false)

        assertNull(edit.pendingConflict)
        assertEquals(listOf("OpenAI"), edit.draftEntities)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements)
    }

    @Test
    fun replacementRewritesDraftAndStagesSpelling() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, listOf("OpenAI", "Kotlin")), isPrivate = false)
        edit.submitEntityInput(emptyMap(), "open-ai")
        assertEquals(EntityTagConflict("open-ai", "OpenAI", "openai"), edit.pendingConflict)

        edit.resolveEntityConflict(replaceAcrossLibrary = true)

        assertNull(edit.pendingConflict)
        assertEquals(listOf("open-ai", "Kotlin"), edit.draftEntities)
        assertEquals(mapOf("openai" to "open-ai"), edit.draftReplacements)
    }

    @Test
    fun conflictUsesPendingThenLibraryThenDraft() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, listOf("OpenAI")), isPrivate = false)
        // First stage a pending replacement to OPEN.AI
        edit.submitEntityInput(emptyMap(), "OPEN.AI")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        assertEquals(mapOf("openai" to "OPEN.AI"), edit.draftReplacements)

        // Pending replacement takes precedence over library spelling
        edit.submitEntityInput(mapOf("openai" to "OpenAI"), "open-ai")
        assertEquals("OPEN.AI", edit.pendingConflict?.existing)
        edit.dismissEntityConflict()
        assertNull(edit.pendingConflict)

        // When no pending replacement or library match exists, draft entities are checked
        val draftOnly = BookmarkDetailEditState("Note")
        draftOnly.openTagEditor(Tag(null, null, listOf("OpenAI")), isPrivate = false)
        draftOnly.submitEntityInput(emptyMap(), "open-ai")
        assertEquals("OpenAI", draftOnly.pendingConflict?.existing)
        draftOnly.dismissEntityConflict()

        // Exact match with existing spelling raises no conflict
        draftOnly.submitEntityInput(emptyMap(), "OpenAI")
        assertNull(draftOnly.pendingConflict)
    }

    @Test
    fun laterChoiceWinsAndUseExistingPreservesPendingRename() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, listOf("OpenAI")), isPrivate = false)
        edit.submitEntityInput(mapOf("openai" to "open_ai"), "OpenAI")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        assertEquals(mapOf("openai" to "OpenAI"), edit.draftReplacements)

        // Later replacement overwrites staged replacement
        edit.submitEntityInput(mapOf("openai" to "OpenAI"), "OPEN-AI")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        assertEquals(mapOf("openai" to "OPEN-AI"), edit.draftReplacements)
        assertEquals(listOf("OPEN-AI"), edit.draftEntities)

        // Choosing "Use existing" keeps the staged replacement intact
        edit.submitEntityInput(mapOf("openai" to "OpenAI"), "open-ai")
        assertEquals("OPEN-AI", edit.pendingConflict?.existing)
        edit.resolveEntityConflict(replaceAcrossLibrary = false)
        assertEquals(mapOf("openai" to "OPEN-AI"), edit.draftReplacements)
        assertEquals(listOf("OPEN-AI"), edit.draftEntities)
    }

    @Test
    fun fullDraftCanReplaceButCannotAddEleventhKey() {
        val tenEntities = listOf("OpenAI") + (1..9).map { "Tag$it" }
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, tenEntities), isPrivate = false)

        // Replacing an existing key in a 10-entity draft succeeds
        edit.submitEntityInput(emptyMap(), "open-ai")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        assertEquals("open-ai", edit.draftEntities.first())
        assertEquals(10, edit.draftEntities.size)

        // Adding an 11th distinct entity key is ignored
        edit.submitEntityInput(emptyMap(), "Another")
        assertNull(edit.pendingConflict)
        assertEquals(10, edit.draftEntities.size)
        assertFalse("Another" in edit.draftEntities)
    }

    @Test
    fun emptySkeletonCannotBeAddedOrStaged() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, emptyList()), isPrivate = false)
        edit.submitEntityInput(emptyMap(), "# -_. ")
        assertNull(edit.pendingConflict)
        assertEquals(emptyList<String>(), edit.draftEntities)
        assertEquals(emptyMap<String, String>(), edit.draftReplacements)
    }

    @Test
    fun rawInputIsCleanedOnceAndStoredLeadingHashIsPreserved() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag(null, null, listOf("#C", "C")), isPrivate = false)
        edit.submitEntityInput(mapOf("#c" to "#C", "c" to "C"), "##C")
        assertNull(edit.pendingConflict)
        assertEquals(listOf("#C", "C"), edit.draftEntities)

        val emptyEdit = BookmarkDetailEditState("Note")
        emptyEdit.openTagEditor(Tag(null, null, emptyList()), isPrivate = false)
        emptyEdit.submitEntityInput(emptyMap(), "##C")
        assertNull(emptyEdit.pendingConflict)
        assertEquals(listOf("#C"), emptyEdit.draftEntities)
    }

    @Test
    fun appendEntitySuggestionToInput_intoEmptyOrBlankInput_returnsCleanedToken() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.appendEntitySuggestionToInput("iPhone")
        assertEquals("iPhone", edit.entityInput)

        edit.updateEntityInput("   ")
        edit.appendEntitySuggestionToInput("  iPhone  ")
        assertEquals("iPhone", edit.entityInput)
    }

    @Test
    fun appendEntitySuggestionToInput_asciiAlphanumericTokens_insertsSingleSpace() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.appendEntitySuggestionToInput("iPhone")
        edit.appendEntitySuggestionToInput("16")
        edit.appendEntitySuggestionToInput("Pro")
        assertEquals("iPhone 16 Pro", edit.entityInput)
    }

    @Test
    fun appendEntitySuggestionToInput_cjkTokens_concatenatesWithoutSpace() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.appendEntitySuggestionToInput("東京")
        edit.appendEntitySuggestionToInput("タワー")
        assertEquals("東京タワー", edit.entityInput)
    }

    @Test
    fun appendEntitySuggestionToInput_mixedAsciiAndCjk_concatenatesWithoutSpace() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.updateEntityInput("iPhone")
        edit.appendEntitySuggestionToInput("発売")
        assertEquals("iPhone発売", edit.entityInput)

        edit.updateEntityInput("新型")
        edit.appendEntitySuggestionToInput("iPhone")
        assertEquals("新型iPhone", edit.entityInput)
    }

    @Test
    fun appendEntitySuggestionToInput_whenInputEndsWithWhitespace_doesNotDoubleSpace() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.updateEntityInput("iPhone ")
        edit.appendEntitySuggestionToInput("16")
        assertEquals("iPhone 16", edit.entityInput)
    }

    @Test
    fun appendEntitySuggestionToInput_withBlankToken_returnsCurrentInputUnchanged() {
        val edit = BookmarkDetailEditState("Note")
        edit.openAddEntityDialog()
        edit.updateEntityInput("Kotlin")
        edit.appendEntitySuggestionToInput("   ")
        assertEquals("Kotlin", edit.entityInput)
    }

    @Test
    fun pickerOperations_overlayStagedReplacementsToggleWithMaxLimitAndRemoveBySkeletonKey() {
        val edit = BookmarkDetailEditState("Note")
        edit.openTagEditor(Tag("Article", "Tech", listOf("OpenAI", "#C")), isPrivate = false)
        edit.submitEntityInput(mapOf("openai" to "OpenAI", "kotlin" to "Kotlin"), "OPEN-AI")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)

        edit.openEntityPicker()
        assertTrue(edit.showEntityBottomSheet)
        // Picker overlays staged replacement ("OPEN-AI") over library ("OpenAI") without duplicating keys
        assertEquals(
            listOf("OPEN-AI", "#C", "Kotlin"),
            edit.pickerEntities(mapOf("openai" to "OpenAI", "kotlin" to "Kotlin", "#c" to "#C"))
        )

        // Toggle preserves leading '#' on display entities
        edit.togglePickerEntity("#C")
        assertEquals(listOf("OPEN-AI"), edit.draftEntities)
        edit.togglePickerEntity("#C")
        assertEquals(listOf("OPEN-AI", "#C"), edit.draftEntities)

        // Remove by skeleton key removes matching entity
        edit.removePickerEntity("open_ai")
        assertEquals(listOf("#C"), edit.draftEntities)

        // Fill up to EntityTagPolicy.MAX_ENTITIES (10) and verify toggling an 11th distinct entity is ignored
        (2..10).forEach { edit.togglePickerEntity("Entity$it") }
        val fullTen = listOf("#C") + (2..10).map { "Entity$it" }
        assertEquals(fullTen, edit.draftEntities)
        edit.togglePickerEntity("Eleventh")
        assertEquals(fullTen, edit.draftEntities)

        edit.dismissEntityPicker()
        assertFalse(edit.showEntityBottomSheet)
    }

    @Test
    fun savedStateSnapshot_roundTripsAllDraftConflictAndNoteStates() {
        val edit = BookmarkDetailEditState("Initial")
        edit.onNoteTyped("Edited note")
        edit.openTagEditor(Tag("Article", "Tech", listOf("AI")), isPrivate = true)
        edit.openAddEntityDialog()
        edit.updateEntityInput("open-ai")
        edit.submitEntityInput(mapOf("openai" to "OpenAI"))
        edit.openEntityPicker()
        edit.onTagSaveFailed()

        val restored = BookmarkDetailEditState.fromSavedSnapshot(edit.toSavedSnapshot())

        assertEquals("Edited note", restored.noteText)
        assertTrue(restored.isNoteEdited)
        assertEquals("Edited note", restored.pendingNoteWrite()?.text)
        assertTrue(restored.showTagEditDialog)
        assertFalse(restored.showAddEntityDialog)
        assertTrue(restored.showEntityBottomSheet)
        assertEquals("Article", restored.draftCategory)
        assertEquals("Tech", restored.draftSubcategory)
        assertEquals(listOf("AI"), restored.draftEntities)
        assertTrue(restored.draftIsPrivate)
        assertTrue(restored.tagSaveFailed)
        assertEquals("open-ai", restored.entityInput)
        assertEquals(EntityTagConflict("open-ai", "OpenAI", "openai"), restored.pendingConflict)
    }
}
