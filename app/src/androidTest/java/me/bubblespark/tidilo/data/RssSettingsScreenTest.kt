package me.bubblespark.tidilo.data

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class RssSettingsScreenTest {
    @Test fun emptyTriggerSectionIsSeparateAndEditableWhileOff(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val settings = mutableStateOf(RssSettings())
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(emptyList()), settings.value, false, null, true,
                    onBack = {}, onRetry = {}, onSettings = { settings.value = it },
                    onFeed = { _, _ -> }, onDelete = {}, onFetch = {}, onDismissError = {})
            } } }
            node("Unset trigger card") { it.text?.toString() == "Not set" }
            node("Empty counter") { it.text?.toString() == "0 / 3" }
            val heading = node("Trigger heading") { it.text?.toString() == "Trigger time" }
            val dailyBounds = android.graphics.Rect()
            val triggerBounds = android.graphics.Rect()
            node("Daily heading") { it.text?.toString() == "Daily fetch time" }.getBoundsInScreen(dailyBounds)
            heading.getBoundsInScreen(triggerBounds)
            assertTrue(triggerBounds.top > dailyBounds.bottom)
            assertNull(find { it.text?.toString() == "Up to 3 times per day" })
            click(node("Add trigger") { it.contentDescription?.toString() == "Add trigger time" })
            clickText("Cancel")
            assertTrue(settings.value.triggerTimes.isEmpty())
            click(node("Add trigger") { it.contentDescription?.toString() == "Add trigger time" })
            clickText("Add")
            withTimeout(3000) { while (settings.value.triggerTimes.isEmpty()) delay(20) }
            assertEquals(listOf(java.time.LocalTime.of(8, 0)), settings.value.triggerTimes)
            assertFalse(settings.value.dailyEnabled)
            assertTrue(settings.value.wifiOnly)
            click(node("Delete trigger") { it.contentDescription?.toString() == "Remove trigger time 08:00" })
            node("Trigger delete confirmation") { it.text?.toString() == "Delete the trigger time 08:00?" }
            assertEquals(listOf(java.time.LocalTime.of(8, 0)), settings.value.triggerTimes)
            instrumentation.waitForIdleSync()
            delay(400)
            val confirmation = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue160-delete-confirmation.png").outputStream().use {
                confirmation.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { confirmation.recycle() }
            clickText("Cancel")
            assertEquals(listOf(java.time.LocalTime.of(8, 0)), settings.value.triggerTimes)
            click(node("Delete trigger again") { it.contentDescription?.toString() == "Remove trigger time 08:00" })
            clickText("Delete")
            node("Restored unset") { it.text?.toString() == "Not set" }
            assertTrue(settings.value.triggerTimes.isEmpty())
            instrumentation.runOnMainSync { settings.value = settings.value.copy(triggerTimes = listOf(java.time.LocalTime.of(8, 0))) }
            click(node("Delete disappearing trigger") { it.contentDescription?.toString() == "Remove trigger time 08:00" })
            node("Pending deletion") { it.text?.toString() == "Delete the trigger time 08:00?" }
            instrumentation.runOnMainSync { settings.value = settings.value.copy(triggerTimes = listOf(java.time.LocalTime.NOON), wifiOnly = false) }
            withTimeout(3000) { while (find { it.text?.toString() == "Delete the trigger time 08:00?" } != null) delay(30) }
            assertEquals(listOf(java.time.LocalTime.NOON), settings.value.triggerTimes)
            assertFalse(settings.value.wifiOnly)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun duplicateAndLimitDoNotMutateSettings(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val settings = mutableStateOf(RssSettings(listOf(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(18, 30))))
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(emptyList()), settings.value, false, null, true,
                    onBack = {}, onRetry = {}, onSettings = { settings.value = it },
                    onFeed = { _, _ -> }, onDelete = {}, onFetch = {}, onDismissError = {})
            } } }
            click(node("Add trigger") { it.contentDescription?.toString() == "Add trigger time" })
            node("Duplicate feedback") { it.text?.toString() == "This time is already set." }
            var save: AccessibilityNodeInfo? = node("Duplicate add") { it.text?.toString() == "Add" }
            while (save != null && !save.isClickable) save = save.parent
            assertFalse(requireNotNull(save).isEnabled)
            clickText("Cancel")
            instrumentation.runOnMainSync { settings.value = settings.value.copy(triggerTimes = listOf(
                java.time.LocalTime.of(8, 0), java.time.LocalTime.NOON, java.time.LocalTime.of(18, 30))) }
            node("Full count") { it.text?.toString() == "3 / 3" }
            var add: AccessibilityNodeInfo? = node("Full add") { it.contentDescription?.toString() == "Add trigger time" }
            while (add != null && !add.isClickable) add = add.parent
            assertFalse(requireNotNull(add).isEnabled)
            click(node("Delete noon") { it.contentDescription?.toString() == "Remove trigger time 12:00" })
            clickText("Delete")
            node("Reduced count") { it.text?.toString() == "2 / 3" }
            assertTrue(node("Reenabled add") { it.contentDescription?.toString() == "Add trigger time" }.isEnabled)
            click(node("Edit time") { it.contentDescription?.toString() == "Edit trigger time 18:30" })
            clickText("Save")
            assertEquals(listOf(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(18, 30)), settings.value.triggerTimes)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun manualFetchWaitsWithoutProgressAndReenablesAfterCompletion(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val status = mutableStateOf(me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus.Waiting)
        var fetches = 0
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(listOf(me.bubblespark.tidilo.data.db.RssFeedEntity(
                    "waiting-test", "https://example.invalid/feed", "Example"))), RssSettings(), false, null, false,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                    onDelete = {}, onFetch = { fetches++ }, onDismissError = {}, manualFetch = status.value)
            } } }
            val waiting = node("Waiting status") { it.text?.toString() == "Waiting to fetch" }
            var button: AccessibilityNodeInfo? = waiting
            while (button != null && !button.isClickable) button = button.parent
            assertFalse(requireNotNull(button).isEnabled)
            assertNull(find { it.className?.toString() == "android.widget.ProgressBar" })
            instrumentation.runOnMainSync { status.value = me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus.Running }
            node("Running progress") { it.className?.toString() == "android.widget.ProgressBar" }
            instrumentation.runOnMainSync { status.value = me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus.Idle }
            clickText("Fetch now")
            withTimeout(3000) { while (fetches == 0) delay(20) }
            assertEquals(1, fetches)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun feedListScrollsBehindTheFixedRssHeader(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            val feeds = (1..12).map { me.bubblespark.tidilo.data.db.RssFeedEntity(
                "header-$it", "https://header-$it.invalid/feed", "Feed $it") }
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(feeds), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                    onDelete = {}, onFetch = {}, onDismissError = {})
            } } }
            host.await(activity) { host.text(it, "Daily fetch") && it.boundsInRoot.height > 0f }
            val list = host.await(activity) {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null &&
                    it.config.getOrNull(SemanticsProperties.CollectionInfo) != null
            }
            instrumentation.waitForIdleSync()
            host.await(activity) { host.text(it, "RSS") && it.boundsInRoot.height > 0f }
            var headerBounds = androidx.compose.ui.geometry.Rect.Zero
            host.main {
                headerBounds = host.nodes(activity).first { host.text(it, "RSS") && it.boundsInRoot.height > 0f }.boundsInRoot
                assertTrue("The scrolling viewport must extend behind the header", list.boundsInRoot.top < headerBounds.top)
            }
            host.main { assertTrue(list.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action?.invoke(0f, 500f) == true) }
            delay(500)
            var before = 0f
            host.main { before = list.config[SemanticsProperties.VerticalScrollAxisRange].value() }
            val start = android.os.SystemClock.uptimeMillis()
            val x = headerBounds.center.x
            val y = headerBounds.center.y
            fun gesture(action: Int, eventY: Float) = host.main {
                val event = android.view.MotionEvent.obtain(start, android.os.SystemClock.uptimeMillis(), action, x, eventY, 0)
                try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            gesture(android.view.MotionEvent.ACTION_DOWN, y)
            repeat(12) { step ->
                android.os.SystemClock.sleep(16)
                gesture(android.view.MotionEvent.ACTION_MOVE, y + (step + 1) * 16f)
            }
            gesture(android.view.MotionEvent.ACTION_UP, y + 192f)
            instrumentation.waitForIdleSync()
            host.main {
                val after = list.config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("A drag starting on the header must scroll the list: $before -> $after", after < before)
            }
            host.await(activity) { host.text(it, "RSS") }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun longPressRequestsConfirmationBeforeDeletingFeed(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var deleted: String? = null
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(listOf(me.bubblespark.tidilo.data.db.RssFeedEntity(
                    "delete-test", "https://example.com/feed", "Example"))), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                    onDelete = { deleted = it }, onFetch = {}, onDismissError = {})
            } } }
            repeat(8) {
                if (find { it.text?.toString() == "Example" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            var card: AccessibilityNodeInfo? = node("Feed title") { it.text?.toString() == "Example" }
            while (card != null && !card.isLongClickable) card = card.parent
            assertTrue(requireNotNull(card).performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            node("Delete confirmation") { it.text?.toString()?.startsWith("Stop fetching") == true }
            assertNull(deleted)
            clickText("Cancel")
            assertNull(deleted)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun manualFetchShowsProgressAndPreventsDuplicateRequests(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(listOf(me.bubblespark.tidilo.data.db.RssFeedEntity(
                    "progress-test", "https://example.com/feed", "Example"))), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                    onDelete = {}, onFetch = {}, onDismissError = {}, manualFetch = me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus.Running)
            } } }
            val label = node("Fetch now") { it.text?.toString() == "Fetch now" }
            var button: AccessibilityNodeInfo? = label
            while (button != null && !button.isClickable) button = button.parent
            assertFalse(requireNotNull(button).isEnabled)
            node("Fetch progress") { it.className?.toString() == "android.widget.ProgressBar" }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun dailyToggleUpdatesSettingWithoutDisablingManualFetch(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val settings = mutableStateOf(RssSettings(dailyEnabled = true))
        var fetched = false
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                RssSettingsContent(StorageLoad.Ready(listOf(me.bubblespark.tidilo.data.db.RssFeedEntity(
                    "daily-test", "https://example.com/feed", "Example"))), settings.value, false, null, true,
                    onBack = {}, onRetry = {}, onSettings = { settings.value = it },
                    onFeed = { _, _ -> }, onDelete = {}, onFetch = { fetched = true }, onDismissError = {})
            } } }
            clickText("Daily fetch")
            withTimeout(3000) { while (settings.value.dailyEnabled) delay(20) }
            clickText("Fetch now")
            withTimeout(3000) { while (!fetched) delay(20) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun importShowsErrorsWithoutAiWaitingSections(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .putExtra(me.bubblespark.tidilo.data.notifications.NotificationCoordinator.EXTRA_DESTINATION, "import")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            node("Notification header") { it.text?.toString() == context.getString(me.bubblespark.tidilo.R.string.settings_notification_title) }
            assertNull(find { it.text?.toString()?.startsWith("Ready - RSS (") == true })
            assertNull(find { it.text?.toString()?.startsWith("Ready (") == true })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun legacyRssFeedEditorHasNoAiModePicker(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            val original = me.bubblespark.tidilo.data.db.RssFeedEntity(
                "feed", "https://fixture.example/feed", "Feed", mode = "AI"
            )
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor(original, onDismiss = {}) { _ -> }
            } } }
            node("Save action") { it.text?.toString() == "Save" }
            assertNull(find { it.text?.toString() == "AI Analyze" })
            assertNull(find { it.text?.toString() == "Direct" })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
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
                RssSettingsContent(StorageLoad.Ready(listOf(feed)), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> }, onDelete = {},
                    onFetch = {}, onDismissError = {})
            } } }
            repeat(8) {
                if (find { it.text?.toString()?.contains("Could not fetch feed XML") == true } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            node("Persistent XML failure") { it.text?.toString()?.contains("Could not fetch feed XML. This feed was automatically disabled.") == true }
            assertNull(find { it.text?.toString()?.contains("Enable this feed to try again") == true })
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
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssRegistrationDialog(100, false,
                    onConfirm = { accepted = true }, onDismiss = { dismissed = true })
            } } }
            node("Existing count") { it.text?.toString()?.contains("100 saved articles") == true }
            node("Cycle permanent deletion warning") { it.text?.toString()?.contains("daily maintenance") == true }
            assertFalse(accepted)
            click(node("Cancel registration") { it.text?.toString() == "Cancel" })
            assertTrue(dismissed)
            assertFalse(accepted)
            node("Protected articles explanation") { it.text?.toString()?.contains("Collection") == true }
            click(node("Confirm registration") { it.text?.toString() == "Register" })
            assertTrue(accepted)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun feedEditorShowsAgePolicyWithoutCountControls(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssFeedEditor(null, onDismiss = {}) { _ -> }
            } } }
            node("Fixed age policy") { it.text?.toString()?.contains("10 days") == true }
            assertNull(find { it.text?.toString() == "Unlimited" })
            assertNull(find { it.text?.toString()?.contains("10–500") == true })
            assertNull(find { it.isEditable && it.text?.toString() == "30" })
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
                RssSettingsContent(StorageLoad.Ready(listOf(feed.value)), RssSettings(), false, null, true,
                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> }, onDelete = {},
                    onFetch = {}, onDismissError = {}, onEnabled = { id, value ->
                        assertEquals("feed", id); feed.value = feed.value.copy(enabled = value)
                    })
            } } }
            assertNull(find { it.text?.toString()?.startsWith("Maximum AI queue:") == true })
            repeat(8) {
                if (find { it.text?.toString() == "Direct" } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            val mode = node("Compact mode") { it.text?.toString() == "Direct" }
            val retention = node("Compact retention") { it.text?.toString() == "Unread 10d · Read 30d" }
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
            toggle!!.getBoundsInScreen(toggleBounds)
            assertTrue(toggleBounds.left > retentionBounds.right)
            click(toggle)
            withTimeout(3000) { while (feed.value.enabled) delay(20) }
            instrumentation.runOnMainSync { feed.value = feed.value.copy(error = null, retentionLimit = 30) }
            node("Fixed retention") { it.text?.toString() == "Unread 10d · Read 30d" }
            click(node("Feed title") { it.text?.toString() == "Example" })
            node("Delete in editor") { it.text?.toString() == "Delete feed" }
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
            click(node("Open navigation") { it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.navigation_open) })
            click(node("Settings entry") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.title_settings) })
            node("Settings content") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.settings_language_title) }
            repeat(10) {
                if (find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_subtitle) } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
            }
            click(node("RSS settings entry") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_subtitle) })
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
        val settings = mutableStateOf(RssSettings(listOf(java.time.LocalTime.MIDNIGHT), dailyEnabled = true))
        val wifi = mutableStateOf<Boolean?>(null)
        var savedUrl: String? = null
        var backed = false
        try {
            instrumentation.runOnMainSync {
                activity.setContent { MaterialTheme {
                    RssSettingsContent(StorageLoad.Ready(emptyList()), settings.value, false, null, wifi.value,
                        onBack = { backed = true }, onRetry = {}, onSettings = { settings.value = it },
                        onFeed = { _, url -> savedUrl = url },
                        onDelete = {}, onFetch = {}, onDismissError = {})
                } }
            }
            node("Initial midnight") { it.text?.toString() == "00:00" }
            assertNull(find { it.text?.toString() == "Cannot fetch: Wi-Fi is not connected." })
            instrumentation.runOnMainSync { wifi.value = false }
            val blocked = node("Wi-Fi blocked status") { it.text?.toString() == "Cannot fetch: Wi-Fi is not connected." }
            val wifiBounds = android.graphics.Rect()
            val blockedBounds = android.graphics.Rect()
            node("Wi-Fi control") { it.text?.toString() == "Fetch only over Wi-Fi" }.getBoundsInScreen(wifiBounds)
            blocked.getBoundsInScreen(blockedBounds)
            assertTrue("Warning remains inside the policy card, above Wi-Fi", blockedBounds.bottom <= wifiBounds.top)
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
            assertEquals(listOf(java.time.LocalTime.MIDNIGHT), settings.value.triggerTimes)
            delay(500)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { File(context.getExternalFilesDir(null), "issue91-rss-settings.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
            click(node("Add feed") { it.contentDescription?.toString() == "Add feed" })
            node("Fixed retention explanation") { it.text?.toString()?.contains("10 days") == true }
            assertNull(find { it.text?.toString() == "Import Mode" })
            val input = node("Feed URL input") { it.isEditable && it.text?.toString() != "30" }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "https://example.com/feed.xml")
            }))
            assertNull(find { it.text?.toString() == "Unlimited" })
            clickCurrent { it.text?.toString() == "Save" }
            withTimeout(3000) { while (savedUrl == null) delay(20) }
            assertEquals("https://example.com/feed.xml", savedUrl)
            click(node("Back") { it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.common_back) })
            assertTrue(backed)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun enablingDailyFetchAndCompletingRegistrationOfferOneTimeNotificationRationaleWithoutBlocking(): Unit = runBlocking {
        val notificationPrefs = context.getSharedPreferences(
            me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val hadRequestedKey = notificationPrefs.contains(
            me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
        )
        val previousRequestedValue = notificationPrefs.getBoolean(
            me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
            false
        )
        val previousAppLanguage = me.bubblespark.tidilo.config.AppConfig.currentLanguage.value
        val previousAppResourcesConfig = Configuration(context.applicationContext.resources.configuration)
        val rssPreferences = me.bubblespark.tidilo.data.rss.RssPreferences.getInstance(context)
        rssPreferences.initialize()
        val previousRssSettings = rssPreferences.state.value
        assertTrue(
            notificationPrefs.edit()
                .remove(me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED)
                .commit()
        )
        rssPreferences.save(previousRssSettings.copy(dailyEnabled = false))

        var activity: ComponentActivity? = null
        var inMemoryDb: me.bubblespark.tidilo.data.db.AppDatabase? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ComponentActivity
            node("Cold launch Library") {
                it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.navigation_open)
            }
            assertFalse(
                "Cold launch must not request or mark notification permission as requested",
                notificationPrefs.getBoolean(
                    me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                    false
                )
            )

            // Navigate via the real MainActivity host to confirm RssSettingsScreen mounts with LocalActivityResultRegistryOwner.
            click(node("Open navigation") {
                it.contentDescription?.toString() == activity.getString(me.bubblespark.tidilo.R.string.navigation_open)
            })
            click(node("Settings entry") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.title_settings)
            })
            node("Settings content") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.settings_language_title)
            }
            repeat(10) {
                if (find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_subtitle) } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
            }
            click(node("RSS settings entry") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_subtitle)
            })
            node("RSS daily time heading") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_schedule)
            }
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            withTimeout(3000) {
                while (find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch) } != null) {
                    delay(20)
                }
            }

            val dailyModel = me.bubblespark.tidilo.ui.screens.settings.RssSettingsViewModel(activity.application)
            var permissionLaunches = 0
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen(
                            onNavigateBack = {},
                            model = dailyModel,
                            isNotificationPermissionGranted = { false },
                            onLaunchNotificationPermission = { permissionLaunches++ }
                        )
                    }
                }
            }
            node("Daily fetch switch") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch) }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch))
            withTimeout(3000) { while (!dailyModel.settings.value.dailyEnabled) delay(20) }
            node("Notification rationale title") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title)
            }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_later))
            withTimeout(3000) {
                while (find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title) } != null ||
                    !notificationPrefs.getBoolean(
                        me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                        false
                    )
                ) {
                    delay(20)
                }
            }
            assertEquals(0, permissionLaunches)
            assertTrue("Dismissing notification rationale must not block Daily fetch", dailyModel.settings.value.dailyEnabled)

            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch))
            withTimeout(3000) { while (dailyModel.settings.value.dailyEnabled) delay(20) }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch))
            withTimeout(3000) { while (!dailyModel.settings.value.dailyEnabled) delay(20) }
            delay(300)
            assertNull(
                "Notification rationale must appear only once",
                find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title) }
            )

            assertTrue(
                notificationPrefs.edit()
                    .putBoolean(me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                    .commit()
            )
            val initialModel = me.bubblespark.tidilo.ui.screens.settings.RssSettingsViewModel(
                activity.application,
                androidx.lifecycle.SavedStateHandle(mapOf("initialFeed" to "initial-test-feed"))
            )
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen(
                            onNavigateBack = {},
                            model = initialModel,
                            isNotificationPermissionGranted = { false },
                            onLaunchNotificationPermission = {
                                assertTrue(
                                    "KEY_NOTIFICATION_PERMISSION_REQUESTED must already be persisted before launching OS prompt",
                                    notificationPrefs.getBoolean(
                                        me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                                        false
                                    )
                                )
                                assertFalse(
                                    "Rationale state must already be cleared before launching OS prompt",
                                    initialModel.notificationRationale.value
                                )
                                permissionLaunches++
                            }
                        )
                    }
                }
            }
            node("Initial fetch dialog") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_initial_title) }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_later))
            node("Notification rationale after registration") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title)
            }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_allow))
            withTimeout(3000) {
                while (permissionLaunches == 0 ||
                    find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title) } != null ||
                    !notificationPrefs.getBoolean(
                        me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                        false
                    )
                ) {
                    delay(20)
                }
            }
            assertEquals(1, permissionLaunches)

            assertTrue(
                notificationPrefs.edit()
                    .putBoolean(me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                    .commit()
            )
            val testDb = androidx.room.Room.inMemoryDatabaseBuilder(
                context,
                me.bubblespark.tidilo.data.db.AppDatabase::class.java
            ).build().also { inMemoryDb = it }
            val fetchNowFeed = me.bubblespark.tidilo.data.db.RssFeedEntity(
                "fetch-now-test",
                "https://xml.invalid/feed",
                "Fetch Now Feed"
            )
            testDb.rssDao().putFeed(fetchNowFeed)
            val enqueuedInitial = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Boolean>>()
            val testRepo = me.bubblespark.tidilo.data.rss.RssRepository(
                context = context,
                db = testDb,
                scheduleChanged = {},
                enqueueInitial = { id, op, mobile -> enqueuedInitial.add(Triple(id, op, mobile)) }
            )
            val fetchNowModel = me.bubblespark.tidilo.ui.screens.settings.RssSettingsViewModel(
                activity.application,
                androidx.lifecycle.SavedStateHandle(mapOf("initialFeed" to fetchNowFeed.id)),
                testRepo,
                networkStateOverride = { true to true }
            )
            var deniedDispatches = 0
            val deniedRegistryOwner = object : androidx.activity.result.ActivityResultRegistryOwner {
                override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                        input: I,
                        options: androidx.core.app.ActivityOptionsCompat?
                    ) {
                        assertTrue(
                            "KEY_NOTIFICATION_PERMISSION_REQUESTED must already be persisted before launching OS prompt",
                            notificationPrefs.getBoolean(
                                me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                                false
                            )
                        )
                        assertFalse(
                            "Rationale state must already be cleared before launching OS prompt",
                            fetchNowModel.notificationRationale.value
                        )
                        permissionLaunches++
                        deniedDispatches++
                        dispatchResult(requestCode, false)
                    }
                }
            }
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(
                            androidx.activity.compose.LocalActivityResultRegistryOwner provides deniedRegistryOwner
                        ) {
                            me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen(
                                onNavigateBack = {},
                                model = fetchNowModel,
                                isNotificationPermissionGranted = { false }
                            )
                        }
                    }
                }
            }
            node("Fetch now dialog") { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_initial_title) }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_fetch_now))
            node("Notification rationale after Fetch now") {
                it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title)
            }
            assertEquals("Initial fetch must be enqueued before rationale is answered", 1, enqueuedInitial.size)
            assertEquals(fetchNowFeed.id, enqueuedInitial.single().first)
            assertNotNull(testDb.rssDao().feed(fetchNowFeed.id)?.acquisitionId)
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_allow))
            withTimeout(3000) {
                while (deniedDispatches == 0 ||
                    find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title) } != null
                ) {
                    delay(20)
                }
            }
            assertEquals(2, permissionLaunches)
            assertNotNull(
                "Denying notification permission must not unschedule or block initial fetch",
                testDb.rssDao().feed(fetchNowFeed.id)?.acquisitionId
            )

            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch))
            withTimeout(3000) { while (fetchNowModel.settings.value.dailyEnabled || fetchNowModel.busy.value) delay(20) }
            clickText(activity.getString(me.bubblespark.tidilo.R.string.rss_daily_fetch))
            withTimeout(3000) { while (!fetchNowModel.settings.value.dailyEnabled || fetchNowModel.busy.value) delay(20) }
            delay(300)
            assertFalse(fetchNowModel.notificationRationale.value)
            assertNull(
                "Denied permission must not re-open notification rationale on subsequent Daily fetch enable",
                find { it.text?.toString() == activity.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title) }
            )

            me.bubblespark.tidilo.config.AppConfig.setLanguage(me.bubblespark.tidilo.config.AppLanguage.ENGLISH)
            val jaLocale = Locale.JAPANESE
            val jaConfig = Configuration(context.resources.configuration).apply {
                setLocale(jaLocale)
                setLayoutDirection(jaLocale)
                fontScale = 2f
                screenWidthDp = 360
                smallestScreenWidthDp = 360
            }
            val jaContext = context.createConfigurationContext(jaConfig)
            val jaTitle = jaContext.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_title)
            val jaMessage = jaContext.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_message)
            val jaAllow = jaContext.getString(me.bubblespark.tidilo.R.string.rss_notification_permission_allow)
            val jaLater = jaContext.getString(me.bubblespark.tidilo.R.string.rss_later)
            var dialogAllows = 0
            me.bubblespark.tidilo.config.AppConfig.setLanguage(me.bubblespark.tidilo.config.AppLanguage.JAPANESE)
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(jaConfig, activity.resources.displayMetrics)
                @Suppress("DEPRECATION")
                activity.applicationContext.resources.updateConfiguration(jaConfig, activity.applicationContext.resources.displayMetrics)
                activity.setContent {
                    TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.MODERN) {
                        CompositionLocalProvider(
                            LocalConfiguration provides jaConfig,
                            LocalContext provides jaContext,
                            LocalDensity provides Density(activity.resources.displayMetrics.density, 2f)
                        ) {
                            me.bubblespark.tidilo.ui.screens.settings.RssNotificationPermissionDialog(
                                onAllow = { dialogAllows++ },
                                onDismiss = {},
                                modifier = Modifier.widthIn(max = 360.dp)
                            )
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            node("Large font title") { it.text?.toString() == jaTitle }
            node("Large font body") { it.text?.toString() == jaMessage }
            val density = activity.resources.displayMetrics.density
            val laterButton = node("Large font Later button") { it.text?.toString() == jaLater }
            var laterClickable: AccessibilityNodeInfo? = laterButton
            while (laterClickable != null && !laterClickable.isClickable) laterClickable = laterClickable.parent
            val laterBounds = android.graphics.Rect().also { requireNotNull(laterClickable).getBoundsInScreen(it) }
            assertTrue("Dialog dismiss button must meet 48dp touch height", laterBounds.height() >= 47.5f * density)

            val allowButton = node("Large font Allow button") { it.text?.toString() == jaAllow }
            var allowClickable: AccessibilityNodeInfo? = allowButton
            while (allowClickable != null && !allowClickable.isClickable) allowClickable = allowClickable.parent
            val allowBounds = android.graphics.Rect().also { requireNotNull(allowClickable).getBoundsInScreen(it) }
            assertTrue("Dialog confirm button must meet 48dp touch height", allowBounds.height() >= 47.5f * density)
            clickText(jaAllow)
            withTimeout(3000) { while (dialogAllows == 0) delay(20) }
        } finally {
            inMemoryDb?.close()
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            me.bubblespark.tidilo.config.AppConfig.setLanguage(previousAppLanguage)
            @Suppress("DEPRECATION")
            context.applicationContext.resources.updateConfiguration(
                previousAppResourcesConfig,
                context.applicationContext.resources.displayMetrics
            )
            rssPreferences.save(previousRssSettings)
            me.bubblespark.tidilo.data.rss.RssScheduler.reschedule(context)
            val restore = notificationPrefs.edit()
            if (hadRequestedKey) {
                restore.putBoolean(
                    me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED,
                    previousRequestedValue
                )
            } else {
                restore.remove(me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED)
            }
            assertTrue(restore.commit())
        }
    }
}
