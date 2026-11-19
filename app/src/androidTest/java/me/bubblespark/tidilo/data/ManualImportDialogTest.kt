package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import android.content.ClipData
import android.content.res.Configuration
import android.provider.Settings
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.data.notifications.NotificationCategory
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import me.bubblespark.tidilo.ui.screens.importscreen.ManualImportState
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale

class ManualImportDialogTest {
    @Test fun pasteAndImeUseTheSameSubmissionGuardAsImport(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val submitted = mutableListOf<String>()
        val keyboardSetting = "show_ime_with_hard_keyboard"
        val resolver = host.context.contentResolver
        val originalKeyboardSetting = Settings.Secure.getString(resolver, keyboardSetting)
        fun setKeyboardSetting(value: String?) {
            val automation = host.instrumentation.uiAutomation
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.putString(resolver, keyboardSetting, value))
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
        val clipboard = object : Clipboard {
            @Suppress("OVERRIDE_DEPRECATION")
            override val nativeClipboard get() = error("This test provides a Compose clipboard")
            override suspend fun getClipEntry() = ClipEntry(ClipData.newPlainText("Test URL", "paste.invalid:8443/path?q=1#top"))
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        }
        try {
            setKeyboardSetting("1")
            host.main { activity.setContent { MaterialTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) { ImportUrlDialog({}, { submitted += it }) }
            } } }
            val paste = host.await(activity) { host.text(it, activity.getString(R.string.import_dialog_paste)) }
            host.main {
                val button = generateSequence(paste) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val url = "https://paste.invalid:8443/path?q=1#top"
            host.await(activity) { host.text(it, activity.getString(R.string.import_dialog_resolved_url, url)) }
            val input = host.await(activity) { it.config.getOrNull(SemanticsActions.OnImeAction) != null }
            host.main { assertTrue(input.config[SemanticsActions.OnClick].action?.invoke() == true) }
            withTimeout(5000) {
                var visible = false
                while (!visible) {
                    host.main {
                        visible = WindowInspector.getGlobalWindowViews().any {
                            it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                        }
                    }
                    if (!visible) delay(20)
                }
            }
            val confirm = host.await(activity) { host.text(it, activity.getString(R.string.import_dialog_confirm)) }
            host.main {
                assertTrue(input.boundsInWindow.height > 0)
                assertTrue(confirm.boundsInWindow.height > 0)
                val button = generateSequence(confirm) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(input.config[SemanticsActions.OnImeAction].action?.invoke() == true)
                button.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals(listOf(url), submitted)
            }
        } finally {
            setKeyboardSetting(originalKeyboardSetting)
            host.main { activity.finish() }
        }
    }

    @Test fun delayedSaveSurvivesRecreationAndDefersNotificationNavigation(): Unit = runBlocking {
        val host = HeaderTestHost()
        val requestReceived = CompletableDeferred<Unit>()
        val sendResponse = CompletableDeferred<Unit>()
        val title = "Manual import recreation fixture"
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}/manual-import-recreation"
            val servePage = launch(Dispatchers.IO) {
                server.accept().use { connection ->
                    connection.soTimeout = 5000
                    val request = connection.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) { /* Read through the HTTP headers. */ }
                    requestReceived.complete(Unit)
                    sendResponse.await()
                    val body = "<html><head><title>$title</title></head><body><article><h1>$title</h1></article></body></html>"
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().use { it.write((headers + body).toByteArray()) }
                }
            }
            val bookmarks = AppDatabase.getInstance(host.context).bookmarkDao()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var activity: MainActivity
                    scenario.onActivity { activity = it }
                    val launchIntent = activity.intent
                    suspend fun click(description: String, predicate: (SemanticsNode) -> Boolean) {
                        val label = awaitNode(host, activity, description, predicate)
                        host.main {
                            val action = generateSequence(label) { it.parent }.first {
                                it.config.getOrNull(SemanticsActions.OnClick) != null
                            }
                            assertTrue(action.config[SemanticsActions.OnClick].action?.invoke() == true)
                        }
                    }
                    click("Library menu") { host.description(it, activity.getString(R.string.navigation_open)) }
                    click("Manual Import menu item") { host.text(it, activity.getString(R.string.navigation_manual_import)) }
                    val editor = awaitNode(host, activity, "URL editor") { it.config.getOrNull(SemanticsActions.SetText) != null }
                    host.main { editor.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(url)) }
                    click("Import button") { host.text(it, activity.getString(R.string.import_dialog_confirm)) }
                    withTimeout(5000) { requestReceived.await() }
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    awaitNode(host, activity, "saving after recreation") {
                        host.text(it, activity.getString(R.string.import_dialog_saving))
                    }
                    try {
                        NotificationCoordinator.getInstance(host.context)
                            .buildNotification(NotificationCategory.IMPORT_ERROR, "Manual import fixture").contentIntent.send()
                        host.instrumentation.waitForIdleSync()
                        host.main {
                            assertFalse(host.nodes(activity).any { host.text(it, activity.getString(R.string.settings_notification_title)) })
                        }
                        sendResponse.complete(Unit)
                        click("Open saved page") { host.text(it, activity.getString(R.string.import_open_saved_page)) }
                        click("Collection action") { host.description(it, activity.getString(R.string.bookmark_collection_action)) }
                        awaitNode(host, activity, "collection sheet") { host.text(it, activity.getString(R.string.collection_assign_title)) }
                        host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        withTimeout(5000) {
                            var shown = true
                            while (shown) {
                                host.main { shown = host.nodes(activity).any { host.text(it, activity.getString(R.string.collection_assign_title)) } }
                                if (shown) delay(20)
                            }
                        }
                        host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        awaitNode(host, activity, "deferred notification screen") {
                            host.text(it, activity.getString(R.string.settings_notification_title))
                        }
                    } finally {
                        // ActivityScenario matches lifecycle events by the launch Intent.
                        host.main { activity.intent = launchIntent }
                    }
                }
            } finally {
                sendResponse.complete(Unit)
                server.close()
                servePage.cancel()
                bookmarks.getBookmarkByUrl(url)?.let { bookmarks.deleteById(it.id) }
            }
        }
    }

    @Test fun draftSurvivesSavedStateRestoration(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var showDialog by mutableStateOf(true)
        var renderedShow = true
        var registry = SaveableStateRegistry(null) { true }
        try {
            host.main { activity.setContent { MaterialTheme {
                if (showDialog) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ImportUrlDialog({}, {}) }
                val shown = showDialog
                SideEffect { renderedShow = shown }
            } } }
            val input = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main {
                input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("https://draft.invalid/article"))
            }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://draft.invalid/article" }
            var saved: Map<String, List<Any?>> = emptyMap()
            host.main { saved = registry.performSave(); showDialog = false }
            withTimeout(3000) { while (renderedShow) delay(20) }
            host.main { registry = SaveableStateRegistry(saved) { true }; showDialog = true }
            withTimeout(3000) { while (!renderedShow) delay(20) }
            host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main {
                assertTrue("Recreated dialog must retain the URL draft",
                    host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://draft.invalid/article" })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun confirmationSubmitsUrlOnce(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val submissions = mutableListOf<String>()
        try {
            host.main { activity.setContent { MaterialTheme { ImportUrlDialog({}, { submissions += it }) } } }
            val input = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("ftp://submit.invalid/article")) }
            host.await(activity) { host.text(it, activity.getString(R.string.import_dialog_error_invalid)) }
            host.main { input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("submit.invalid/article")) }
            host.await(activity) {
                host.text(it, activity.getString(R.string.import_dialog_resolved_url, "https://submit.invalid/article"))
            }
            val confirm = host.await(activity) { node ->
                host.text(node, host.context.getString(R.string.import_dialog_confirm)) &&
                    generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
                        ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
            }
            host.main {
                var target = confirm
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                target.config[SemanticsActions.OnClick].action?.invoke()
                target.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals(listOf("https://submit.invalid/article"), submissions)
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun savingExposesIndeterminateProgressAndBlocksBackInBothThemes(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var locale by mutableStateOf(Locale.ENGLISH)
        var dismissals = 0
        var state by mutableStateOf<ManualImportState>(ManualImportState.Saving)
        val url = "https://saved.invalid/article"
        try {
            host.main { activity.setContent {
                val configuration = remember(locale) { Configuration(activity.resources.configuration).apply { setLocale(locale) } }
                val localized = remember(configuration) { activity.createConfigurationContext(configuration) }
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalResources provides localized.resources) {
                    TidiloTheme(theme = theme, blurEnabled = false) {
                        ImportUrlDialog(onDismiss = { dismissals++ }, onConfirm = {}, state = state)
                    }
                }
            } }
            for ((preset, language, label) in listOf(
                Triple(AppTheme.DEFAULT, Locale.ENGLISH, "Saving page…"),
                Triple(AppTheme.MODERN, Locale.JAPANESE, "ページを保存しています…")
            )) {
                host.main { theme = preset; locale = language }
                host.instrumentation.waitForIdleSync()
                awaitNode(host, activity, "$preset progress") {
                    it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
                }
                awaitNode(host, activity, "$preset saving label") { host.text(it, label) }
                host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                host.instrumentation.waitForIdleSync()
                host.main {
                    assertEquals(0, dismissals)
                    assertTrue(host.nodes(activity).none { it.config.getOrNull(SemanticsActions.SetText) != null })
                }
            }
            host.main { state = ManualImportState.Finished(ImportOutcome.SavedDuplicate("bookmark-id", url)) }
            awaitNode(host, activity, "Japanese saved page action") { host.text(it, "保存済みのページを開く") }
        } finally { host.main { activity.finish() } }
    }

    private suspend fun awaitNode(host: HeaderTestHost, activity: androidx.activity.ComponentActivity,
        description: String, predicate: (SemanticsNode) -> Boolean): SemanticsNode = try {
        host.await(activity, predicate)
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Missing $description", timeout)
    }
}
