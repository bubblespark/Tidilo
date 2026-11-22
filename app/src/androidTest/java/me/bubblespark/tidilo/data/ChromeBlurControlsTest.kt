package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.screenCanvasSource
import androidx.compose.foundation.background
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu
import me.bubblespark.tidilo.ui.navigation.rememberLibraryRadialMenuState
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.OverlayBlurPolicy
import me.bubblespark.tidilo.ui.theme.resolveOverlayBlur
import org.junit.Assert.*
import org.junit.Test

/**
 * Every pixel sample waits for a settled frame instead of a fixed delay: a frame-sync marker drawn above the screen
 * proves the screenshot shows the latest state, and the sampled pixels must repeat across consecutive screenshots
 * so theme switches, blur changes, and menu animations have finished. Each step changes the body color so the
 * marker always distinguishes the new frame from the previous one.
 */
class ChromeBlurControlsTest {
    @Test fun realDetailToolbarAndBrowserActionSampleCanvas() = runBlocking {
        val host = HeaderTestHost()
        val fixture = ChromePerformanceFixture(host)
        var activity: ComponentActivity? = null
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        try {
            fixture.prepare()
            val screen = host.launch()
            activity = screen
            host.main { screen.setContent { TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().screenCanvasSource().background(body))
                    BookmarkDetailScreen(fixture.detailId, fixture.vm, {})
                    FrameSyncMarker({ body })
                }
            } } }
            val marker = host.markerCenter(screen)
            // Detail loads asynchronously; the first marker change must come after the toolbar is composed.
            host.await(screen) { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
            val actions = listOf(R.string.detail_action_ai_summary, R.string.bookmark_open_in_browser)
            suspend fun points(): List<Offset> = actions.map { resource ->
                val bounds = clickOwner(host.await(screen) { host.description(it, host.context.getString(resource)) }).boundsInWindow
                Offset(bounds.center.x, bounds.top + bounds.height * .14f)
            }
            suspend fun step(color: Color, change: () -> Unit): List<Int> {
                host.main { change(); body = color }
                return host.settledPixels(marker, color) { points() }
            }
            for (preset in AppTheme.entries) for (night in listOf(false, true)) {
                val off = step(Color.Green) { theme = preset; dark = night; enabled = false }
                assertEquals("OFF surfaces retain opaque theme drawing for $preset/$night", off, step(Color.Red) {})
                val on = step(Color.Blue) { enabled = true }
                val changed = step(Color.Red) {}
                on.indices.forEach { assertNotEquals("Real Detail consumer $it must sample its canvas for $preset/$night", on[it], changed[it]) }
            }
        } finally {
            try { activity?.let { screen -> host.main { screen.setContent {}; screen.finish() } } }
            finally { fixture.close() }
        }
    }

    @Test fun realSearchActionUsesBodyInBothThemesAndRetainsClick() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        var clicks = 0
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        try {
            host.main { activity.setContent { TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalOverlayBlurSetting provides resolveOverlayBlur(policy, enabled)) {
                    TidiloScreenScaffold(header = {}, overlay = {
                        LibraryRadialMenu(rememberLibraryRadialMenuState(), LibraryFilterMode.NEWEST_FIRST, {}, { clicks++ })
                    }) { Box(Modifier.fillMaxSize().drawBehind { drawRect(body) }) }
                    }
                    FrameSyncMarker({ body })
                }
            } } }
            val marker = host.markerCenter(activity)
            val bounds = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }.boundsInWindow
            val point = listOf(Offset(bounds.left + bounds.width * .25f, bounds.top + bounds.height * .4f))
            suspend fun step(color: Color, change: () -> Unit): Int {
                host.main { change(); body = color }
                return host.settledPixels(marker, color) { point }.single()
            }
            for (preset in AppTheme.entries) for (night in listOf(false, true)) {
                val plain = step(Color.Green) { theme = preset; dark = night; enabled = false }
                assertEquals("OFF retains the themed opaque surface for $preset/$night", plain, step(Color.Red) {})
                val blurred = step(Color.Blue) { enabled = true }
                assertNotEquals("Search must sample its body for $preset/$night", blurred, step(Color.Red) {})
            }
            // Menu interactions run apart from pixel sampling so their animations never overlap a sample.
            for (preset in AppTheme.entries) for (night in listOf(false, true)) {
                host.main { theme = preset; dark = night; enabled = true }
                val trigger = host.await(activity) { host.description(it, host.context.getString(R.string.library_shortcuts_open)) }
                host.main { trigger.config[SemanticsActions.OnClick].action!!.invoke() }
                val node = host.await(activity) { host.description(it, host.context.getString(R.string.nav_search)) }
                host.main { check(clickOwner(node).config[SemanticsActions.OnClick].action?.invoke() == true) }
            }
            assertEquals(AppTheme.entries.size * 2, clicks)
            for (fixed in listOf(OverlayBlurPolicy.FORCE_ON, OverlayBlurPolicy.FORCE_OFF)) for (saved in listOf(false, true)) {
                val first = step(Color.Green) { policy = fixed; enabled = saved }
                val second = step(Color.Red) {}
                if (fixed == OverlayBlurPolicy.FORCE_ON) assertNotEquals("$fixed/$saved must sample its body", first, second)
                else assertEquals("$fixed/$saved retains the themed opaque surface", first, second)
            }
        } finally { host.main { activity.finish() } }
    }
}

private const val FRAME_SYNC_MARKER_TAG = "chrome_blur_frame_sync_marker"

/** Mirrors the latest body color above the screen content; screenshots showing it include the latest state. */
@Composable
private fun BoxScope.FrameSyncMarker(color: () -> Color) {
    Box(Modifier.align(Alignment.CenterStart).size(24.dp).testTag(FRAME_SYNC_MARKER_TAG).drawBehind { drawRect(color()) })
}

private suspend fun HeaderTestHost.markerCenter(activity: ComponentActivity): Offset =
    await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == FRAME_SYNC_MARKER_TAG }.boundsInWindow.center

private fun clickOwner(node: SemanticsNode): SemanticsNode {
    var click = node
    while (click.config.getOrNull(SemanticsActions.OnClick) == null && click.parent != null) click = requireNotNull(click.parent)
    return click
}

/**
 * Returns the pixels at [points] from the first screenshot that shows [marker] at [markerPoint] and repeats the
 * previous [STABLE_REPEATS] screenshots' samples, failing with the last observation when no settled frame appears.
 */
private suspend fun HeaderTestHost.settledPixels(markerPoint: Offset, marker: Color, points: suspend () -> List<Offset>): List<Int> {
    val expected = marker.toArgb()
    var last: Pair<Int, List<Int>>? = null
    var previous: List<Int>? = null
    var repeats = 0
    val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
        while (repeats < STABLE_REPEATS) {
            delay(SAMPLE_INTERVAL_MS)
            val targets = points()
            val image = instrumentation.uiAutomation.takeScreenshot()
            val sample = try {
                image.getPixel(markerPoint.x.toInt(), markerPoint.y.toInt()) to targets.map { image.getPixel(it.x.toInt(), it.y.toInt()) }
            } finally { image.recycle() }
            last = sample
            val current = sample.second.takeIf { sample.first == expected }
            repeats = if (current != null && current == previous) repeats + 1 else 0
            previous = current
        }
        requireNotNull(previous)
    }
    return settled ?: throw AssertionError("No settled frame for marker ${Integer.toHexString(expected)}; last (marker, pixels) = $last")
}

private const val STABLE_REPEATS = 2
private const val SAMPLE_INTERVAL_MS = 40L
private const val SETTLE_TIMEOUT_MS = 5_000L
