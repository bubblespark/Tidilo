package me.bubblespark.tidilo.data

import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real native windows retain ownership of IME; all saves use inert fixture callbacks. */
class EdgeToEdgeOverlayTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    @Test fun collectionEditorInputAndSaveStayAboveIme() = verifyInput(R.string.collection_create_action) {
        CollectionEditorContent(CollectionEditorState(CollectionDraft("Inset fixture")), false, {}, {}, {}, {}, {})
    }
    @Test fun rssEditorInputStaysAboveIme() = verifyInput(R.string.rss_save) {
        RssFeedEditor(me.bubblespark.tidilo.data.db.RssFeedEntity("inset", "https://fixture.invalid/feed", "Fixture"), {}) {}
    }
    @Test fun importDialogInputAndActionsStayAboveIme() = verifyInput(R.string.import_dialog_confirm) { ImportUrlDialog({}, {}) }
    @Test fun entityDialogInputAndActionsStayAboveIme() = verifyInput(R.string.common_ok) {
        AddEntityDialog({}, {}, "Inset fixture", {}, EntityLibraryState(loading = false), {}, onSuggestionClick = {})
    }
    @Test fun membershipAndPickerKeepNativeNavigationClearance() {
        var picker by mutableStateOf(false)
        mount {
            if (picker) EntityTagPickerBottomSheet(emptyList(), (0..40).map { "Fixture $it" }, {}, {}, {})
            else CollectionMembershipContent(CollectionMembershipState(loading = false), {}, {}, {}, {}, {})
        }
        for (next in listOf(false, true)) {
            composeRule.runOnUiThread { picker = next }
            if (next) composeRule.awaitNode(hasText("Fixture 0"))
            composeRule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            composeRule.waitForIdle()
            assertActionsSafe()
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        // MainActivity draws edge to edge; the native windows' inset handling depends on it.
        composeRule.runOnUiThread { activity.enableEdgeToEdge() }
        composeRule.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { content() } }
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun modalWindow() = WindowInspector.getGlobalWindowViews().last {
        it.isShown && it.width > activity.window.decorView.width / 2 && it.height > 200
    }

    private fun clickableAncestor(node: SemanticsNode): SemanticsNode {
        var action = node
        while (action.parent != null && action.config.getOrNull(SemanticsActions.OnClick) == null) action = requireNotNull(action.parent)
        return action
    }

    private fun verifyInput(actionRes: Int, content: @Composable () -> Unit) {
        val editable = SemanticsMatcher("visible editable field") {
            it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0
        }
        val actionLabel = hasText(activity.getString(actionRes))
        mount(content)
        composeRule.awaitNode(editable)
        composeRule.waitForIdle()
        val field = nodes(editable).first()
        composeRule.runOnUiThread {
            check(field.config[SemanticsActions.RequestFocus].action?.invoke() == true)
            ViewCompat.getWindowInsetsController(modalWindow())?.show(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(5_000) {
            WindowInspector.getGlobalWindowViews().any {
                ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        composeRule.waitForIdle()
        var imeTop = 0
        composeRule.runOnUiThread {
            val window = modalWindow()
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(window))
            val origin = IntArray(2)
            window.getLocationOnScreen(origin)
            imeTop = activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - origin[1]
        }
        val current = nodes(editable).first { it.config.getOrNull(SemanticsProperties.Focused) == true }.boundsInWindow
        assertTrue("Field $current must fit above IME $imeTop", current.bottom <= imeTop + 1 && current.top >= 0)
        if (clickableAncestor(nodes(actionLabel).first()).fullWindowBounds().bottom > imeTop + 1) {
            composeRule.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            composeRule.waitForIdle()
        }
        val bounds = clickableAncestor(nodes(actionLabel).first()).fullWindowBounds()
        assertTrue("Action $bounds must be reachable above IME $imeTop",
            bounds.width > 0 && bounds.height > 0 && bounds.top >= 0 && bounds.bottom <= imeTop + 1)
    }

    private fun assertActionsSafe() {
        var safeBottom = 0
        var nav: androidx.core.graphics.Insets? = null
        composeRule.runOnUiThread {
            val focused = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            nav = requireNotNull(ViewCompat.getRootWindowInsets(focused)).getInsets(WindowInsetsCompat.Type.navigationBars())
            safeBottom = activity.window.decorView.height - requireNotNull(nav).bottom
        }
        for (node in nodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))) {
            if (node.boundsInWindow.height > 0) {
                assertTrue("Button ${node.boundsInWindow} must avoid navigation inset $nav", node.boundsInWindow.bottom <= safeBottom + 1)
            }
        }
    }
}
