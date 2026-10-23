package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.SummaryLoad
import org.junit.Assert.*
import org.junit.Test

class RssSettingsScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun node(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        android.util.Log.i("RssUiTest", "Looking for $label")
        return try { withTimeout(8000) {
            var result = find(predicate)
            while (result == null) { delay(50); result = find(predicate) }
            result
        } } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            fun dump(node: AccessibilityNodeInfo, depth: Int = 0) {
                android.util.Log.e("RssUiTest", "${" ".repeat(depth)}${node.className} text=${node.text} description=${node.contentDescription} checkable=${node.isCheckable}")
                for (i in 0 until node.childCount) node.getChild(i)?.let { dump(it, depth + 1) }
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { dump(it) }
            throw AssertionError("Missing $label", timeout)
        }
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    @Test fun feedCardLabelsEnabledAndErrorsReplaceTimestamps(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val feed = mutableStateOf(me.bubblespark.tidilo.data.db.RssFeedEntity("feed", "https://example.com/feed", "Example",
            retentionLimit = null, error = "HTTP 500", lastAttemptAt = 100, lastSuccessAt = 50))
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(SummaryLoad.Ready(listOf(feed.value)), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _, _, _, _ -> }, onDelete = {},
                    onFetch = {}, onDismissError = {}, onEnabled = { id, value ->
                        assertEquals("feed", id); feed.value = feed.value.copy(enabled = value)
                    })
            } } }
            node("Simple queue guidance") { it.text?.toString() == "Maximum AI queue: 0 items" }
            repeat(8) {
                if (find { it.text?.toString() == "Enabled" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            node("Mode label") { it.text?.toString() == "Import Mode: Direct" }
            node("Retention label") { it.text?.toString() == "Max saved items: Unlimited" }
            node("Fetch error") { it.text?.toString() == "HTTP 500" }
            assertNull(find { it.text?.toString()?.startsWith("Last attempt:") == true || it.text?.toString()?.startsWith("Last success:") == true })
            click(node("Enabled checkbox label") { it.text?.toString() == "Enabled" })
            withTimeout(3000) { while (feed.value.enabled) delay(20) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun settingsRssNavigationAndBack(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            click(node("Settings button") { it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.title_settings) })
            node("Settings heading") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.settings_title) }
            repeat(10) {
                if (find { it.text?.toString() == "RSS" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
            }
            click(node("RSS entry") { it.text?.toString() == "RSS" })
            node("RSS daily time heading") { it.text?.toString() == "Daily fetch time" }
            node("Wi-Fi policy") { it.text?.toString() == "Fetch only over Wi-Fi" }
            delay(500)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue91-rss-navigation.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            node("Returned RSS entry") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_subtitle) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun scheduleWifiAndDirectFeedEditorExposeIndependentControls(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val settings = mutableStateOf(RssSettings())
        var savedMode: String? = null
        var savedLimit: Int? = 1
        var savedUrl: String? = null
        var backed = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MaterialTheme {
                    RssSettingsContent(SummaryLoad.Ready(emptyList()), settings.value, false, null, false,
                        onBack = { backed = true }, onRetry = {}, onSettings = { settings.value = it },
                        onFeed = { _, url, mode, limit, _ -> savedUrl = url; savedMode = mode; savedLimit = limit },
                        onDelete = {}, onFetch = {}, onDismissError = {})
                } }
            }
            node("Initial midnight") { it.text?.toString() == "00:00" }
            node("Wi-Fi wait status") { it.text?.toString() == "Waiting for Wi-Fi" }
            val wifiLabel = node("Wi-Fi label") { it.text?.toString() == "Fetch only over Wi-Fi" }
            assertTrue(wifiLabel.parent.isCheckable)
            click(wifiLabel)
            withTimeout(3000) { while (settings.value.wifiOnly) delay(20) }
            click(node("Time picker") { it.text?.toString() == "00:00" })
            click(node("Cancel time") { it.text?.toString() == "Cancel" })
            assertEquals(0, settings.value.hour)
            delay(500)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue91-rss-settings.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
            click(node("Add feed") { it.text?.toString() == "Add feed" })
            node("Import Mode section") { it.text?.toString() == "Import Mode" }
            node("Max saved items section") { it.text?.toString() == "Max saved items" }
            node("Default retention") { it.isEditable && it.text?.toString() == "30" }
            val input = node("Feed URL input") { it.isEditable && it.text?.toString() != "30" }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "https://example.com/feed.xml")
            }))
            val unlimitedLabel = node("Unlimited") { it.text?.toString() == "Unlimited" }
            assertTrue(unlimitedLabel.parent.isCheckable)
            click(unlimitedLabel)
            click(node("Save feed") { it.text?.toString() == "Save" })
            withTimeout(3000) { while (savedUrl == null) delay(20) }
            assertEquals("https://example.com/feed.xml", savedUrl)
            assertEquals("DIRECT", savedMode)
            assertNull(savedLimit)
            click(node("Back") { it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.common_back) })
            assertTrue(backed)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
