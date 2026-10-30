package me.bubblespark.tidilo.data

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.SummaryLoad
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class RssTriggerInteractionTest {
    private val host = HeaderTestHost()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return host.instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun click(predicate: (AccessibilityNodeInfo) -> Boolean) = withTimeout(5000) {
        while (true) {
            var node = find(predicate)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
            delay(30)
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(30) }

    @Test fun pickerDraftSurvivesRestorationAndStaleEditIsDiscarded(): Unit = runBlocking {
        val activity = host.launch()
        val settings = mutableStateOf(RssSettings(listOf(LocalTime.of(18, 30))))
        val mounted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(null) { true })
        var updates = 0
        var stage = "mount"
        try {
            host.main { activity.setContent { MaterialTheme {
                if (mounted.value) CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                    RssSettingsContent(SummaryLoad.Ready(emptyList()), settings.value, false, null, true,
                        onBack = {}, onRetry = {}, onSettings = { settings.value = it; updates++ },
                        onFeed = { _, _, _, _, _ -> }, onDelete = {}, onFetch = {}, onDismissError = {})
                }
            } } }
            stage = "add default time"
            click { it.contentDescription?.toString() == "Add trigger time" }
            click { it.text?.toString() == "Add" }
            await { settings.value.triggerTimes.size == 2 }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), settings.value.triggerTimes)
            stage = "open edit"
            click { it.contentDescription?.toString() == "Edit trigger time 18:30" }
            // The existing Material clock exposes values through descriptions, not text.
            stage = "select hour 12"
            click { it.contentDescription?.toString() == "12 hours" }
            // The clock's hour-to-minute transition temporarily excludes further pointer input.
            delay(500)
            stage = "select minute 15"
            click { it.contentDescription?.toString() == "15 minutes" }
            await { find { it.text?.toString() == "15" && it.contentDescription?.toString() == "15 minutes" } != null }
            var saved: Map<String, List<Any?>> = emptyMap()
            stage = "save and unmount"
            host.main { saved = registry.value.performSave(); mounted.value = false }
            host.instrumentation.waitForIdleSync()
            await { find { it.text?.toString() == "Save" } == null }
            stage = "restore and save draft"
            host.main { registry.value = SaveableStateRegistry(saved) { true }; mounted.value = true }
            click { it.text?.toString() == "Save" }
            await { settings.value.triggerTimes.contains(LocalTime.of(12, 15)) }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(12, 15)), settings.value.triggerTimes)
            assertFalse(settings.value.dailyEnabled)
            assertTrue(settings.value.wifiOnly)
            assertEquals(2, updates)
            stage = "stale edit"
            click { it.contentDescription?.toString() == "Edit trigger time 12:15" }
            host.main { settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.of(8, 0)), wifiOnly = false) }
            await { find { it.text?.toString() == "Save" } == null }
            assertEquals(2, updates)
            assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
            assertFalse(settings.value.wifiOnly)
        } catch (failure: Exception) {
            fun dump(node: AccessibilityNodeInfo) {
                android.util.Log.e("RssTriggerTest", "text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}")
                for (i in 0 until node.childCount) node.getChild(i)?.let(::dump)
            }
            host.instrumentation.uiAutomation.rootInActiveWindow?.let(::dump)
            throw AssertionError("Picker stage: $stage", failure)
        } finally { host.main { activity.finish() } }
    }

    @Test fun triggerControlsRemainReachableAtLargeFontsInBothThemes(): Unit = runBlocking {
        val activity = host.launch()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) for (count in 0..3) {
                val settings = RssSettings(listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 30)).take(count))
                host.main { activity.setContent { key(dark, scale, count) {
                    TidiloTheme(darkTheme = dark, dynamicColor = false) {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                            Surface { Box(Modifier.width(360.dp)) {
                                RssSettingsContent(SummaryLoad.Ready(emptyList()), settings, false, null, true,
                                    onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _, _, _, _ -> },
                                    onDelete = {}, onFetch = {}, onDismissError = {})
                            } }
                        }
                    }
                } } }
                host.await(activity) { host.text(it, "Daily fetch time") }
                host.instrumentation.waitForIdleSync()
                val target = if (count == 0) "Not set" else "Edit trigger time ${settings.triggerTimes.last()}"
                var reached = false
                repeat(12) {
                    if (!reached) {
                        var scroll: (suspend (androidx.compose.ui.geometry.Offset) -> androidx.compose.ui.geometry.Offset)? = null
                        host.main {
                            val nodes = host.nodes(activity)
                            reached = nodes.any { (host.description(it, target) || host.text(it, target)) &&
                                it.boundsInRoot.height >= it.size.height - 1 && it.boundsInRoot.height > 0 &&
                                it.boundsInRoot.top >= 120 * host.context.resources.displayMetrics.density }
                            scroll = nodes.first { it.config.getOrNull(SemanticsActions.ScrollByOffset) != null }.config[SemanticsActions.ScrollByOffset]
                        }
                        if (!reached) kotlinx.coroutines.withContext(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
                            requireNotNull(scroll).invoke(androidx.compose.ui.geometry.Offset(0f, 160 * host.context.resources.displayMetrics.density))
                        }
                    }
                }
                assertTrue("Last trigger fits for dark=$dark scale=$scale count=$count", reached)
                if (count > 0) host.main {
                    fun button(description: String): androidx.compose.ui.semantics.SemanticsNode {
                        var node = host.nodes(activity).first { host.description(it, description) }
                        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                        return node
                    }
                    val label = settings.triggerTimes.last().toString()
                    val remove = button("Remove trigger time $label")
                    val edit = button("Edit trigger time $label")
                    val density = host.context.resources.displayMetrics.density
                    for (control in listOf(remove, edit)) {
                        assertTrue(control.boundsInRoot.width >= 48 * density - 1)
                        assertTrue(control.boundsInRoot.height >= 48 * density - 1)
                        assertTrue(control.boundsInRoot.right <= 360 * density + 1)
                    }
                    assertTrue(remove.boundsInRoot.right <= edit.boundsInRoot.left)
                }
                host.instrumentation.waitForIdleSync()
                val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
                try {
                    if (count > 0) {
                        var center = androidx.compose.ui.geometry.Offset.Zero
                        host.main {
                            var remove = host.nodes(activity).first { host.description(it, "Remove trigger time ${settings.triggerTimes.last()}") }
                            while (remove.config.getOrNull(SemanticsActions.OnClick) == null) remove = requireNotNull(remove.parent)
                            center = remove.boundsInWindow.center
                        }
                        val density = host.context.resources.displayMetrics.density
                        val background = bitmap.getPixel(center.x.toInt(), (center.y + 4 * density).toInt())
                        assertTrue("Delete circle stays red: dark=$dark scale=$scale count=$count color=${Integer.toHexString(background)} center=$center", android.graphics.Color.red(background) > 140 &&
                            android.graphics.Color.green(background) < 120 && android.graphics.Color.blue(background) < 120)
                        var whiteMinus = false
                        for (dy in -2..2) for (dx in -2..2) {
                            val pixel = bitmap.getPixel(center.x.toInt() + dx, center.y.toInt() + dy)
                            whiteMinus = whiteMinus || (android.graphics.Color.red(pixel) > 230 &&
                                android.graphics.Color.green(pixel) > 230 && android.graphics.Color.blue(pixel) > 230)
                        }
                        assertTrue("Delete minus stays white in both themes", whiteMinus)
                    }
                    java.io.File(host.context.getExternalFilesDir(null), "issue160-$dark-$scale-$count.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
                if (count == 0) {
                    click { it.contentDescription?.toString() == "Add trigger time" }
                    await { find { it.text?.toString() == "Cancel" } != null }
                    val confirm = requireNotNull(find { it.text?.toString() == "Add" })
                    val bounds = android.graphics.Rect()
                    confirm.getBoundsInScreen(bounds)
                    assertTrue("Picker confirmation remains visible at font scale $scale", bounds.height() > 0 &&
                        bounds.bottom < activity.window.decorView.height)
                    host.instrumentation.waitForIdleSync()
                    // Capture the settled dialog rather than its translucent entrance frame.
                    delay(400)
                    val picker = host.instrumentation.uiAutomation.takeScreenshot()
                    try { java.io.File(host.context.getExternalFilesDir(null), "issue160-picker-$dark-$scale.png").outputStream().use {
                        picker.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { picker.recycle() }
                    click { it.text?.toString() == "Cancel" }
                }
            }
        } finally { host.main { activity.finish() } }
    }
}
