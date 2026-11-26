package me.bubblespark.tidilo.data

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.debug.CrashLogEntry
import me.bubblespark.tidilo.data.debug.CrashLogManager
import me.bubblespark.tidilo.ui.screens.settings.CrashLogDetailsContent
import me.bubblespark.tidilo.ui.screens.settings.CrashLogsSection
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsDebugCrashLogTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val emptyText = context.getString(R.string.debug_crash_logs_empty)
    private val clearText = context.getString(R.string.debug_crash_logs_clear)

    @Before
    fun setUp() {
        CrashLogManager.clearLogs(context)
    }

    @After
    fun tearDown() {
        CrashLogManager.clearLogs(context)
    }

    @Test fun emptySectionShowsEmptyTextWithoutClear() {
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                CrashLogsSection(crashLogs = emptyList(), onLogClick = {}, onClearClick = {})
            }
        }
        composeRule.onNodeWithText(emptyText).assertExists("Empty state text must be displayed when no crash logs exist")
        composeRule.onNodeWithText(clearText).assertDoesNotExist()
    }

    @Test fun listRendersEntriesAndForwardsClicks() {
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
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                CrashLogsSection(
                    crashLogs = listOf(entry1, entry2),
                    onLogClick = { clickedEntry = it },
                    onClearClick = { clearClicked = true }
                )
            }
        }
        composeRule.onNode(hasText("IllegalStateException") and hasClickAction()).assertExists()
        composeRule.onNode(hasText("NullPointerException") and hasClickAction()).performClick()
        assertEquals("Clicking NPE card must invoke callback with entry1", "test-npe-1", clickedEntry?.id)
        composeRule.onNode(hasText(clearText) and hasClickAction()).performClick()
        assertTrue("Clear button must trigger onClearClick callback", clearClicked)
    }

    @Test fun detailsShowMetadataAndStackTrace() {
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
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) { CrashLogDetailsContent(entry = detailedEntry) }
        }
        composeRule.onNodeWithText("java.lang.IllegalArgumentException", useUnmergedTree = true)
            .assertExists("Full exception class name must be shown in dialog content")
        composeRule.onNodeWithText("App: 0.5.25 (56) | Google Pixel 8 (Android 14 (API 34))", useUnmergedTree = true)
            .assertExists("App version, device model, and OS version must be displayed")
        composeRule.onNodeWithText(detailedEntry.stackTrace, useUnmergedTree = true)
            .assertExists("Full stack trace must be displayed in dialog content")
    }

    @Test fun debugScreenOpensAndClearsARecordedCrash() {
        CrashLogManager.recordCrash(context, Thread.currentThread(),
            UnsupportedOperationException("End to end integration test crash"))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                SettingsDebugScreen(onNavigateBack = {}, checkAiCoreReadiness = { AiCoreReadiness.Failed })
            }
        }
        composeRule.onNode(hasText("UnsupportedOperationException") and hasClickAction()).performScrollTo().performClick()

        val dialogClose = composeRule.onNode(hasText(context.getString(R.string.debug_crash_logs_close)) and hasClickAction())
        dialogClose.performClick()
        dialogClose.assertDoesNotExist()

        composeRule.onNode(hasText(clearText) and hasClickAction()).performScrollTo().performClick()
        val confirmTitle = composeRule.onNodeWithText(context.getString(R.string.debug_crash_logs_clear_confirm_title))
        confirmTitle.assertExists("Clear confirmation dialog title must appear")
        composeRule.onNode(hasText(clearText) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        confirmTitle.assertDoesNotExist()
        composeRule.onNodeWithText(emptyText).assertExists("Empty state text must appear on screen after clearing logs")
    }
}
