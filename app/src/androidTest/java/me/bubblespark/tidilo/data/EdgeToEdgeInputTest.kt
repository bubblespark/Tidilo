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

    @Test fun optionalParameterFieldsAvoidImeWithoutChangingPreferences(): Unit = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("tidilo_generate_content_request_prefs", android.content.Context.MODE_PRIVATE)
        val snapshot = prefs.all.toMap()
        try {
            check(prefs.edit().putBoolean("topKEnabled", true).commit())
            verifyViewport(lastField = true) { _, _ -> GenerateContentRequestScreen({}) }
            check(prefs.edit().putBoolean("cachedContextNameEnabled", true).putString("cachedContextName", "Fixture\nSecond line").commit())
            verifyViewport { _, _ -> GenerateContentRequestScreen({}) }
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

    @Test fun debugMetadataFilterUsesTheSameImeSafeViewport(): Unit = runBlocking {
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
        val host = HeaderTestHost()
        val activity = host.launch()
        var keyboard: SoftwareKeyboardController? = null
        fun click(node: androidx.compose.ui.semantics.SemanticsNode) {
            var target = node
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
            check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        try {
            host.main { activity.setContent { MaterialTheme {
                keyboard = LocalSoftwareKeyboardController.current
                SettingsDebugScreen({})
            } } }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            host.scroll(activity, 2000f)
            delay(300)
            host.main {
                val input = host.nodes(activity).first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                check(input.config[SemanticsActions.SetText].action?.invoke(androidx.compose.ui.text.AnnotatedString("http://127.0.0.1:${server.localPort}/fixture")) == true)
            }
            val parse = host.await(activity) { host.text(it, "Parse Web Elements") }
            host.main { click(parse) }
            val tab = host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("All Tags (") } == true }
            host.main { click(tab) }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.startsWith("Filter Meta Tags (") } == true }
            host.main {
                val field = host.nodes(activity).last { it.config.getOrNull(SemanticsProperties.EditableText) != null }
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
            delay(500)
            host.main {
                val bottom = activity.window.decorView.height - requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView)).getInsets(WindowInsetsCompat.Type.ime()).bottom
                val field = host.nodes(activity).last { it.config.getOrNull(SemanticsProperties.EditableText) != null }.boundsInWindow
                assertTrue("Metadata filter $field must be visible above IME $bottom", field.height > 0 && field.bottom <= bottom + 1f)
            }
        } finally {
            server.close(); worker.join(1000)
            host.main { keyboard?.hide(); activity.setContent {}; activity.finish() }
        }
    }

    private fun verifyViewport(detail: Boolean = false, lastField: Boolean = false, screen: @Composable (HeaderTestHost, androidx.activity.ComponentActivity) -> Unit): Unit = runBlocking {
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
            withTimeout(5000) {
                while (!visibleField) {
                    host.scroll(activity, 700f); delay(100)
                    host.main { visibleField = host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.EditableText) != null && it.boundsInWindow.height > 0 } }
                }
            }
            if (lastField) { host.scroll(activity, 100000f); delay(300) }
            host.main {
                val candidates = host.nodes(activity).filter {
                    it.config.getOrNull(SemanticsProperties.EditableText) != null &&
                        it.config.getOrNull(SemanticsProperties.Disabled) == null && it.boundsInWindow.height > 0
                }
                val field = if (lastField) candidates.last() else candidates.first()
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
            host.main { keyboard?.hide(); activity.setContent {}; activity.viewModelStore.clear(); activity.finish() }
        }
    }
}