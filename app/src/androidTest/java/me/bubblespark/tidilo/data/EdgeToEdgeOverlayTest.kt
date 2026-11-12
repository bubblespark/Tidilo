package me.bubblespark.tidilo.data

import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real native windows retain ownership of IME; all saves use inert fixture callbacks. */
class EdgeToEdgeOverlayTest {
    @Test fun collectionEditorInputAndSaveStayAboveIme() = verifyInput(R.string.collection_create_action) {
        CollectionEditorContent(CollectionEditorState(CollectionDraft("Inset fixture")), false, {}, {}, {}, {}, {})
    }
    @Test fun rssEditorInputStaysAboveIme() = verifyInput(R.string.rss_save) {
        RssFeedEditor(me.bubblespark.tidilo.data.db.RssFeedEntity("inset", "https://fixture.invalid/feed", "Fixture"), {}) {}
    }
    @Test fun importDialogInputAndActionsStayAboveIme() = verifyInput(R.string.import_dialog_confirm) { ImportUrlDialog({}, {}) }
    @Test fun entityDialogInputAndActionsStayAboveIme() = verifyInput(R.string.common_ok) {
        AddEntityDialog({}, {}, "Inset fixture", {}, EntityLibraryState(loading = false), {})
    }
    @Test fun membershipAndPickerKeepNativeNavigationClearance(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (theme in AppTheme.entries) {
                host.main { activity.setContent { TidiloTheme(theme = theme) {
                    CollectionMembershipContent(CollectionMembershipState(loading = false), {}, {}, {}, {}, {})
                } } }
                host.await(activity) { it.config.getOrNull(SemanticsActions.Dismiss) != null }
                delay(400)
                assertActionsSafe(host, activity)
                host.main { activity.setContent { TidiloTheme(theme = theme) {
                    EntityTagPickerBottomSheet(emptyList(), (0..40).map { "Fixture $it" }, {}, {}, {})
                } } }
                host.await(activity) { it.config.getOrNull(SemanticsActions.Dismiss) != null }
                delay(400)
                assertActionsSafe(host, activity)
            }
        } finally { host.main { activity.setContent {}; activity.finish() } }
    }

    private fun verifyInput(actionRes: Int, content: @Composable () -> Unit): Unit = runBlocking {
        for (theme in AppTheme.entries) {
            val host = HeaderTestHost()
            val activity = host.launch()
            fun modalWindow() = WindowInspector.getGlobalWindowViews().last {
                it.isShown && it.width > activity.window.decorView.width / 2 && it.height > 200
            }
            try {
                host.main { activity.setContent { TidiloTheme(theme = theme) { content() } } }
                host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0 }
                delay(500)
                host.main {
                    val field = host.nodes(activity).first { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0 }
                    check(field.config[SemanticsActions.RequestFocus].action?.invoke() == true)
                    ViewCompat.getWindowInsetsController(modalWindow())?.show(WindowInsetsCompat.Type.ime())
                }
                withTimeout(5000) {
                    while (true) {
                        var ime = false
                        host.main { ime = WindowInspector.getGlobalWindowViews().any {
                            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        } }
                        if (ime) break
                        delay(30)
                    }
                }
                delay(400)
                host.main {
                    val window = modalWindow()
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(window))
                    val origin = IntArray(2)
                    window.getLocationOnScreen(origin)
                    val bottom = activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - origin[1]
                    val current = host.nodes(activity).first {
                        it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true && it.boundsInWindow.height > 0
                    }.boundsInWindow
                    assertTrue("$theme field $current must fit above IME $bottom", current.bottom <= bottom + 1 && current.top >= 0)
                    val label = host.nodes(activity).first { host.text(it, host.context.getString(actionRes)) }
                    var action = label
                    while (action.parent != null && action.config.getOrNull(SemanticsActions.OnClick) == null) action = requireNotNull(action.parent)
                    assertTrue("$theme action ${action.boundsInWindow} must fit above IME $bottom", action.boundsInWindow.bottom <= bottom + 1)
                }
            } catch (error: Throwable) {
                host.capture("overlay-input-failure-$actionRes")
                throw error
            } finally { host.main { activity.setContent {}; activity.finish() } }
        }
    }

    private fun assertActionsSafe(host: HeaderTestHost, activity: androidx.activity.ComponentActivity) {
        host.main {
            val focused = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            val nav = requireNotNull(ViewCompat.getRootWindowInsets(focused)).getInsets(WindowInsetsCompat.Type.navigationBars())
            for (node in host.nodes(activity)) {
                if (node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button && node.boundsInWindow.height > 0) {
                    assertTrue("Button ${node.boundsInWindow} must avoid navigation inset $nav", node.boundsInWindow.bottom <= activity.window.decorView.height - nav.bottom + 1)
                }
            }
        }
    }
}