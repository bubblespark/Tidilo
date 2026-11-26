package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.screenCanvasSource
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu
import me.bubblespark.tidilo.ui.navigation.rememberLibraryRadialMenuState
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.OverlayBlurPolicy
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.theme.resolveOverlayBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/**
 * Every pixel sample waits for a settled frame instead of a fixed delay: a frame-sync marker drawn above the screen
 * proves the screenshot shows the latest state, and the sampled pixels must repeat across consecutive screenshots
 * so theme switches, blur changes, and menu animations have finished. Each step changes the body color so the
 * marker always distinguishes the new frame from the previous one.
 */
class ChromeBlurControlsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun realDetailToolbarAndBrowserActionSampleCanvas() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        val detailId = "chrome-detail-0"
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        try {
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertOrUpdate(
                    BookmarkEntity(
                        id = detailId,
                        title = "Chrome detail fixture",
                        url = "https://chrome-fixture.invalid/0",
                        description = "Fixture summary",
                        createdAt = 1000L,
                        updatedAt = 1L
                    )
                )
            }
            val vm = withContext(Dispatchers.Main) { library.viewModel() }
            composeRule.setContent {
                TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().screenCanvasSource().background(body))
                        BookmarkDetailScreen(detailId, vm, {})
                        FrameSyncMarker({ body })
                    }
                }
            }
            val markerNode = composeRule.awaitNode(hasTestTag(FRAME_SYNC_MARKER_TAG))
            val marker = Offset(
                markerNode.positionOnScreen.x + markerNode.size.width / 2f,
                markerNode.positionOnScreen.y + markerNode.size.height / 2f
            )
            // Detail loads asynchronously; wait until the toolbar action is composed before sampling.
            composeRule.awaitNode(hasContentDescription(context.getString(R.string.bookmark_open_in_browser), substring = true))
            val actions = listOf(R.string.detail_action_ai_summary, R.string.bookmark_open_in_browser)
            fun points(): List<Offset> = actions.map { resource ->
                val node = composeRule.awaitNode(hasContentDescription(context.getString(resource), substring = true))
                val owner = clickOwner(node)
                Offset(
                    owner.positionOnScreen.x + owner.size.width / 2f,
                    owner.positionOnScreen.y + owner.size.height * .14f
                )
            }
            suspend fun step(color: Color, change: () -> Unit): List<Int> {
                composeRule.runOnUiThread {
                    change()
                    body = color
                }
                return composeRule.settledPixels(marker, color, ::points)
            }
            for ((preset, night) in listOf(AppTheme.DEFAULT to false, AppTheme.MODERN to true)) {
                val off = step(Color.Green) { theme = preset; dark = night; enabled = false }
                assertEquals("OFF surfaces retain opaque theme drawing for $preset/$night", off, step(Color.Red) {})
                val on = step(Color.Blue) { enabled = true }
                val changed = step(Color.Red) {}
                on.indices.forEach {
                    assertNotEquals(
                        "Real Detail consumer $it must sample its canvas for $preset/$night",
                        on[it],
                        changed[it]
                    )
                }
            }
        } finally {
            caches.close()
            library.close()
        }
    }

    @Test
    fun realSearchActionUsesBodyInBothThemesAndRetainsClick() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        var clicks = 0
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        composeRule.setContent {
            TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalOverlayBlurSetting provides resolveOverlayBlur(policy, enabled)) {
                        TidiloScreenScaffold(header = {}, overlay = {
                            LibraryRadialMenu(rememberLibraryRadialMenuState(), LibraryFilterMode.NEWEST_FIRST, {}, { clicks++ })
                        }) {
                            Box(Modifier.fillMaxSize().drawBehind { drawRect(body) })
                        }
                    }
                    FrameSyncMarker({ body })
                }
            }
        }
        val markerNode = composeRule.awaitNode(hasTestTag(FRAME_SYNC_MARKER_TAG))
        val marker = Offset(
            markerNode.positionOnScreen.x + markerNode.size.width / 2f,
            markerNode.positionOnScreen.y + markerNode.size.height / 2f
        )
        val triggerNode = composeRule.awaitNode(hasTestTag("library_radial_trigger"))
        val point = listOf(
            Offset(
                triggerNode.positionOnScreen.x + triggerNode.size.width * .25f,
                triggerNode.positionOnScreen.y + triggerNode.size.height * .4f
            )
        )
        suspend fun step(color: Color, change: () -> Unit): Int {
            composeRule.runOnUiThread {
                change()
                body = color
            }
            return composeRule.settledPixels(marker, color) { point }.single()
        }
        for ((preset, night) in listOf(AppTheme.DEFAULT to false, AppTheme.MODERN to true)) {
            val plain = step(Color.Green) { theme = preset; dark = night; enabled = false }
            assertEquals("OFF retains the themed opaque surface for $preset/$night", plain, step(Color.Red) {})
            val blurred = step(Color.Blue) { enabled = true }
            assertNotEquals("Search must sample its body for $preset/$night", blurred, step(Color.Red) {})
        }
        // Menu interactions run apart from pixel sampling so their animations never overlap a sample.
        for ((preset, night) in listOf(AppTheme.DEFAULT to false, AppTheme.MODERN to true)) {
            composeRule.runOnUiThread { theme = preset; dark = night; enabled = true }
            composeRule.clickNode(
                composeRule.awaitNode(hasContentDescription(context.getString(R.string.library_shortcuts_open), substring = true))
            )
            composeRule.clickNode(
                composeRule.awaitNode(hasContentDescription(context.getString(R.string.nav_search), substring = true))
            )
        }
        assertEquals(2, clicks)
        for ((fixed, saved) in listOf(OverlayBlurPolicy.FORCE_ON to false, OverlayBlurPolicy.FORCE_OFF to true)) {
            val first = step(Color.Green) { policy = fixed; enabled = saved }
            val second = step(Color.Red) {}
            if (fixed == OverlayBlurPolicy.FORCE_ON) {
                assertNotEquals("$fixed/$saved must sample its body", first, second)
            } else {
                assertEquals("$fixed/$saved retains the themed opaque surface", first, second)
            }
        }
    }
}

