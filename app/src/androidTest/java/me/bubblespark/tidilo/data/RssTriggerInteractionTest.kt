package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class RssTriggerInteractionTest {
    private val host = HeaderTestHost()
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(30) }

    private fun activePickerNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            for (index in 0 until node.childCount) {
                val match = node.getChild(index)?.let { find(it, matches) }
                if (match != null) return match
            }
            return null
        }
        val root = host.instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (find(root) { it.text?.toString() == "Save" } == null) return null
        return find(root, predicate)
    }

    private fun clickDial(value: String): Boolean {
        val label = activePickerNode { it.text == null && it.contentDescription?.toString() == value }
        val target = generateSequence(label) { it.parent }.firstOrNull { it.isClickable }
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun tapMinute(value: String) {
        val label = activePickerNode { it.text == null && it.contentDescription?.toString() == value } ?: return
        val bounds = Rect().also(label::getBoundsInScreen)
        if (bounds.isEmpty) return
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0
            )
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                host.instrumentation.uiAutomation.injectInputEvent(event, true)
            } finally {
                event.recycle()
            }
        }
    }

    private fun showsMinute15() = activePickerNode {
        it.text?.toString() == "15" && it.contentDescription?.toString() == "15 minutes"
    } != null

    @Test fun pickerDraftSurvivesRestorationAndStaleEditIsDiscarded(): Unit = runBlocking {
        val activity = host.launch()
        val controls = RssTriggerTestControls(host, activity)
        val settings = mutableStateOf(RssSettings(listOf(LocalTime.of(18, 30))))
        val mounted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        val unmounted = CompletableDeferred<Unit>()
        var updates = 0
        var stage = "mount"
        try {
            host.main { activity.setContent { MaterialTheme {
                if (mounted.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                    RssSettingsContent(StorageLoad.Ready(emptyList()), settings.value, false, null, true,
                        onBack = {}, onRetry = {}, onSettings = { settings.value = it; updates++ },
                        onFeed = { _, _ -> }, onDelete = {}, onFetch = {}, onDismissError = {})
                    DisposableEffect(Unit) {
                        onDispose { unmounted.complete(Unit) }
                    }
                }
            } } }
            stage = "add default time"
            controls.clickRow("Add trigger", controls.description(R.string.rss_add_trigger_time))
            controls.clickDialog("Add")
            await { settings.value.triggerTimes.size == 2 }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), settings.value.triggerTimes)
            stage = "open edit"
            controls.clickRow("Edit 18:30", controls.description(R.string.rss_edit_trigger_time, "18:30"))
            // The existing Material clock exposes values through descriptions, not text.
            stage = "select hour 12"
            await { clickDial("12 hours") }
            stage = "select minute 15"
            controls.awaitDialog("minute dial") { host.description(it, "20 minutes") }
            await {
                val selected = showsMinute15()
                // The dial can be present while Material still ignores selections during its transition.
                if (!selected) tapMinute("15 minutes")
                selected
            }
            var saved: Map<String, List<Any?>> = emptyMap()
            stage = "save and unmount"
            host.main { saved = registry.value.performSave(); mounted.value = false }
            withTimeout(5000) { unmounted.await() }
            await { !controls.dialogHas(controls.text("Save")) }
            stage = "restore and save draft"
            host.main { registry.value = SaveableStateRegistry(saved) { true }; mounted.value = true }
            await(::showsMinute15)
            controls.clickDialog("Save")
            await { settings.value.triggerTimes.contains(LocalTime.of(12, 15)) }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(12, 15)), settings.value.triggerTimes)
            assertFalse(settings.value.dailyEnabled)
            assertTrue(settings.value.wifiOnly)
            assertEquals(2, updates)
            stage = "stale edit"
            controls.clickRow("Edit 12:15", controls.description(R.string.rss_edit_trigger_time, "12:15"))
            controls.awaitDialog("Save", predicate = controls.text("Save"))
            host.main { settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.of(8, 0)), wifiOnly = false) }
            await { !controls.dialogHas(controls.text("Save")) }
            assertEquals(2, updates)
            assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
            assertFalse(settings.value.wifiOnly)
        } catch (failure: Exception) {
            throw AssertionError("Picker stage: $stage", failure)
        } finally { host.main { activity.finish() } }
    }

    @Test fun triggerControlsRemainReachableAtLargeFontsInBothThemes(): Unit = runBlocking {
        val activity = host.launch()
        val controls = RssTriggerTestControls(host, activity)
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) for (count in 0..3) {
                val settings = RssSettings(listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 30)).take(count))
                host.main { activity.setContent { key(dark, scale, count) {
                    TidiloTheme(darkTheme = dark, dynamicColor = false) {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                            Surface { Box(Modifier.width(360.dp)) {
                                RssSettingsContent(StorageLoad.Ready(emptyList()), settings, false, null, true,
                                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                                    onDelete = {}, onFetch = {}, onDismissError = {})
                            } }
                        }
                    }
                } } }
                val scenario = "dark=$dark scale=$scale count=$count"
                val time = settings.triggerTimes.lastOrNull()
                val bitmap = if (time == null) {
                    controls.reveal("Not set ($scenario)", controls.text("Not set"))
                    host.instrumentation.uiAutomation.takeScreenshot()
                } else {
                    val editLabel = controls.description(R.string.rss_edit_trigger_time, time.toString())
                    val removeLabel = controls.description(R.string.rss_remove_trigger_time, time.toString())
                    controls.reveal("last trigger ($scenario)", editLabel)
                    captureDeleteButton(activity, controls, editLabel, removeLabel, scenario)
                }
                try {
                    java.io.File(host.context.getExternalFilesDir(null), "issue160-$dark-$scale-$count.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
                if (count == 0) {
                    controls.clickRow("Add trigger", controls.description(R.string.rss_add_trigger_time))
                    controls.awaitDialog("Add", predicate = controls.text("Add"))
                    host.main {
                        val confirm = host.nodes(activity).first(controls.text("Add"))
                        assertTrue("Picker confirmation remains visible at font scale $scale",
                            confirm.boundsInWindow.height >= confirm.size.height - 1 && confirm.size.height > 0)
                    }
                    host.instrumentation.waitForIdleSync()
                    // Capture the settled dialog rather than its translucent entrance frame.
                    delay(400)
                    val picker = host.instrumentation.uiAutomation.takeScreenshot()
                    try { java.io.File(host.context.getExternalFilesDir(null), "issue160-picker-$dark-$scale.png").outputStream().use {
                        picker.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { picker.recycle() }
                    controls.clickDialog("Cancel")
                }
            }
        } finally { host.main { activity.finish() } }
    }
    private suspend fun captureDeleteButton(
        activity: ComponentActivity,
        controls: RssTriggerTestControls,
        editLabel: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean,
        removeLabel: (androidx.compose.ui.semantics.SemanticsNode) -> Boolean,
        scenario: String
    ): Bitmap {
        val committed = CompletableDeferred<Unit>()
        host.main {
            val decor = activity.window.decorView
            decor.viewTreeObserver.registerFrameCommitCallback { committed.complete(Unit) }
            decor.invalidate()
        }
        withTimeout(5000) { committed.await() }

        val result = CompletableDeferred<Int>()
        lateinit var bitmap: Bitmap
        var center = Offset.Zero
        val density = activity.resources.displayMetrics.density
        try {
            host.main {
                val nodes = host.nodes(activity, includeOtherWindows = false)
                val icon = nodes.first(removeLabel)
                val remove = controls.action(icon)
                val edit = controls.action(nodes.first(editLabel))
                for (control in listOf(remove, edit)) {
                    val bounds = control.boundsInRoot
                    assertTrue("48dp touch target: $scenario $bounds",
                        bounds.width >= 48 * density - 1 && bounds.height >= 48 * density - 1)
                    assertTrue("Control fits at 360dp: $scenario $bounds", bounds.right <= 360 * density + 1)
                }
                assertTrue("Controls must not overlap: $scenario", remove.boundsInRoot.right <= edit.boundsInRoot.left)
                assertTrue("Delete icon must be fully visible: $scenario ${icon.boundsInWindow}",
                    icon.boundsInWindow.height >= icon.size.height - 1)
                center = icon.boundsInWindow.center
                val decor = activity.window.decorView
                bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (!result.complete(code)) bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
            val code = withTimeout(5000) { result.await() }
            assertEquals("Window capture: $scenario center=$center", PixelCopy.SUCCESS, code)
            val x = center.x.toInt()
            val y = center.y.toInt()
            val redY = (center.y + 4 * density).toInt()
            assertTrue("Delete icon outside captured window: $scenario center=$center",
                x - 2 >= 0 && x + 2 < bitmap.width && y - 2 >= 0 &&
                    maxOf(y + 2, redY) < bitmap.height)
            val background = bitmap.getPixel(x, redY)
            assertTrue("Delete circle stays red: $scenario color=${Integer.toHexString(background)} center=$center",
                Color.red(background) > 140 && Color.green(background) < 120 && Color.blue(background) < 120)
            val whiteMinus = (-2..2).any { dy ->
                (-2..2).any { dx ->
                    val pixel = bitmap.getPixel(x + dx, y + dy)
                    Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230
                }
            }
            assertTrue("Delete minus stays white: $scenario center=$center", whiteMinus)
            return bitmap
        } catch (failure: Throwable) {
            if (result.isCompleted) bitmap.recycle()
            result.cancel()
            throw failure
        }
    }

}
