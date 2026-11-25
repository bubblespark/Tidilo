package me.bubblespark.tidilo.data

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryRadialMenuAccessibilityTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun nodes(matcher: SemanticsMatcher, unmerged: Boolean = true): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }

    private fun awaitFocused(suffix: String) = await(hasTestTag("library_radial_$suffix") and isFocused())

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        composeRule.waitForIdle()
    }

    @Test fun closeRestoresTriggerFocusFromAnItem() {
        val scene = RadialMenuScene(composeRule)
        scene.show()
        for (physical in listOf(false, true)) {
            var stage = "open"
            try {
                scene.click("trigger")
                scene.node("search")
                key(KeyEvent.KEYCODE_TAB)
                stage = "focus item"
                awaitFocused("search")
                stage = "close"
                if (physical) scene.tap(scene.center("trigger")) else scene.click("trigger")
                stage = "restore trigger"
                awaitFocused("trigger")
                scene.assertClosedWithoutAction()
            } catch (error: Throwable) { throw AssertionError("physical=$physical, stage=$stage", error) }
        }
    }

    @Test fun everyShortcutHasIndependentActivationAndOnlyFiltersExposeSelection() {
        val scene = RadialMenuScene(composeRule)
        scene.show()
        for ((suffix, action) in listOf("search" to "SEARCH", "collection" to "COLLECTION", "rss" to "RSS",
            "domain" to "DOMAIN", "newest_first" to "NEWEST_FIRST")) {
            scene.click("trigger")
            scene.node(suffix)
            // The merged tree is what accessibility services see.
            val buttons = nodes(isRadialButton, unmerged = false)
            assertEquals(6, buttons.size)
            assertEquals(4, buttons.count { it.config.contains(SemanticsProperties.Selected) })
            assertEquals(1, buttons.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
            scene.click(suffix)
            composeRule.runOnIdle { assertEquals(action, scene.actions.last()); assertFalse(scene.state.isOpen) }
        }
        scene.click("trigger")
        key(KeyEvent.KEYCODE_TAB)
        key(KeyEvent.KEYCODE_ESCAPE)
        composeRule.runOnIdle { assertFalse(scene.state.isOpen) }
        key(KeyEvent.KEYCODE_ENTER)
        scene.node("search")
        key(KeyEvent.KEYCODE_SPACE)
        composeRule.runOnIdle { assertFalse(scene.state.isOpen) }
    }

    @Test fun realLibraryEffectiveTreeHidesAndRestoresBackgroundActions() = runBlocking {
        val fixtureTitle = "Radial fixture"
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) {
            InMemoryLibrary(context).also {
                it.awaitReady()
                it.db.bookmarkDao().insertAll(listOf(BookmarkEntity("radial-a11y", fixtureTitle,
                    "https://radial.invalid/a11y", "Summary", createdAt = 1, updatedAt = 1)))
            }
        }
        try {
            val model = library.viewModel()
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { LibraryScreen(viewModel = model) }
            }
            val title = context.getString(R.string.library_header_title)
            await(hasText(fixtureTitle))
            fun backgroundActions(): Int = nodes(hasClickAction(), unmerged = false).count { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title || it.text.contains(fixtureTitle) } == true
            }
            val baseline = backgroundActions()
            assertTrue(baseline > 0)
            composeRule.onAllNodes(hasTestTag("library_radial_trigger") and hasClickAction(), useUnmergedTree = true)[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            await(hasContentDescription(context.getString(R.string.common_close)))
            assertEquals(0, backgroundActions())
            for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) repeat(5) {
                key(code)
                assertTrue("Directional focus escaped the menu: $code", nodes(isRadialButton and isFocused()).isNotEmpty())
            }
            composeRule.onAllNodes(hasTestTag("library_radial_trigger") and hasClickAction(), useUnmergedTree = true)[0]
                .performSemanticsAction(SemanticsActions.RequestFocus)
            key(KeyEvent.KEYCODE_TAB)
            awaitFocused("search")
            key(KeyEvent.KEYCODE_BACK)
            await(hasContentDescription(context.getString(R.string.library_shortcuts_open)))
            awaitFocused("trigger")
            assertEquals(baseline, backgroundActions())
        } finally {
            caches.close()
            library.close()
        }
    }
}
