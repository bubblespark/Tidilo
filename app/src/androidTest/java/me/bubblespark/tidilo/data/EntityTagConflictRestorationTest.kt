package me.bubblespark.tidilo.data

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Tag editor drafts across a saved-instance-state restore of the Detail screen. */
class EntityTagConflictRestorationTest {
    // Dialog and sheet motion settles at once; these tests assert outcomes, not motion.
    @get:Rule val composeRule = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    @Test fun restorationAndRoomEmissionKeepNestedChoice() = runEntityConflict(composeRule) {
        conflict(); restore(); click(R.string.entity_conflict_replace)
        val row = row()
        withContext(Dispatchers.IO) { dao.insertOrUpdate(row.copy(note = "Concurrent note", updatedAt = 3)) }
        await(hasText("Concurrent note"))
        restore(); accept()
        awaitStored { spelling() == variant }
        assertEquals("Tag commit must not revert an independently saved note", "Concurrent note", storedNote())
    }

    @Test fun completeDraftSurvivesRestorationAndKeepsAutosavedNote() = runEntityConflict(composeRule) {
        note("My autosaved note")
        awaitStored { storedNote() == "My autosaved note" }
        conflict(); click(R.string.entity_conflict_replace)
        click("Select Category"); click(R.string.category_tool)
        click("Select Subcategory"); click(R.string.subcategory_tech)
        val privateSwitch = await(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off) and inTagDialog)
        composeRule.runOnUiThread { check(privateSwitch.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true) }
        restore(); accept()
        awaitStored { row().isPrivate }
        val saved = row()
        assertEquals("My autosaved note", saved.note)
        assertEquals("Tool", saved.category); assertEquals("Tech", saved.subcategory)
        assertEquals(variant, spelling(id))
    }

    @Test fun restoredNoteDraftAutosavesWithoutClassification() = runEntityConflict(composeRule) {
        note("Restored note"); restore()
        awaitStored { storedNote() == "Restored note" }
        val row = row()
        assertNull(row.category); assertNull(row.subcategory)
        assertEquals(0, navigations)
    }
}
