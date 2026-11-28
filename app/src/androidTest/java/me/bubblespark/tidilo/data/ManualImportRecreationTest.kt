package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.notifications.NotificationCategory
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ManualImportRecreationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var oldDiscoverer: ((String) -> DomainIconManager.HtmlDiscoveryResult)? = null
    private var oldDownloader: ((String) -> DomainIconManager.DownloadResult)? = null
    private var oldOgDownloader: (suspend (String) -> ByteArray?)? = null
    private var oldBlurEnabled: Boolean = true

    @Before
    fun setUp() {
        oldDiscoverer = DomainIconManager.htmlDiscoverer
        oldDownloader = DomainIconManager.httpDownloader
        oldOgDownloader = OgImageManager.imageDownloader
        oldBlurEnabled = AppConfig.overlayBlurEnabled.value
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
        AppConfig.setOverlayBlurEnabled(false)
    }

    @After
    fun tearDown() {
        AppConfig.setOverlayBlurEnabled(oldBlurEnabled)
        DomainIconManager.htmlDiscoverer = oldDiscoverer
        DomainIconManager.httpDownloader = oldDownloader
        OgImageManager.imageDownloader = oldOgDownloader
    }

    @Test
    fun delayedSaveSurvivesRecreationAndDefersNotificationNavigation(): Unit = runBlocking {
        val requestReceived = CompletableDeferred<Unit>()
        val sendResponse = CompletableDeferred<Unit>()
        val title = "Manual import recreation fixture"
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val url = "http://127.0.0.1:${server.localPort}/manual-import-recreation"
            val servePage = launch(Dispatchers.IO) {
                try {
                    server.accept().use { connection ->
                        connection.soTimeout = 5000
                        val request = connection.getInputStream().bufferedReader()
                        while (!request.readLine().isNullOrEmpty()) { /* Read through HTTP headers. */ }
                        requestReceived.complete(Unit)
                        sendResponse.await()
                        val body = "<html><head><title>$title</title></head><body><article><h1>$title</h1></article></body></html>"
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                        connection.getOutputStream().use { it.write((headers + body).toByteArray()) }
                    }
                } catch (_: SocketException) {
                    // Expected if server.close() interrupts accept() on teardown.
                }
            }
            val bookmarks = AppDatabase.getInstance(context).bookmarkDao()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var activity: MainActivity
                    scenario.onActivity { activity = it }
                    val launchIntent = activity.intent

                    val importConfirmText = activity.getString(R.string.import_dialog_confirm)
                    val savingText = activity.getString(R.string.import_dialog_saving)
                    val notificationTitle = activity.getString(R.string.settings_notification_title)
                    val openSavedText = activity.getString(R.string.import_open_saved_page)
                    val collectionActionDesc = activity.getString(R.string.bookmark_collection_action)
                    val collectionAssignTitle = activity.getString(R.string.collection_assign_title)

                    fun contentDescMatcher(desc: String) = SemanticsMatcher("contentDesc=$desc") {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(desc) == true
                    }

                    // The floating toolbar's FAB opens Manual Import.
                    composeRule.waitUntil(8000) {
                        composeRule.onAllNodes(hasTestTag("floating_nav_save"), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.onNode(hasTestTag("floating_nav_save")).performClick()

                    composeRule.waitUntil(8000) {
                        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.onNode(hasSetTextAction()).performTextReplacement(url)

                    composeRule.waitUntil(8000) {
                        composeRule.onAllNodes(hasText(importConfirmText), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.onNodeWithText(importConfirmText).performClick()

                    withTimeout(5000) { requestReceived.await() }
                    scenario.recreate()
                    scenario.onActivity { activity = it }

                    composeRule.waitUntil(8000) {
                        composeRule.onAllNodes(hasText(savingText), useUnmergedTree = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    try {
                        NotificationCoordinator.getInstance(context)
                            .buildNotification(NotificationCategory.IMPORT_ERROR, "Manual import fixture").contentIntent.send()
                        composeRule.waitForIdle()
                        assertTrue(
                            composeRule.onAllNodes(hasText(notificationTitle), useUnmergedTree = true)
                                .fetchSemanticsNodes().isEmpty()
                        )

                        sendResponse.complete(Unit)
                        composeRule.waitUntil(8000) {
                            composeRule.onAllNodes(hasText(openSavedText), useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        composeRule.onNodeWithText(openSavedText).performClick()

                        composeRule.waitUntil(8000) {
                            composeRule.onAllNodes(contentDescMatcher(collectionActionDesc), useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                        composeRule.onNodeWithContentDescription(collectionActionDesc).performClick()

                        composeRule.waitUntil(8000) {
                            composeRule.onAllNodes(hasText(collectionAssignTitle), useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }

                        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        composeRule.waitUntil(5000) {
                            composeRule.onAllNodes(hasText(collectionAssignTitle), useUnmergedTree = true)
                                .fetchSemanticsNodes().isEmpty()
                        }

                        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        composeRule.waitUntil(8000) {
                            composeRule.onAllNodes(hasText(notificationTitle), useUnmergedTree = true)
                                .fetchSemanticsNodes().isNotEmpty()
                        }
                    } finally {
                        // ActivityScenario matches lifecycle events by the launch Intent.
                        instrumentation.runOnMainSync { activity.intent = launchIntent }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    sendResponse.complete(Unit)
                    servePage.cancel()
                    runCatching { server.close() }
                    bookmarks.getBookmarkByUrl(url)?.let { bookmarks.deleteById(it.id) }
                }
            }
        }
    }
}