private const val FRAME_SYNC_MARKER_TAG = "chrome_blur_frame_sync_marker"

/** Mirrors the latest body color above the screen content; screenshots showing it include the latest state. */
@Composable
private fun BoxScope.FrameSyncMarker(color: () -> Color) {
    Box(Modifier.align(Alignment.CenterStart).size(24.dp).testTag(FRAME_SYNC_MARKER_TAG).drawBehind { drawRect(color()) })
}

private fun clickOwner(node: SemanticsNode): SemanticsNode {
    var click = node
    while (click.config.getOrNull(SemanticsActions.OnClick) == null && click.parent != null) {
        click = requireNotNull(click.parent)
    }
    return click
}

/**
 * Returns the pixels at [points] from the first screenshot that shows [marker] at [markerPoint] and matches the
 * previous screenshot's samples, failing with the last observation when no settled frame appears.
 */
private suspend fun ComposeTestRule.settledPixels(
    markerPoint: Offset,
    marker: Color,
    points: () -> List<Offset>
): List<Int> {
    val expected = marker.toArgb()
    var last: Pair<Int, List<Int>>? = null
    var previous: List<Int>? = null
    var repeats = 0
    repeat(MAX_SETTLE_ATTEMPTS) { attempt ->
        waitForIdle()
        val targets = points()
        val sample = captureScreen { image ->
            image.getPixel(markerPoint.x.toInt(), markerPoint.y.toInt()) to
                targets.map { image.getPixel(it.x.toInt(), it.y.toInt()) }
        }
        last = sample
        val current = sample.second.takeIf { sample.first == expected }
        repeats = if (current != null && current == previous) repeats + 1 else 0
        previous = current
        if (repeats >= STABLE_REPEATS && current != null) return current
        if (attempt < MAX_SETTLE_ATTEMPTS - 1) delay(SAMPLE_INTERVAL_MS)
    }
    throw AssertionError("No settled frame for marker ${Integer.toHexString(expected)}; last (marker, pixels) = $last")
}

private const val STABLE_REPEATS = 1
private const val SAMPLE_INTERVAL_MS = 120L
private const val MAX_SETTLE_ATTEMPTS = 10
