package me.bubblespark.tidilo.data

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Language and Theme dialogs in a short landscape window at font scale 2.
 *
 * Dialog windows take their size from the real window and their density from their own context, so a
 * parent box cannot shrink them. The test rotates only its own activity once and renders the screen in a
 * view whose context carries font scale 2, which the dialogs inherit.
 */
class SettingsDialogLandscapeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    @Test fun languageAndThemeDialogsScrollInShortLandscapeWindowsAtLargeFont() {
        rotateToLandscape()
        composeRule.activityRule.scenario.onActivity { activity ->
            val largeFont = ContextThemeWrapper(activity, activity.theme).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = 2f })
            }
            activity.setContent {
                AndroidView({
                    ComposeView(largeFont).apply {
                        setContent { TidiloTheme(blurEnabled = false, dynamicColor = false) { SettingsScreen({}, {}) } }
                    }
                }, Modifier.fillMaxSize())
            }
        }
        for (dialog in listOf(R.string.settings_language_title, R.string.settings_theme_title)) {
            assertDialogScrollsToLastControl(dialog)
        }
    }

    private fun rotateToLandscape() {
        val resumed = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.RESUMED &&
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) resumed.countDown()
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            composeRule.activityRule.scenario.onActivity {
                if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) resumed.countDown()
                else it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            assertTrue("Activity did not resume in landscape", resumed.await(8, TimeUnit.SECONDS))
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
        composeRule.waitForIdle()
    }

    private fun assertDialogScrollsToLastControl(dialog: Int) {
        val rowLabel = string(dialog)
        val close = string(R.string.common_close)
        composeRule.onNode(hasText(rowLabel) and hasClickAction()).performClick()
        val body = composeRule.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))[0]
        val closeButton = composeRule.onNode(hasText(close) and hasClickAction() and hasAnyAncestor(isDialog()))
        assertRendersAtLargeFont(rowLabel, closeButton.fetchSemanticsNode())
        assertFullyVisible("$rowLabel Close", closeButton.fetchSemanticsNode())

        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        if (dialog == R.string.settings_theme_title) {
            assertTrue("$rowLabel overflows, so this case exercises scrolling", range.maxValue() > 0f)
        }
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() - range.value()) }
        composeRule.waitForIdle()

        val viewport = body.fetchSemanticsNode().boundsInWindow
        val role = if (dialog == R.string.settings_theme_title) Role.Switch else Role.RadioButton
        val controls = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
            and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes()
        val last = if (role == Role.Switch) controls.first() else controls.last()
        val bounds = last.boundsInWindow
        // A large-font row can be taller than a cutout-constrained viewport, so require the
        // row to be scrolled into view with its label unclipped and a usable visible target.
        assertTrue("$rowLabel last control is scrolled into view: $bounds in $viewport",
            bounds.height > 0f && bounds.bottom <= viewport.bottom + 1f)
        val minVisible = minOf(48f * composeRule.density.density, viewport.height) - 1f
        assertTrue("$rowLabel last control keeps a usable visible target: ${bounds.height} px", bounds.height >= minVisible)
        val label = descendants(last).first { it.config.getOrNull(SemanticsProperties.Text) != null }
        val labelBounds = label.boundsInWindow
        assertTrue("$rowLabel last control label is unclipped: $labelBounds in $viewport",
            labelBounds.top >= viewport.top - 1f && labelBounds.bottom <= viewport.bottom + 1f)
        assertEquals("$rowLabel last control label is fully visible", label.size.height.toFloat(), labelBounds.height, 1f)

        assertFullyVisible("$rowLabel Close", closeButton.fetchSemanticsNode())
        closeButton.performClick()
        closeButton.assertDoesNotExist()
    }

    /** Guards against the font-scale override silently not reaching the dialog window. */
    private fun assertRendersAtLargeFont(rowLabel: String, closeButton: SemanticsNode) {
        val text = descendants(closeButton).plus(closeButton)
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertEquals("$rowLabel dialog font scale", 2f, layouts.single().layoutInput.density.fontScale, 0.01f)
    }

    private fun assertFullyVisible(what: String, node: SemanticsNode) {
        val bounds = node.boundsInWindow
        assertTrue("$what is visible: $bounds", bounds.height > 0f)
        assertEquals("$what is fully visible", node.size.height.toFloat(), bounds.height, 1f)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun visit(current: SemanticsNode): Unit = current.children.forEach { out += it; visit(it) }
        visit(node)
        return out
    }
}
