package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.TagSelectionScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Requires a real software IME; emulator keyboard settings are prepared by the runner. */
class EdgeToEdgeInputTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity
    private var keyboard: SoftwareKeyboardController? = null

    @Test fun tagSelectionViewportAvoidsIme() {
        mount { TagSelectionScreen(ViewModelProvider(activity)[LibraryViewModel::class.java], {}) }
        checkViewport()
    }
    @Test fun debugViewportAvoidsIme() {
        mount { SettingsDebugScreen({}, checkAiCoreReadiness = { AiCoreReadiness.Failed }) }
        checkViewport()
    }
    @Test fun parameterViewportAvoidsIme() {
        mount { GenerateContentRequestScreen({}) }
        checkViewport()
    }

    @Test fun detailMemoHidesOnlyLowerActionsWhileImeIsVisible(): Unit = runBlocking {
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(activity.applicationContext).also { it.awaitReady() } }
        try {
            val id = "inset-memo"
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Inset memo", "https://inset.invalid/$id", "Fixture summary",
                    createdAt = 1, updatedAt = 1, note = "A memo that stays available while editing."))
            }
            val viewModel = library.viewModel()
            mount { BookmarkDetailScreen(id, viewModel, {}) }
            checkViewport(detail = true)
        } finally {
            caches.close()
            library.close()
        }
    }

    @Test fun optionalParameterFieldsAvoidImeWithoutChangingPreferences() {
        val prefs = activity.getSharedPreferences("tidilo_generate_content_request_prefs", android.content.Context.MODE_PRIVATE)
        val snapshot = prefs.all.toMap()
        try {
            check(prefs.edit().putBoolean("topKEnabled", true).commit())
            var generation by mutableIntStateOf(0)
            // The screen reads its preferences once per mount; a new key remounts it within one setContent.
            mount { key(generation) { GenerateContentRequestScreen({}) } }
            checkViewport(lastField = true)
            check(prefs.edit().putBoolean("cachedContextNameEnabled", true).putString("cachedContextName", "Fixture\nSecond line").commit())
            composeRule.runOnUiThread { generation++ }
            composeRule.waitForIdle()
            checkViewport()
        } finally {
            val edit = prefs.edit().clear()
            snapshot.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") val strings = value as Set<String>; edit.putStringSet(key, strings) }
            } }
            check(edit.commit())
        }
    }

    @Test fun debugMetadataFilterUsesTheSameImeSafeViewport() {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val html = "<html><head><title>Inset parser fixture</title>" +
            (0..29).joinToString("") { "<meta name='fixture-$it' content='value-$it'>" } +
            "</head><body><p>Local fixture content for metadata input verification.</p></body></html>"
        val worker = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            mount { SettingsDebugScreen({}, checkAiCoreReadiness = { AiCoreReadiness.Failed }) }
            composeRule.awaitNode(hasSetTextAction())
            composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasSetTextAction())
            composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("http://127.0.0.1:${server.localPort}/fixture")
            composeRule.clickNode(composeRule.awaitNode(hasText("Parse Web Elements")))
            composeRule.clickNode(composeRule.awaitNode(textStartsWith("All Tags (")))
            composeRule.awaitNode(textStartsWith("Filter Meta Tags ("))
            val filter = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).last()
            composeRule.waitUntil(5_000) { activity.hasWindowFocus() }
            composeRule.runOnUiThread {
                check(filter.config[SemanticsActions.RequestFocus].action?.invoke() == true)
                keyboard?.show()
            }
            composeRule.waitUntil(5_000) { imeVisible() }
            composeRule.waitForIdle()
            val field = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).last().boundsInWindow
            composeRule.runOnUiThread {
                val bottom = activity.window.decorView.height - imeBottom()
                assertTrue("Metadata filter $field must be visible above IME $bottom", field.height > 0 && field.bottom <= bottom + 1f)
            }
        } finally {
            server.close(); worker.join(1000)
            composeRule.runOnUiThread { keyboard?.hide() }
        }
    }

    private fun mount(content: @Composable () -> Unit) {
        // MainActivity draws edge to edge; the IME and system-bar insets depend on it.
        composeRule.runOnUiThread { activity.enableEdgeToEdge() }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                keyboard = LocalSoftwareKeyboardController.current
                content()
            }
        }
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun textStartsWith(prefix: String) = SemanticsMatcher("text starts with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
    }

    private fun imeVisible() =
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun imeBottom() =
        requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView)).getInsets(WindowInsetsCompat.Type.ime()).bottom

    private val editable = SemanticsMatcher("visible editable field") {
        it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0
    }
    private val viewport = SemanticsMatcher("scroll viewport") {
        it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
            it.config.getOrNull(SemanticsProperties.EditableText) == null && it.boundsInWindow.height > 100
    }

    private fun checkViewport(detail: Boolean = false, lastField: Boolean = false) {
        composeRule.awaitNode(hasSetTextAction())
        if (nodes(editable).isEmpty()) composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasSetTextAction())
        if (lastField) {
            composeRule.onAllNodes(hasScrollAction()).onFirst().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            composeRule.waitForIdle()
        }
        val candidates = nodes(editable).filter { it.config.getOrNull(SemanticsProperties.Disabled) == null }
        val target = if (lastField) candidates.last() else candidates.first()
        // The IME ignores show requests from a window that does not have focus yet.
        composeRule.waitUntil(5_000) { activity.hasWindowFocus() }
        composeRule.runOnUiThread {
            check(target.config[SemanticsActions.RequestFocus].action?.invoke() == true)
            keyboard?.show()
        }
        composeRule.waitUntil(5_000) { imeVisible() }
        composeRule.waitForIdle()
        val viewportBounds = nodes(viewport).first().boundsInWindow
        val field = nodes(editable).first { it.config.getOrNull(SemanticsProperties.Focused) == true }.boundsInWindow
        var bottom = 0
        composeRule.runOnUiThread { bottom = activity.window.decorView.height - imeBottom() }
        assertTrue("Scroll viewport $viewportBounds must end above IME top $bottom", viewportBounds.bottom <= bottom + 1f)
        assertTrue("Focused field $field must fit above IME $bottom", field.bottom <= bottom + 1f)
        if (detail) {
            assertTrue(nodes(hasContentDescription(activity.getString(R.string.detail_action_delete))).isEmpty())
            assertTrue(nodes(hasContentDescription(activity.getString(R.string.common_back))).isNotEmpty())
        }
        composeRule.runOnUiThread { keyboard?.hide() }
        composeRule.waitUntil(5_000) { !imeVisible() }
        // The IME reports hidden when its hide animation starts; the viewport settles about 400 ms later.
        composeRule.waitUntil("Viewport must expand after IME dismissal", 5_000) {
            nodes(viewport).first().boundsInWindow.bottom >= activity.window.decorView.height - 1f
        }
        if (detail) composeRule.awaitNode(hasContentDescription(activity.getString(R.string.detail_action_delete)))
    }
}
