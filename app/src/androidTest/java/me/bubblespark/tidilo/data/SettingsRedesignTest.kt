package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisContent
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class SettingsRedesignTest {
    private val host = HeaderTestHost()

    @Test fun settingsRowsNavigateAndKeepExistingConfirmationDialogs(): Unit = runBlocking {
        val activity = host.launch()
        var ai = 0
        var rss = 0
        var summary = 0
        var debug = 0
        try {
            host.main { activity.setContent { TidiloTheme {
                SettingsScreen({}, { ai++ }, onNavigateToRss = { rss++ },
                    onNavigateToStorage = { summary++ }, onNavigateToDebug = { debug++ })
            } } }
            suspend fun click(label: Int) = invokeClick(host.await(activity) {
                host.text(it, host.context.getString(label)) && clickableOrNull(it) != null
            })
            val aiLabel = host.await(activity) { host.text(it, host.context.getString(R.string.settings_ai_analysis_title)) }
            val aiRow = clickable(aiLabel)
            assertEquals(androidx.compose.ui.semantics.Role.Button, aiRow.config[SemanticsProperties.Role])
            invokeClick(aiLabel)
            assertEquals(1, ai)
            click(R.string.rss_title)
            click(R.string.summary_title)
            click(R.string.settings_debug_title)
            assertEquals(1, rss)
            assertEquals(1, summary)
            assertEquals(1, debug)
            click(R.string.settings_language_title)
            awaitDialogText(R.string.settings_language_dialog_title)
            clickDialogText(R.string.common_close)
        } finally { host.main { activity.finish() } }
    }

    @Test fun settingsAndAiLayoutsRemainScrollableAtLargeFontSizes(): Unit = runBlocking {
        val activity = host.launch()
        try {
            for (dark in listOf(false, true)) for (fontScale in listOf(1f, 2f)) {
                for (page in listOf("settings", "ai", "disabled")) {
                    var finalClicks = 0
                    val rendered = kotlinx.coroutines.CompletableDeferred<Unit>()
                    host.main { activity.setContent { key(dark, fontScale, page) {
                        TidiloTheme(darkTheme = dark, dynamicColor = false) {
                            val density = LocalDensity.current.density
                            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                                LaunchedEffect(Unit) {
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    rendered.complete(Unit)
                                }
                                Surface(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize()) { Box(Modifier.width(360.dp)) {
                                        if (page == "settings") SettingsScreen({}, {}, onNavigateToDebug = { finalClicks++ })
                                        else AiAnalysisContent(
                                            mode = if (page == "ai") AiAnalysisMode.AICORE else AiAnalysisMode.DISABLE,
                                            fallback = AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY, autoRun = false,
                                            available = page == "ai", onNavigateBack = {}, onModeChange = {},
                                            onFallbackChange = {}, onAutoRunChange = {},
                                            onNavigateToParameters = {}, onNavigateToSafety = { finalClicks++ })
                                    } }
                                }
                            }
                        }
                    } } }
                    withTimeout(5000) { rendered.await() }
                    val finalLabel = host.context.getString(if (page == "settings") R.string.settings_debug_title else R.string.settings_nsfw_title)
                    host.await(activity) { host.text(it, finalLabel) }
                    host.instrumentation.waitForIdleSync()
                    scrollToBoundary(activity, bottom = false)
                    capture("$page-${if (dark) "dark" else "light"}-$fontScale-top")
                    scrollToBoundary(activity, bottom = true)
                    val last = host.await(activity) { host.text(it, finalLabel) && clickableOrNull(it) != null }
                    lateinit var finalRow: SemanticsNode
                    host.main {
                        assertTrue("$page final row is reachable at font scale $fontScale", last.boundsInRoot.height > 0f)
                        val row = clickable(last)
                        finalRow = row
                        assertTrue("Rows retain a minimum touch target", row.boundsInRoot.height >= 48f * density())
                        assertEquals("The full row is visible", row.size.height.toFloat(), row.boundsInRoot.height, 1f)
                        val navigationInset = activity.window.decorView.rootWindowInsets
                            .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                        assertTrue("The full row clears navigation bars", row.boundsInRoot.bottom <=
                            activity.window.decorView.height - navigationInset - 31f * density())
                    }
                    capture("$page-${if (dark) "dark" else "light"}-$fontScale-bottom")
                    val point = finalRow.boundsInWindow.center
                    val downTime = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int) {
                        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                            action, point.x, point.y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { host.instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN)
                    delay(30)
                    touch(android.view.MotionEvent.ACTION_UP)
                    withTimeout(3000) {
                        while (true) {
                            var received = false
                            host.main { received = finalClicks == 1 }
                            if (received) break
                            delay(20)
                        }
                    }
                    assertEquals("$page/$dark/$fontScale row ${finalRow.boundsInRoot} window ${finalRow.boundsInWindow} receives a real tap", 1, finalClicks)
                }
            }
        } finally { host.main { activity.finish() } }
    }

    private fun density() = host.context.resources.displayMetrics.density

    private suspend fun scrollToBoundary(activity: androidx.activity.ComponentActivity, bottom: Boolean) {
        var scroll: (suspend (androidx.compose.ui.geometry.Offset) -> androidx.compose.ui.geometry.Offset)? = null
        var distance = 0f
        host.main {
            val node = host.nodes(activity).first { it.config.getOrNull(SemanticsActions.ScrollByOffset) != null }
            scroll = node.config[SemanticsActions.ScrollByOffset]
            val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
            distance = if (bottom) range.maxValue() - range.value() else -range.value()
        }
        kotlinx.coroutines.withContext(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            requireNotNull(scroll).invoke(androidx.compose.ui.geometry.Offset(0f, distance))
        }
        withTimeout(5000) {
            var settledSamples = 0
            while (settledSamples < 4) {
                var atBoundary = false
                host.main {
                    val range = host.nodes(activity).firstNotNullOf { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
                    atBoundary = kotlin.math.abs(range.value() - if (bottom) range.maxValue() else 0f) < 1f
                }
                settledSamples = if (atBoundary) settledSamples + 1 else 0
                delay(50)
            }
        }
        host.instrumentation.waitForIdleSync()
    }

    private suspend fun awaitDialogText(label: Int): android.view.accessibility.AccessibilityNodeInfo = withTimeout(5000) {
        val text = host.context.getString(label)
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
            return null
        }
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        while (found == null) {
            host.instrumentation.uiAutomation.clearCache()
            found = host.instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            if (found == null) delay(30)
        }
        requireNotNull(found)
    }

    private suspend fun clickDialogText(label: Int) {
        var node = awaitDialogText(label)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun capture(name: String) {
        host.instrumentation.waitForIdleSync()
        val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
        try {
            java.io.File(host.context.cacheDir, "issue150-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun clickable(original: SemanticsNode): SemanticsNode {
        return requireNotNull(clickableOrNull(original))
    }

    private fun clickableOrNull(original: SemanticsNode): SemanticsNode? {
        var node: SemanticsNode? = original
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        return node
    }

    private fun invokeClick(original: SemanticsNode) = host.main {
        assertTrue(clickable(original).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
}
