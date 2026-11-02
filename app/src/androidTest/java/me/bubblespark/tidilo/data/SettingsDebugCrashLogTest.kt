package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.debug.CrashLogEntry
import me.bubblespark.tidilo.data.debug.CrashLogManager
import me.bubblespark.tidilo.ui.screens.settings.CrashLogDetailsContent
import me.bubblespark.tidilo.ui.screens.settings.CrashLogsSection
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsDebugCrashLogTest {
    private val host = HeaderTestHost()

    @Before
    fun setUp() {
        CrashLogManager.clearLogs(host.context)
        runCatching {
            host.instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${host.context.packageName} android.permission.POST_NOTIFICATIONS"
            )
        }
    }

    @After
    fun tearDown() {
        CrashLogManager.clearLogs(host.context)
    }

    @Test
    fun crashLogCompleteVerificationLifecycle(): Unit = runBlocking {
        val activity = host.launch()
        try {
            // =========================================================================
            // Stage 1: Empty state verification
            // =========================================================================
            host.main {
                activity.setContent {
                    TidiloTheme {
                        CrashLogsSection(
                            crashLogs = emptyList(),
                            onLogClick = {},
                            onClearClick = {}
                        )
                    }
                }
            }

            val emptyText = host.context.getString(R.string.debug_crash_logs_empty)
            val emptyNode = host.await(activity) { host.text(it, emptyText) }
            assertNotNull("Empty state text must be displayed when no crash logs exist", emptyNode)

            val clearText = host.context.getString(R.string.debug_crash_logs_clear)
            var clearNodeWhenEmpty: SemanticsNode? = null
            host.main {
                clearNodeWhenEmpty = host.nodes(activity).firstOrNull { host.text(it, clearText) }
            }
            assertNull("Clear button must not be present when log list is empty", clearNodeWhenEmpty)

            // =========================================================================
            // Stage 2: List rendering and callback verification with multiple crashes
            // =========================================================================
            val entry1 = CrashLogEntry(
                id = "test-npe-1",
                timestamp = System.currentTimeMillis(),
                exceptionClass = "java.lang.NullPointerException",
                message = "Target view reference is null",
                stackTrace = "java.lang.NullPointerException: Target view reference is null\n\tat me.test.View.bind(View.kt:42)",
                threadName = "main"
            )
            val entry2 = CrashLogEntry(
                id = "test-ise-2",
                timestamp = System.currentTimeMillis() - 1000,
                exceptionClass = "java.lang.IllegalStateException",
                message = "Coroutine job was already completed",
                stackTrace = "java.lang.IllegalStateException: Coroutine job was already completed\n\tat me.test.Job.run(Job.kt:88)",
                threadName = "DefaultDispatcher-worker-1"
            )

            var clickedEntry: CrashLogEntry? = null
            var clearClicked = false

            host.main {
                activity.setContent {
                    TidiloTheme {
                        CrashLogsSection(
                            crashLogs = listOf(entry1, entry2),
                            onLogClick = { clickedEntry = it },
                            onClearClick = { clearClicked = true }
                        )
                    }
                }
            }

            val npeNode = host.await(activity) { host.text(it, "NullPointerException") }
            val iseNode = host.await(activity) { host.text(it, "IllegalStateException") }
            assertNotNull("NullPointerException item must be rendered in the list", npeNode)
            assertNotNull("IllegalStateException item must be rendered in the list", iseNode)

            invokeClick(npeNode)
            assertEquals("Clicking NPE card must invoke callback with entry1", "test-npe-1", clickedEntry?.id)

            val clearButtonInList = host.await(activity) { host.text(it, clearText) }
            invokeClick(clearButtonInList)
            assertTrue("Clear button must trigger onClearClick callback", clearClicked)

            // =========================================================================
            // Stage 3: Dialog content metadata & stacktrace display verification
            // =========================================================================
            val detailedEntry = CrashLogEntry(
                id = "detailed-crash-id",
                timestamp = 1700000000000L,
                exceptionClass = "java.lang.IllegalArgumentException",
                message = "Required parameter 'apiKey' was missing",
                stackTrace = "java.lang.IllegalArgumentException: Required parameter 'apiKey' was missing\n\tat me.bubblespark.tidilo.Service.init(Service.kt:15)",
                threadName = "background-thread-2",
                appVersion = "0.5.25 (56)",
                osVersion = "Android 14 (API 34)",
                deviceModel = "Google Pixel 8"
            )

            host.main {
                activity.setContent {
                    TidiloTheme {
                        CrashLogDetailsContent(entry = detailedEntry)
                    }
                }
            }

            val fullClassNode = host.await(activity) { host.text(it, "java.lang.IllegalArgumentException") }
            assertNotNull("Full exception class name must be shown in dialog content", fullClassNode)

            val metadataNode = host.await(activity) {
                host.text(it, "App: 0.5.25 (56) | Google Pixel 8 (Android 14 (API 34))")
            }
            assertNotNull("App version, device model, and OS version must be displayed", metadataNode)

            val stackTraceNode = host.await(activity) {
                host.text(it, detailedEntry.stackTrace)
            }
            assertNotNull("Full stack trace must be displayed in dialog content", stackTraceNode)

            // =========================================================================
            // Stage 4: Full SettingsDebugScreen End-to-End integration verification
            // =========================================================================
            CrashLogManager.clearLogs(host.context)
            val integrationException = UnsupportedOperationException("End to end integration test crash")
            CrashLogManager.recordCrash(host.context, Thread.currentThread(), integrationException)

            host.main {
                activity.setContent {
                    TidiloTheme {
                        SettingsDebugScreen(onNavigateBack = {})
                    }
                }
            }

            // Scroll down to Crash Logs section
            val crashLogsTitle = host.context.getString(R.string.debug_crash_logs_title)
            var crashSectionFound = false
            for (i in 1..15) {
                if (host.nodes(activity).any { host.text(it, crashLogsTitle) }) {
                    crashSectionFound = true
                    host.scroll(activity, 800f)
                    delay(150)
                    break
                }
                host.scroll(activity, 1000f)
                delay(100)
            }
            assertTrue("Crash Logs section title must be found after scrolling", crashSectionFound)

            var crashItem: SemanticsNode? = null
            for (i in 1..5) {
                crashItem = host.nodes(activity).firstOrNull { host.text(it, "UnsupportedOperationException") }
                if (crashItem != null) break
                host.scroll(activity, 400f)
                delay(100)
            }
            val targetCrashItem = requireNotNull(crashItem) { "UnsupportedOperationException must appear in crash list" }

            // Tap crash item to open details dialog
            invokeClick(targetCrashItem)

            // Verify dialog opened and close it
            val dialogCloseText = host.context.getString(R.string.debug_crash_logs_close)
            val dialogCloseButton = host.await(activity) { host.text(it, dialogCloseText) }
            assertNotNull("Details dialog close button must be found", dialogCloseButton)
            invokeClick(dialogCloseButton)

            // Wait until dialog dismisses
            withTimeout(4000) {
                while (true) {
                    var dialogVisible = false
                    host.main {
                        dialogVisible = host.nodes(activity).any { host.text(it, dialogCloseText) }
                    }
                    if (!dialogVisible) break
                    delay(50)
                }
            }

            // Tap Clear button on screen
            val screenClearButton = host.await(activity) { host.text(it, clearText) }
            invokeClick(screenClearButton)

            // Confirm clear in confirmation dialog
            val confirmTitle = host.context.getString(R.string.debug_crash_logs_clear_confirm_title)
            val confirmDialogTitle = host.await(activity) { host.text(it, confirmTitle) }
            assertNotNull("Clear confirmation dialog title must appear", confirmDialogTitle)

            // In confirmation dialog, confirm button has text clearText and is located in the dialog window
            var confirmButtonInDialog: SemanticsNode? = null
            host.main {
                confirmButtonInDialog = host.nodes(activity).filter { host.text(it, clearText) }.lastOrNull()
            }
            invokeClick(requireNotNull(confirmButtonInDialog))

            // Wait until confirmation dialog dismisses
            withTimeout(4000) {
                while (true) {
                    var confirmDialogVisible = false
                    host.main {
                        confirmDialogVisible = host.nodes(activity).any { host.text(it, confirmTitle) }
                    }
                    if (!confirmDialogVisible) break
                    delay(50)
                }
            }

            // Empty state should now appear
            val finalEmptyNode = host.await(activity) { host.text(it, emptyText) }
            assertNotNull("Empty state text must appear on screen after clearing logs", finalEmptyNode)
        } finally {
            host.main {
                try { activity.finish() } catch (_: Exception) {}
            }
        }
    }

    private fun invokeClick(original: SemanticsNode) = host.main {
        var target: SemanticsNode? = original
        while (target != null && target.config.getOrNull(SemanticsActions.OnClick) == null) {
            target = target.parent
        }
        val clickable = requireNotNull(target) { "No clickable parent for node: $original" }
        assertTrue(clickable.config[SemanticsActions.OnClick].action?.invoke() == true)
    }
}
