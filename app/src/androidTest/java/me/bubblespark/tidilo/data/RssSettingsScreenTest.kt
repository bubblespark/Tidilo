package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
    @Test fun aiCoreRadioIsDisabledWhileCheckingOrUnavailableAndRechecksOnReopen(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val shown = mutableStateOf(true)
        val mode = mutableStateOf(me.bubblespark.tidilo.config.AiAnalysisMode.AICORE)
        val firstCheck = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val mounted = java.util.concurrent.atomic.AtomicBoolean(false)
        var calls = 0
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                if (shown.value) {
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        mounted.set(true)
                        onDispose { mounted.set(false) }
                    }
                    me.bubblespark.tidilo.ui.screens.settings.AiAnalysisDialog(mode.value,
                        onSelect = { mode.value = it; shown.value = false }, onDismiss = { shown.value = false },
                        checkAvailable = { if (++calls == 1) firstCheck.await() else true })
                }
            } } }
            node("Availability pending") { it.text?.toString()?.startsWith("Checking AICore") == true }
            fun radio(label: String): AccessibilityNodeInfo? {
                var candidate = find { it.text?.toString() == label }
                while (candidate != null && !candidate.isCheckable) candidate = candidate.parent
                return candidate
            }
            assertFalse(requireNotNull(radio("AICore")).isEnabled)
            for (label in listOf("AICore", "Disable")) {
                val bounds = android.graphics.Rect()
                requireNotNull(radio(label)).getBoundsInScreen(bounds)
                assertTrue("$label row must be at least 48dp high",
                    bounds.height() >= kotlin.math.ceil(48f * context.resources.displayMetrics.density).toInt())
            }
            assertTrue(requireNotNull(radio("Disable")).isEnabled)
            assertFalse(requireNotNull(radio("AICore")).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            firstCheck.complete(false)
            node("Unavailable explanation") { it.text?.toString() == "AICore is currently unavailable." }
            assertFalse(requireNotNull(radio("AICore")).isEnabled)
            assertEquals(me.bubblespark.tidilo.config.AiAnalysisMode.AICORE, mode.value)
            clickText("Disable")
            withTimeout(3000) { while (mounted.get()) delay(20) }
            instrumentation.runOnMainSync { shown.value = true }
            withTimeout(5000) { while (calls != 2 || radio("AICore")?.isEnabled != true) delay(30) }
            clickText("AICore")
            withTimeout(3000) { while (mode.value != me.bubblespark.tidilo.config.AiAnalysisMode.AICORE) delay(20) }
            assertEquals(2, calls)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun globalAiDisableExplainsDirectFallbackWithoutRestrictingFeedChoices(): Unit = runBlocking {
        val previous = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            for (original in listOf(null, me.bubblespark.tidilo.data.db.RssFeedEntity("feed", "https://fixture.example/feed", "Feed", mode = "AI"))) {
                var chosen: String? = null
                instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                    me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor(original, onDismiss = {}) { _, mode, _, _ -> chosen = mode }
                } } }
                node("Direct fallback guidance") { it.text?.toString()?.contains("Feeds will be imported in Direct mode automatically") == true }
                // Compose removes ACTION_CLICK from a currently selected radio option.
                if (original != null) clickCurrent { it.text?.toString() == "Direct" }
                clickCurrent { it.text?.toString() == "AI Analyze" }
                withTimeout(3000) {
                    while (true) {
                        var radio = find { it.text?.toString() == "AI Analyze" }
                        while (radio != null && !radio.isCheckable) radio = radio.parent
                        if (radio?.isChecked == true) {
                            assertTrue(radio.isEnabled)
                            break
                        }
                        delay(30)
                    }
                }
                if (original != null) {
                    clickCurrent { it.text?.toString() == "Save" }
                    withTimeout(3000) { while (chosen == null) delay(30) }
                    assertEquals("AI", chosen)
                }
            }
            instrumentation.runOnMainSync {
                me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.AICORE)
            }
            delay(300)
            assertNull(find { it.text?.toString()?.contains("Feeds will be imported in Direct mode automatically") == true })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(previous)
        }
    }

    @Test fun settingsAiAnalysisDialogUpdatesModeAndDismissDoesNotChangeIt(): Unit = runBlocking {
        val previous = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.SettingsScreen(onNavigateBack = {}, onNavigateToGenerateContentRequest = {},
                    checkAiCoreAvailable = { true })
            } } }
            node("Fresh Settings screen") { it.text?.toString() == "Settings" }
            repeat(4) {
                if (find { it.text?.toString() == "AI Analysis" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    instrumentation.waitForIdleSync()
                    delay(300)
                }
            }
            var hasButtonRole = false
            instrumentation.runOnMainSync {
                fun hasRole(node: SemanticsNode): Boolean =
                    (node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button &&
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "AI Analysis" } == true) ||
                        node.children.any(::hasRole)
                fun inspect(view: android.view.View) {
                    if (view is ViewRootForTest && hasRole(view.semanticsOwner.rootSemanticsNode)) hasButtonRole = true
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) inspect(view.getChildAt(index))
                }
                inspect(activity.window.decorView)
            }
            assertTrue("AI Analysis setting must expose the Button semantic role", hasButtonRole)
            clickText("AI Analysis")
            clickText("Close")
            awaitAiSettingCard()
            assertEquals(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE, me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value)
            clickText("AI Analysis")
            node("Mode dialog open") { it.text?.toString() == "Close" }
            clickText("AICore")
            awaitAiSettingCard()
            assertEquals(me.bubblespark.tidilo.config.AiAnalysisMode.AICORE, me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value)
            clickText("AI Analysis")
            node("Mode dialog reopened") { it.text?.toString() == "Close" }
            clickText("Disable")
            assertEquals(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE, me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(previous)
        }
    }

    @Test fun initialFetchDialogSeparatesTimingAndShowsMobileNotice(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var fetched = false
        var later = false
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssInitialFetchDialog(true, true, false,
                    onFetch = { fetched = true }, onLater = { later = true })
            } } }
            node("Mobile data notice") { it.text?.toString()?.contains("This fetch will use mobile data") == true }
            assertNull(find { it.text?.toString()?.contains("permanently deleted") == true })
            click(node("Fetch later") { it.text?.toString() == "Later" })
            assertTrue(later)
            assertFalse(fetched)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Suppress("DEPRECATION")
    @Test fun automaticallyDisabledFeedCardExplainsXmlFailure(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val feed = me.bubblespark.tidilo.data.db.RssFeedEntity("failed", "https://fixture.example/feed", "Failed feed",
            enabled = false, error = "XML_FETCH_FAILED")
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(SummaryLoad.Ready(listOf(feed)), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _, _, _, _ -> }, onDelete = {},
                    onFetch = {}, onDismissError = {})
            } } }
            repeat(8) {
                if (find { it.text?.toString()?.contains("Could not fetch feed XML") == true } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            node("Persistent XML failure") { it.text?.toString()?.contains("Could not fetch feed XML. This feed was automatically disabled.") == true }
            node("Recovery instruction") { it.text?.toString()?.contains("Enable this feed to try again") == true }
            var enabled: AccessibilityNodeInfo? = node("Enabled switch") { it.contentDescription?.toString() == "Enabled" }
            while (enabled != null && !enabled.isCheckable) enabled = enabled.parent
            assertNotNull(enabled)
            assertFalse(enabled!!.isChecked)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun resubscriptionDialogExplainsCycleDeletionAndRequiresExplicitChoice(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var accepted = false
        var dismissed = false
        val limit = mutableStateOf<Int?>(30)
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssRegistrationDialog(100, limit.value, false,
                    onConfirm = { accepted = true }, onDismiss = { dismissed = true })
            } } }
            node("Existing count") { it.text?.toString()?.contains("100 saved articles") == true }
            node("Cycle permanent deletion warning") { it.text?.toString()?.contains("scheduled or explicit fetch") == true }
            assertFalse(accepted)
            click(node("Cancel registration") { it.text?.toString() == "Cancel" })
            assertTrue(dismissed)
            assertFalse(accepted)
            instrumentation.runOnMainSync { limit.value = null }
            node("Unlimited explanation") { it.text?.toString()?.contains("no automatic count deletion") == true }
            click(node("Confirm registration") { it.text?.toString() == "Register" })
            assertTrue(accepted)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
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

    private suspend fun clickText(label: String) {
        try {
            clickCurrent { it.text?.toString() == label }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val chain = mutableListOf<String>()
            var item = find { it.text?.toString() == label }
            while (item != null) {
                chain.add("text=${item.text} clickable=${item.isClickable} checked=${item.isChecked} enabled=${item.isEnabled}")
                item = item.parent
            }
            throw AssertionError("Cannot click $label: ${chain.joinToString("; ")}", e)
        }
    }

    /** A transient null active window is not proof that a closing dialog has disappeared. */
    private suspend fun awaitAiSettingCard() = withTimeout(5000) {
        while (true) {
            var item = find { it.text?.toString() == "AI Analysis" }
            while (item != null && !item.isClickable) item = item.parent
            if (item?.isEnabled == true && find { it.text?.toString() == "Close" } == null) return@withTimeout
            delay(30)
        }
    }

    /** Recomposition can detach an accessibility node between discovery and its action. */
    private suspend fun clickCurrent(predicate: (AccessibilityNodeInfo) -> Boolean) = withTimeout(3000) {
        while (true) {
            var target = find(predicate)
            while (target != null && !target.isClickable) target = target.parent
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
            delay(50)
        }
    }

    @Test fun feedCardShowsCompactMetadataAndBottomRightSwitch(): Unit = runBlocking {
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
                if (find { it.contentDescription?.toString() == "Enabled" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            val mode = node("Compact mode") { it.text?.toString() == "Direct" }
            val retention = node("Compact retention") { it.text?.toString() == "Unlimited" }
            val modeBounds = android.graphics.Rect()
            val retentionBounds = android.graphics.Rect()
            mode.getBoundsInScreen(modeBounds)
            retention.getBoundsInScreen(retentionBounds)
            assertEquals(modeBounds.centerY(), retentionBounds.centerY())
            assertTrue(retentionBounds.left > modeBounds.right)
            node("Fetch error") { it.text?.toString() == "HTTP 500" }
            assertNull(find { it.text?.toString()?.startsWith("Last attempt:") == true || it.text?.toString()?.startsWith("Last success:") == true })
            var toggle: AccessibilityNodeInfo? = node("Enabled switch") { it.contentDescription?.toString() == "Enabled" }
            while (toggle != null && !toggle.isCheckable) toggle = toggle.parent
            assertNotNull(toggle)
            val toggleBounds = android.graphics.Rect()
            val deleteBounds = android.graphics.Rect()
            toggle!!.getBoundsInScreen(toggleBounds)
            node("Delete action") { it.text?.toString() == "Delete" }.getBoundsInScreen(deleteBounds)
            assertTrue(toggleBounds.left > deleteBounds.right)
            assertTrue(kotlin.math.abs(deleteBounds.centerY() - toggleBounds.centerY()) <= 1)
            node("On state") { it.text?.toString() == "On" }
            click(toggle)
            withTimeout(3000) { while (feed.value.enabled) delay(20) }
            node("Off state") { it.text?.toString() == "Off" }
            instrumentation.runOnMainSync { feed.value = feed.value.copy(error = null, retentionLimit = 30) }
            node("Numeric maximum") { it.text?.toString() == "Max 30" }
            delay(250)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue96-compact-feed.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
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
        val wifi = mutableStateOf<Boolean?>(null)
        var savedMode: String? = null
        var savedLimit: Int? = 1
        var savedUrl: String? = null
        var backed = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MaterialTheme {
                    RssSettingsContent(SummaryLoad.Ready(emptyList()), settings.value, false, null, wifi.value,
                        onBack = { backed = true }, onRetry = {}, onSettings = { settings.value = it },
                        onFeed = { _, url, mode, limit, _ -> savedUrl = url; savedMode = mode; savedLimit = limit },
                        onDelete = {}, onFetch = {}, onDismissError = {})
                } }
            }
            node("Initial midnight") { it.text?.toString() == "00:00" }
            assertNull(find { it.text?.toString() == "Cannot fetch: Wi-Fi is not connected." })
            instrumentation.runOnMainSync { wifi.value = false }
            val blocked = node("Wi-Fi blocked status") { it.text?.toString() == "Cannot fetch: Wi-Fi is not connected." }
            val timeBounds = android.graphics.Rect()
            val blockedBounds = android.graphics.Rect()
            node("Time control") { it.text?.toString() == "00:00" }.getBoundsInScreen(timeBounds)
            blocked.getBoundsInScreen(blockedBounds)
            assertTrue("Warning appears beside the time", blockedBounds.left > timeBounds.right)
            val blockedScreenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue91-wifi-blocked.png").outputStream().use {
                blockedScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { blockedScreenshot.recycle() }
            val wifiLabel = node("Wi-Fi label") { it.text?.toString() == "Fetch only over Wi-Fi" }
            assertTrue(wifiLabel.parent.isCheckable)
            click(wifiLabel)
            withTimeout(3000) { while (settings.value.wifiOnly) delay(20) }
            withTimeout(3000) { while (find { it.text?.toString() == "Cannot fetch: Wi-Fi is not connected." } != null) delay(20) }
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
            instrumentation.waitForIdleSync()
            clickCurrent { it.text?.toString() == "Save" }
            withTimeout(3000) { while (savedUrl == null) delay(20) }
            assertEquals("https://example.com/feed.xml", savedUrl)
            assertEquals("DIRECT", savedMode)
            assertNull(savedLimit)
            click(node("Back") { it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.common_back) })
            assertTrue(backed)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
