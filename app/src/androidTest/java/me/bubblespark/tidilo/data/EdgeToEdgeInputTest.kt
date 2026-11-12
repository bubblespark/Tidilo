package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.TagSelectionScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestScreen
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requires a real software IME; emulator keyboard settings are prepared by the runner. */
class EdgeToEdgeInputTest {
    @Test fun tagSelectionViewportAvoidsIme() = verifyViewport { host, activity ->
        TagSelectionScreen(ViewModelProvider(activity)[LibraryViewModel::class.java], {})
    }
    @Test fun debugViewportAvoidsIme() = verifyViewport { _, _ -> SettingsDebugScreen({}) }
    @Test fun parameterViewportAvoidsIme() = verifyViewport { _, _ -> GenerateContentRequestScreen({}) }

    @Test fun detailMemoHidesOnlyLowerActionsWhileImeIsVisible(): Unit = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val db = me.bubblespark.tidilo.data.db.AppDatabase.getInstance(context)
        val id = java.util.UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity(id, "Inset memo", "https://inset.invalid/$id", "Fixture summary", createdAt = 1, updatedAt = 1, note = "A memo that stays available while editing."))
        try {
            verifyViewport(detail = true) { _, activity ->
                me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen(id, ViewModelProvider(activity)[LibraryViewModel::class.java], {})
            }
        } finally { db.bookmarkDao().deleteById(id) }
    }

    private fun verifyViewport(detail: Boolean = false, screen: @Composable (HeaderTestHost, androidx.activity.ComponentActivity) -> Unit): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var keyboard: SoftwareKeyboardController? = null
        try {
            host.main { activity.setContent { MaterialTheme {
                keyboard = LocalSoftwareKeyboardController.current
                screen(host, activity)
            } } }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            // Retrieve live nodes after the initial route has settled, never keep a stale node.
            delay(300)
            var visibleField = false
            host.main { visibleField = host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0 } }
            if (!visibleField) { host.scroll(activity, 2000f); delay(300) }
            host.main {
                val field = host.nodes(activity).first {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null && it.boundsInWindow.height > 0
                }
                check(field.config[SemanticsActions.RequestFocus].action?.invoke() == true)
                keyboard?.show()
            }
            withTimeout(5000) {
                while (true) {
                    var visible = false
                    host.main { visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    if (visible) break
                    delay(30)
                }
            }
            delay(400)
            host.main {
                val ime = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView)).getInsets(WindowInsetsCompat.Type.ime())
                val bottom = activity.window.decorView.height - ime.bottom
                val viewport = host.nodes(activity).first {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                        it.config.getOrNull(SemanticsProperties.EditableText) == null && it.boundsInWindow.height > 100
                }.boundsInWindow
                assertTrue("Scroll viewport $viewport must end above IME top $bottom", viewport.bottom <= bottom + 1f)
                val field = host.nodes(activity).first {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true && it.boundsInWindow.height > 0
                }.boundsInWindow
                assertTrue("Focused field $field must fit above IME $bottom", field.bottom <= bottom + 1f)
                if (detail) {
                    assertTrue(host.nodes(activity).none { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.detail_action_delete)) })
                    assertTrue(host.nodes(activity).any { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.detail_save)) })
                }
            }
            host.main { keyboard?.hide() }
            withTimeout(5000) {
                while (true) {
                    var visible = true
                    host.main { visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    if (!visible) break
                    delay(30)
                }
            }
            delay(300)
            host.main {
                val viewport = host.nodes(activity).first {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                        it.config.getOrNull(SemanticsProperties.EditableText) == null && it.boundsInWindow.height > 100
                }.boundsInWindow
                assertTrue("Viewport must expand after IME dismissal", viewport.bottom >= activity.window.decorView.height - 1f)
            }
            if (detail) host.await(activity) { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.detail_action_delete)) }
        } catch (error: Throwable) {
            host.capture("input-failure")
            throw error
        } finally {
            host.main { keyboard?.hide(); activity.setContent {}; activity.finish() }
        }
    }
}