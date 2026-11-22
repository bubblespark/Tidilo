package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisContent
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsContentMaxWidth
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class SettingsRedesignTest {
    private val host = HeaderTestHost()

    @Test fun settingsRowsNavigateAndKeepExistingConfirmationDialogs(): Unit = runBlocking {
        val activity = host.launch()
        var ai = 0
        var privateContent = 0
        var rss = 0
        var summary = 0
        var debug = 0
        try {
            host.main { activity.setContent { TidiloTheme {
                SettingsScreen({}, { ai++ }, onNavigateToPrivateContent = { privateContent++ },
                    onNavigateToRss = { rss++ }, onNavigateToStorage = { summary++ },
                    onNavigateToDebug = { debug++ })
            } } }
            suspend fun click(label: Int) = invokeClick(host.await(activity) {
                host.text(it, host.context.getString(label)) && clickableOrNull(it) != null
            })
            val aiLabel = host.await(activity) { host.text(it, host.context.getString(R.string.settings_ai_analysis_title)) }
            val aiRow = clickable(aiLabel)
            assertEquals(androidx.compose.ui.semantics.Role.Button, aiRow.config[SemanticsProperties.Role])
            invokeClick(aiLabel)
            assertEquals(1, ai)
            val privateLabel = host.await(activity) { host.text(it, host.context.getString(R.string.settings_private_title)) }
            assertEquals(androidx.compose.ui.semantics.Role.Button, clickable(privateLabel).config[SemanticsProperties.Role])
            invokeClick(privateLabel)
            assertEquals(1, privateContent)
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
                                            readiness = AiCoreReadiness.Status(
                                                if (page == "ai") FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE
                                            ), onNavigateBack = {},
                                            onModeChange = { if (page == "disabled") finalClicks++ },
                                            onNavigateToParameters = { if (page == "ai") finalClicks++ })
                                    } }
                                }
                            }
                        }
                    } } }
                    withTimeout(5000) { rendered.await() }
                    val finalLabel = host.context.getString(when (page) {
                        "settings" -> R.string.settings_debug_title
                        "ai" -> R.string.settings_generate_content_title
                        else -> R.string.settings_ai_analysis_disable
                    })
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

    @Test fun settingsGroupsAreCenteredAndCappedOnWideWindows(): Unit = runBlocking {
        val activity = host.launch()
        try {
            for (hostWidth in listOf(1200.dp, 360.dp)) {
                host.main { activity.setContent { key(hostWidth) { TidiloTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                        Box(Modifier.requiredWidth(hostWidth).fillMaxHeight().testTag("settings-host")) {
                            SettingsScreen({}, {})
                        }
                    }
                } } } }
                val expectedHostPx = hostWidth.value * density()
                val hostNode = host.await(activity) {
                    it.config.getOrNull(SemanticsProperties.TestTag) == "settings-host" &&
                        kotlin.math.abs(it.boundsInRoot.width - expectedHostPx) < 1f
                }
                host.instrumentation.waitForIdleSync()
                val label = host.context.getString(R.string.settings_language_title)
                val row = host.await(activity) { host.text(it, label) && clickableOrNull(it) != null }
                host.main {
                    val hostBounds = host.nodes(activity).first { it.id == hostNode.id }.boundsInRoot
                    val rowBounds = clickable(row).boundsInRoot
                    val px = density()
                    // The scaffold adds horizontal system bar and cutout insets to the 20 dp margins.
                    val safe = activity.window.decorView.rootWindowInsets.getInsets(
                        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    val margin = HeaderDefaults.HorizontalPadding.value * px
                    val start = margin + safe.left
                    val end = margin + safe.right
                    if (hostWidth > SettingsContentMaxWidth) {
                        assertTrue("Groups stay within the 840 dp cap: ${rowBounds.width / px} dp",
                            rowBounds.width <= SettingsContentMaxWidth.value * px + 1f)
                    } else {
                        assertEquals("Compact margins are unchanged", hostBounds.width - start - end, rowBounds.width, 2f)
                    }
                    assertEquals("Groups are centered in the padded area",
                        hostBounds.left + start + (hostBounds.width - start - end) / 2f, rowBounds.center.x, 1f)
                }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun languageAndThemeDialogsScrollInShortLandscapeWindowsAtLargeFont(): Unit = runBlocking {
        // Dialog windows re-provide density from their own configuration, so the system font scale is required.
        val originalScale = shell("settings get system font_scale").trim()
        val originalSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
            ?.substringAfter(':')?.trim() ?: "reset"
        val px = density()
        var activity: androidx.activity.ComponentActivity? = null
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(640 * px).toInt()}x${(320 * px).toInt()}")
            host.launch()
            val screen = awaitLandscapeActivity(2f)
            activity = screen
            host.main { screen.setContent { TidiloTheme(dynamicColor = false) { SettingsScreen({}, {}) } } }
            host.instrumentation.waitForIdleSync()
            for (dialog in listOf(R.string.settings_language_title, R.string.settings_theme_title)) {
                assertDialogScrollsToLastControl(screen, dialog)
            }
        } finally {
            activity?.let { host.main { it.finish() } }
            shell("wm size $originalSize")
            shell(if (originalScale == "null" || originalScale.isEmpty()) "settings delete system font_scale"
                else "settings put system font_scale $originalScale")
        }
    }

    private suspend fun assertDialogScrollsToLastControl(activity: androidx.activity.ComponentActivity, dialog: Int) {
        val rowLabel = host.context.getString(dialog)
        invokeClick(host.await(activity) { host.text(it, rowLabel) && clickableOrNull(it) != null })
        val close = host.context.getString(R.string.common_close)
        val body = awaitOrFail(activity, "$rowLabel dialog scroll body") { node ->
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null && descendants(node).any {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
            }
        }
        host.instrumentation.waitForIdleSync()
        assertCloseVisible(activity, close)
        var scroll: (suspend (androidx.compose.ui.geometry.Offset) -> androidx.compose.ui.geometry.Offset)? = null
        var distance = 0f
        host.main {
            // Re-read the settled node; the awaited snapshot can predate the final layout.
            val settled = host.nodes(activity).first { it.id == body.id }
            val range = settled.config[SemanticsProperties.VerticalScrollAxisRange]
            distance = range.maxValue() - range.value()
            scroll = settled.config.getOrNull(SemanticsActions.ScrollByOffset)
            android.util.Log.i("SettingsRedesignTest", "$rowLabel dialog overflow=${range.maxValue()}")
            if (dialog == R.string.settings_theme_title) {
                assertTrue("$rowLabel overflows, so this case exercises scrolling", range.maxValue() > 0f)
            }
        }
        if (distance > 0f) {
            kotlinx.coroutines.withContext(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
                requireNotNull(scroll) { "$rowLabel overflows but exposes no scroll action" }
                    .invoke(androidx.compose.ui.geometry.Offset(0f, distance))
            }
            withTimeout(5000) {
                var settledSamples = 0
                while (settledSamples < 4) {
                    var atEnd = false
                    host.main {
                        val range = host.nodes(activity).first { it.id == body.id }
                            .config[SemanticsProperties.VerticalScrollAxisRange]
                        atEnd = kotlin.math.abs(range.maxValue() - range.value()) < 1f
                    }
                    settledSamples = if (atEnd) settledSamples + 1 else 0
                    delay(50)
                }
            }
            host.instrumentation.waitForIdleSync()
        }
        host.main {
            val viewport = host.nodes(activity).first { it.id == body.id }.boundsInWindow
            val last = if (dialog == R.string.settings_theme_title) {
                host.nodes(activity).first { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            } else {
                host.nodes(activity).last { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            }
            val bounds = last.boundsInWindow
            // A large-font row can be taller than a cutout-constrained viewport, so require the
            // row to be scrolled into view with its label unclipped and a usable visible target.
            assertTrue("$rowLabel last control is scrolled into view: $bounds in $viewport",
                bounds.height > 0f && bounds.bottom <= viewport.bottom + 1f)
            val minVisible = minOf(48f * density(), viewport.height) - 1f
            assertTrue("$rowLabel last control keeps a usable visible target: ${bounds.height} px",
                bounds.height >= minVisible)
            val label = descendants(last).first { it.config.getOrNull(SemanticsProperties.Text) != null }
            val labelBounds = label.boundsInWindow
            assertTrue("$rowLabel last control label is unclipped: $labelBounds in $viewport",
                labelBounds.top >= viewport.top - 1f && labelBounds.bottom <= viewport.bottom + 1f)
            assertEquals("$rowLabel last control label is fully visible",
                label.size.height.toFloat(), labelBounds.height, 1f)
        }
        assertCloseVisible(activity, close)
        clickDialogText(R.string.common_close)
        withTimeout(5000) {
            while (true) {
                var open = true
                host.main { open = host.nodes(activity).any { host.text(it, close) } }
                if (!open) break
                delay(30)
            }
        }
    }

    private fun shell(command: String): String {
        val pfd = host.instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    @Test fun sectionTitlesExposeHeadingSemantics(): Unit = runBlocking {
        val activity = host.launch()
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) { SettingsScreen({}, {}) } } }
            assertHeadings(activity, listOf(R.string.settings_section_general, R.string.settings_section_data,
                R.string.settings_section_debug), notHeading = R.string.settings_language_title)
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                AiAnalysisContent(mode = AiAnalysisMode.AICORE,
                    readiness = AiCoreReadiness.Status(FeatureStatus.AVAILABLE), onNavigateBack = {},
                    onModeChange = {}, onNavigateToParameters = {})
            } } }
            assertHeadings(activity, listOf(R.string.settings_ai_analysis_using_model_title,
                R.string.settings_section_aicore_parameters, R.string.ai_model_status_title),
                notHeading = R.string.settings_ai_analysis_aicore)
        } finally { host.main { activity.finish() } }
    }

    private suspend fun assertHeadings(activity: androidx.activity.ComponentActivity, headings: List<Int>, notHeading: Int) {
        for (label in headings) {
            val text = host.context.getString(label)
            val node = host.await(activity) { host.text(it, text) }
            host.main { assertTrue("$text is a heading", node.config.contains(SemanticsProperties.Heading)) }
        }
        val rowText = host.context.getString(notHeading)
        val row = host.await(activity) { host.text(it, rowText) }
        host.main { assertFalse("$rowText is not a heading", row.config.contains(SemanticsProperties.Heading)) }
    }

    private suspend fun assertCloseVisible(activity: androidx.activity.ComponentActivity, close: String) {
        val node = host.await(activity) { host.text(it, close) }
        host.main {
            val bounds = node.boundsInWindow
            assertTrue("Close is visible: $bounds", bounds.height > 0f)
            assertEquals("Close is fully visible", node.size.height.toFloat(), bounds.height, 1f)
        }
    }

    private suspend fun awaitOrFail(
        activity: androidx.activity.ComponentActivity,
        what: String,
        predicate: (SemanticsNode) -> Boolean
    ): SemanticsNode = try {
        host.await(activity, predicate)
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        var dump = ""
        host.main {
            dump = host.nodes(activity).filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton ||
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            }.joinToString(" | ") { node ->
                "${node.id}:${node.config.getOrNull(SemanticsProperties.Role)}:" +
                    "${node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()}:" +
                    "parents=${generateSequence(node.parent) { it.parent }.map { it.id }.toList()}"
            }
        }
        throw AssertionError("Timed out waiting for $what; nodes: $dump", timeout)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = node.children.flatMap { listOf(it) + descendants(it) }

    private suspend fun awaitLandscapeActivity(fontScale: Float): androidx.activity.ComponentActivity {
        var found: androidx.activity.ComponentActivity? = null
        var observed = "none"
        try {
            withTimeout(8000) {
                while (found == null) {
                    host.main {
                        val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<androidx.activity.ComponentActivity>()
                        observed = resumed.joinToString { "${it.javaClass.simpleName}:${it.resources.configuration.orientation}:${it.resources.configuration.fontScale}" }
                        found = resumed.firstOrNull {
                            it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                                kotlin.math.abs(it.resources.configuration.fontScale - fontScale) < 0.01f
                        }
                    }
                    if (found == null) delay(50)
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Landscape activity not resumed; observed [$observed]", timeout)
        }
        host.instrumentation.waitForIdleSync()
        return requireNotNull(found)
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
