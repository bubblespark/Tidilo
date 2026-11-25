package me.bubblespark.tidilo.data

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EntityTagConflictFlowTest {
    // Dialog and sheet motion settles at once; these tests assert outcomes, not motion.
    @get:Rule val composeRule = createComposeRule(effectContext = object : MotionDurationScale { override val scaleFactor = 0f })

    private fun verify(currentHasEntity: Boolean = true, classified: Boolean = false, action: suspend EntityTagConflictFixture.() -> Unit) =
        runEntityConflict(composeRule, currentHasEntity, classified, action)

    @Test fun useExistingKeepsCanonicalSpelling() = verify {
        conflict(); click(R.string.entity_conflict_use_existing); accept()
        awaitStored { canonical in row().entities }
        assertEquals(canonical, spelling())
        assertEquals(canonical, spelling(id))
    }

    @Test fun dismissAndParentCancelDoNotPersistReplacement() = verify {
        conflict()
        pressSystemBack()
        assertEquals(canonical, spelling())
        click(R.string.common_cancel, inTagDialog)
        click(R.string.common_back)
        composeRule.waitUntil(8_000) { navigations == 1 }
        assertEquals(canonical, spelling(id))
    }

    @Test fun parentCancelDiscardsNestedReplacement() = verify {
        conflict(); click(R.string.entity_conflict_replace); click(R.string.common_cancel, inTagDialog)
        assertEquals(canonical, spelling())
        assertEquals(canonical, spelling(id))
        click(R.string.common_back)
        composeRule.waitUntil(8_000) { navigations == 1 }
    }

    @Test fun pickerOkThenParentCancelLeavesStoredTagsUnchanged() = verify {
        click(R.string.detail_edit_tags)
        click(R.string.detail_tag_select_entities)
        // The picker is a separate sheet window above the dialog; toggle the entity off in its library list.
        click(canonical, inPicker)
        await(hasText("${string(R.string.detail_entity_picker_selected)} (0/10)"))
        click(R.string.common_ok, inPicker)
        click(R.string.common_cancel, inTagDialog)
        assertEquals(listOf(canonical), row().entities)
        assertEquals(0, navigations)
    }

    @Test fun parentOkPersistsReplacementAndUpdatesHiddenRows() = verify {
        conflict(); click(R.string.entity_conflict_replace)
        assertEquals("Replacement stays a draft until parent OK", canonical, spelling())
        accept()
        awaitStored { spelling() == variant }
        assertEquals(variant, spelling(id))
        assertEquals("Committing tags never navigates away", 0, navigations)
        composeRule.waitUntil(8_000) { nodes(hasText(string(R.string.common_cancel))).isEmpty() }
    }

    @Test fun backAfterCommitLeavesWithoutDiscardPrompt() = verify {
        conflict(); click(R.string.entity_conflict_replace); accept()
        awaitStored { spelling() == variant }
        click(R.string.common_back)
        composeRule.waitUntil(8_000) { navigations == 1 }
        assertEquals(variant, spelling())
    }

    @Test fun tagSaveFailureKeepsDialogDraftForRetry() = verify {
        conflict(); click(R.string.entity_conflict_replace)
        val sql = library.db.openHelper.writableDatabase
        withContext(Dispatchers.IO) {
            sql.execSQL("CREATE TRIGGER entity_conflict_failure BEFORE UPDATE ON bookmarks BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
        }
        accept()
        await(hasText(string(R.string.common_save_failed)))
        assertEquals(0, navigations)
        assertEquals("No partial cross-bookmark replacement", canonical, spelling())
        assertEquals(canonical, spelling(id))
        withContext(Dispatchers.IO) { sql.execSQL("DROP TRIGGER entity_conflict_failure") }
        accept()
        awaitStored { spelling() == variant }
        assertEquals(variant, spelling(id))
    }

    @Test fun replacementOnlyEditCommitsOnParentOk() = verify(classified = true) {
        conflict(); click(R.string.entity_conflict_replace); accept()
        awaitStored { spelling() == variant }
        click(R.string.detail_edit_tags)
        addEntity(canonical)
        click(R.string.entity_conflict_replace); accept()
        awaitStored { spelling(id) == canonical }
        assertEquals(canonical, spelling())
        val row = row()
        assertEquals("Tool", row.category); assertEquals("Tech", row.subcategory)
    }

    @Test fun removedTargetCannotBulkRenameOtherBookmarks() = verify {
        conflict(); click(R.string.entity_conflict_replace)
        withContext(Dispatchers.IO) { dao.deleteById(id) }
        await(hasText(string(R.string.detail_item_not_found)))
        assertEquals(canonical, spelling())
        assertEquals(0, navigations)
    }

    @Test fun rapidOkTapsCommitOnce() = verify {
        conflict(); click(R.string.entity_conflict_replace)
        val ok = await(hasText(string(R.string.common_ok)) and inTagDialog)
        val tap = generateSequence(ok) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        composeRule.runOnUiThread { tap(); tap() }
        awaitStored { spelling() == variant }
        composeRule.waitForIdle()
        assertEquals(0, navigations); assertEquals(variant, spelling(id))
    }

    @Test fun noteAutosaveKeepsLatestExternalSpelling() = verify {
        note("My note")
        library.repository.updateBookmarkTags(other, Tag.fromDisplayValues(null, null, listOf(canonical)), true,
            mapOf(entitySkeletonKey(canonical) to variant), 9)
        awaitStored { storedNote() == "My note" }
        assertEquals(variant, spelling(id))
    }

    @Test fun hiddenOnlyLibrarySpellingRequiresAChoice() = verify(currentHasEntity = false) {
        conflict(); click(R.string.entity_conflict_use_existing); accept()
        awaitStored { row().entities.isNotEmpty() }
        assertEquals(canonical, spelling(id))
    }

    @Test fun pickerShowsStagedSpellingOncePerKey() = verify {
        conflict(); click(R.string.entity_conflict_replace)
        click(R.string.detail_tag_select_entities)
        await(hasText(variant) and inPicker)
        assertTrue("Previous library spelling must be overlaid by the pending choice", nodes(hasText(canonical) and inPicker).isEmpty())
        assertEquals(canonical, spelling())
    }
}

