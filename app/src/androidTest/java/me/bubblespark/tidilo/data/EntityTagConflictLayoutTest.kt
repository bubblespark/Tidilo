package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.library.AddEntityDialog
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailEditState
import me.bubblespark.tidilo.ui.screens.library.BookmarkTagEditDialog
import me.bubblespark.tidilo.ui.screens.library.EntityLibraryState
import me.bubblespark.tidilo.ui.screens.library.EntityTagConflict
import me.bubblespark.tidilo.ui.screens.library.EntityTagConflictDialog
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EntityTagConflictLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The clickable control whose own or child node is labelled [label] (text or description). */
    private fun button(label: String): SemanticsNode {
        val matcher = hasText(label) or hasContentDescription(label)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first()
        return generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }
    }

    private fun isEnabled(label: String) = SemanticsProperties.Disabled !in button(label).config

    private fun click(label: String) {
        val action = requireNotNull(button(label).config[SemanticsActions.OnClick].action)
        composeRule.runOnUiThread { check(action()) }
        composeRule.waitForIdle()
    }

    private fun inputText(): String? = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        .fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun awaitInput(expected: String) = composeRule.waitUntil(5_000) { inputText() == expected }

    @Test fun lookupLoadingAndFailureBlockConfirmationUntilRetryCompletes() {
        var state by mutableStateOf(EntityLibraryState())
        var confirmed: String? = null
        var retries = 0
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                AddEntityDialog({}, { confirmed = it }, "##C", {}, state, {
                    retries++; state = EntityLibraryState()
                }, onSuggestionClick = {})
            }
        }
        val ok = context.getString(R.string.common_ok)
        assertFalse(isEnabled(ok))
        composeRule.runOnUiThread { state = EntityLibraryState(loading = false, error = IllegalStateException("Fixture")) }
        click(context.getString(R.string.common_retry))
        assertEquals(1, retries)
        assertFalse(isEnabled(ok))
        composeRule.runOnUiThread { state = EntityLibraryState(loading = false) }
        composeRule.waitUntil(5_000) { isEnabled(ok) }
        click(ok)
        composeRule.waitUntil(5_000) { confirmed != null }
        assertEquals("##C", confirmed)
    }

    @Test fun longSpellingsAndBothChoicesFitAt360dpAndDoubleFontInBothThemes() {
        var variant by mutableStateOf(false to 1f)
        var choice: Boolean? = null
        composeRule.setContent {
            TidiloTheme(darkTheme = variant.first, dynamicColor = false, blurEnabled = false) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, variant.second)) {
                    EntityTagConflictDialog("A very long manually entered spelling ".repeat(6),
                        "A very long existing canonical spelling ".repeat(6), {}, { choice = it },
                        Modifier.width(312.dp))
                }
            }
        }
        val labels = listOf(
            context.getString(R.string.entity_conflict_use_existing) to false,
            context.getString(R.string.entity_conflict_replace) to true
        )
        val screenWidth = context.resources.displayMetrics.widthPixels
        val density = context.resources.displayMetrics.density
        for (target in listOf(false to 1f, false to 2f, true to 1f, true to 2f)) {
            composeRule.runOnUiThread { variant = target; choice = null }
            composeRule.waitForIdle()
            for ((label, expected) in labels) {
                val bounds = button(label).boundsInWindow
                assertTrue("48dp action at dark=${target.first} scale=${target.second}", bounds.height / density >= 47f)
                assertTrue(bounds.left >= 0f && bounds.right <= screenWidth)
                click(label)
                composeRule.waitUntil(5_000) { choice == expected }
            }
        }
    }

    @Test fun displayChipsKeepMeaningfulLeadingHash() {
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                BookmarkTagEditDialog(null, null, listOf("#C", "C"),
                    onCategoryChanged = {}, onSubcategoryChanged = {}, onAddEntityClick = {},
                    onOpenEntityPickerClick = {}, onConfirm = {}, onDismiss = {})
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("#C"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun suggestionChipsAppendToInputAndClearResetsInput() {
        val edit = BookmarkDetailEditState("Note").apply { openAddEntityDialog() }
        var confirmed: String? = null
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                AddEntityDialog(
                    onDismiss = edit::dismissAddEntityDialog,
                    onConfirm = { confirmed = it },
                    text = edit.entityInput,
                    onTextChange = edit::updateEntityInput,
                    libraryState = EntityLibraryState(loading = false),
                    onRetry = {},
                    candidates = listOf("iPhone", "16", "発売"),
                    onSuggestionClick = edit::appendEntitySuggestionToInput
                )
            }
        }
        click("iPhone")
        awaitInput("iPhone")
        click(context.getString(R.string.common_clear))
        awaitInput("")
        click("iPhone")
        awaitInput("iPhone")
        click("16")
        awaitInput("iPhone 16")
        click(context.getString(R.string.common_ok))
        composeRule.waitForIdle()
        assertEquals("iPhone 16", confirmed)
    }

    @Test fun saver_roundTripsAllDraftConflictAndNoteStatesThroughBundle() {
        val edit = BookmarkDetailEditState("Initial")
        edit.onNoteTyped("Edited note")
        edit.openTagEditor(Tag("Article", "Tech", listOf("AI", "OpenAI")), isPrivate = true)
        edit.submitEntityInput(mapOf("ai" to "AI"), "A.I.")
        edit.resolveEntityConflict(replaceAcrossLibrary = true)
        edit.openAddEntityDialog()
        edit.updateEntityInput("open-ai")
        edit.submitEntityInput(mapOf("openai" to "OpenAI"))
        edit.openEntityPicker()
        edit.onTagSaveFailed()

        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        val savedBundle = with(BookmarkDetailEditState.Saver) { scope.save(edit) }
        assertNotNull(savedBundle)
        val restored = BookmarkDetailEditState.Saver.restore(requireNotNull(savedBundle))
        assertNotNull(restored)
        restored!!

        assertEquals("Edited note", restored.noteText)
        assertTrue(restored.isNoteEdited)
        assertEquals("Edited note", restored.pendingNoteWrite()?.text)
        assertTrue(restored.showTagEditDialog)
        assertFalse(restored.showAddEntityDialog)
        assertTrue(restored.showEntityBottomSheet)
        assertEquals("Article", restored.draftCategory)
        assertEquals("Tech", restored.draftSubcategory)
        assertEquals(listOf("A.I.", "OpenAI"), restored.draftEntities)
        assertTrue(restored.draftIsPrivate)
        assertEquals(mapOf("ai" to "A.I."), restored.draftReplacements)
        assertTrue(restored.tagSaveFailed)
        assertEquals("open-ai", restored.entityInput)
        assertEquals(EntityTagConflict("open-ai", "OpenAI", "openai"), restored.pendingConflict)

        // Separate Saver round trip with Add Entity dialog open and in-progress input
        val addOpenEdit = BookmarkDetailEditState("Note").apply {
            openTagEditor(Tag("Tool", "Business", listOf("Kotlin")), isPrivate = false)
            openAddEntityDialog()
            updateEntityInput("Compose")
        }
        val addOpenBundle = with(BookmarkDetailEditState.Saver) { scope.save(addOpenEdit) }
        val restoredAddOpen = requireNotNull(BookmarkDetailEditState.Saver.restore(requireNotNull(addOpenBundle)))
        assertTrue(restoredAddOpen.showTagEditDialog)
        assertTrue(restoredAddOpen.showAddEntityDialog)
        assertFalse(restoredAddOpen.showEntityBottomSheet)
        assertEquals("Compose", restoredAddOpen.entityInput)
    }
}
