package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditedFieldsTest {
    private enum class F { TITLE, DESCRIPTION }

    @Test
    fun aWriteClearsOnlyFieldsNotEditedSince() {
        val edits = EditedFields<F>()
        edits.markEdited(listOf(F.TITLE, F.DESCRIPTION))
        val snapshot = edits.snapshot()
        edits.markEdited(listOf(F.TITLE))

        edits.onWritten(snapshot)

        assertEquals(setOf(F.TITLE), edits.fields)
    }

    @Test
    fun revertingToAnEarlierValueIsStillAnEdit() {
        val edits = EditedFields<F>()
        edits.markEdited(listOf(F.TITLE))           // A -> B
        val writeB = edits.snapshot()
        edits.markEdited(listOf(F.TITLE))           // B -> A while B is in flight
        val writeA = edits.snapshot()

        edits.onWritten(writeB)
        assertTrue(edits.isEdited(F.TITLE))
        edits.onWritten(writeA)
        assertTrue(edits.isEmpty)
    }

    @Test
    fun restoredFieldsAreOwedUntilWritten() {
        val edits = EditedFields(listOf(F.DESCRIPTION))
        assertEquals(setOf(F.DESCRIPTION), edits.fields)
        edits.onWritten(edits.snapshot())
        assertTrue(edits.isEmpty)
    }
}
