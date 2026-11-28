package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.DomainGroupItem
import me.bubblespark.tidilo.ui.screens.library.LibraryDeckCard
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Reads real Compose text layout so an ellipsized accessibility label cannot pass as a full title. */
class CollectionTitleTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Awaits [text], stepping frames while the clock is paused for a marquee. */
    private fun awaitText(text: String): SemanticsNode {
        composeRule.waitUntil(5_000) {
            if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
    }

    private fun layout(text: String): TextLayoutResult {
        val node = awaitText(text)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.runOnUiThread { node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        return layouts.first()
    }

    private fun titleBounds(text: String): Rect {
        val node = awaitText(text)
        // boundsInWindow is clipped to the visible title; shift it into screen coordinates.
        val shift = node.positionOnScreen - node.positionInWindow
        val area = node.boundsInWindow.translate(shift)
        return Rect(area.left.roundToInt(), area.top.roundToInt(), area.right.roundToInt(), area.bottom.roundToInt())
    }

    private fun screenshot(bounds: Rect): Bitmap {
        composeRule.waitForIdle()
        val screen = instrumentation.uiAutomation.takeScreenshot()
        return try {
            Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
        } finally {
            screen.recycle()
        }
    }

    @Test fun sharedDeckFitsLongJapaneseNamesAtAllScaleBoundaries() {
        val title = mutableStateOf("IGN")
        val fontScale = mutableFloatStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    LibraryDeckCard(DomainGroupItem("example.com", title.value, 3), Color.Blue, {},
                        Modifier.width(166.dp)) { }
                }
            }
        }
        for ((length, size) in listOf(10 to 16f, 11 to 14.5f, 18 to 14.5f, 19 to 13.5f, 24 to 13.5f)) {
            val value = "あ".repeat(length)
            title.value = value
            val rendered = layout(value)
            assertFalse("$length characters must fit the deck", rendered.hasVisualOverflow)
            assertTrue("Deck title must use at most three lines", rendered.lineCount <= 3)
            assertEquals(size, rendered.layoutInput.style.fontSize.value, 0.01f)
        }
        val example = "ニッチなPCゲーマーの環境構築Z"
        title.value = example
        fontScale.floatValue = 1.3f
        assertFalse("Issue example must fit with enlarged text", layout(example).hasVisualOverflow)
        val domain = titleBounds("example.com")
        val count = titleBounds(context.resources.getQuantityString(me.bubblespark.tidilo.R.plurals.domain_card_item_count, 3, 3))
        assertTrue("Enlarged title must leave room for the count badge", domain.bottom <= count.top)
    }

    @Test fun rssDrillDownScalesAtAllBoundariesWithoutChangingCollectionHeader(): Unit = runBlocking {
        val caches = OfflineVisualCaches()
        val library = InMemoryLibrary(context)
        try {
            library.awaitReady()
            val vm = library.viewModel()
            composeRule.runOnUiThread {
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.selectRssFeedFilter(null)
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Box(Modifier.width(320.dp)) { LibraryScreen(vm) }
                }
            }
            // Overflowing titles start an infinite marquee, which never lets an auto-advancing clock go idle.
            composeRule.mainClock.autoAdvance = false
            for ((length, size) in listOf(6 to 28f, 7 to 23f, 9 to 23f, 10 to 20f, 13 to 20f, 14 to 17f, 40 to 17f)) {
                val value = "あ".repeat(length)
                composeRule.runOnUiThread {
                    vm.selectRssFeedFilter(RssFeedEntity("title-test", "https://title.invalid/rss", value, enabled = false))
                }
                val rendered = layout(value)
                assertEquals(size, rendered.layoutInput.style.fontSize.value, 0.01f)
                assertEquals(1, rendered.lineCount)
                assertFalse("Full title must be laid out for marquee", rendered.isLineEllipsized(0))
            }
            composeRule.runOnUiThread { vm.selectRssFeedFilter(null) }
            // Tab roots keep the Library title; the RSS label belongs to the tab group.
            val header = layout(context.getString(me.bubblespark.tidilo.R.string.library_header_title))
            assertEquals(28f, header.layoutInput.style.fontSize.value, 0.01f)

            // Exercise both actual drill-down routes: the title stays still initially, then moves.
            for (rss in listOf(true, false)) {
                val value = if (rss) "A very long RSS collection title for delayed scrolling"
                    else "a-very-long-domain-name-for-scrolling.invalid"
                composeRule.runOnUiThread {
                    vm.setFilterMode(if (rss) LibraryFilterMode.RSS else LibraryFilterMode.DOMAIN)
                    if (rss) vm.selectRssFeedFilter(RssFeedEntity("title-test", "https://title.invalid/rss", value, enabled = false))
                    else vm.selectDomainFilter(value)
                }
                assertFalse(layout(value).isLineEllipsized(0))
                composeRule.mainClock.advanceTimeBy(450)
                val bounds = titleBounds(value)
                val initial = screenshot(bounds)
                composeRule.mainClock.advanceTimeBy(500)
                val waiting = screenshot(bounds)
                composeRule.mainClock.advanceTimeBy(1600)
                try {
                    assertTrue("Marquee must wait before moving (rss=$rss)", initial.sameAs(waiting))
                    // The compositor can present the advanced frame late, so sample up to 10 times 100ms apart.
                    var moved = false
                    for (sample in 1..10) {
                        if (sample > 1) { composeRule.mainClock.advanceTimeBy(100); delay(100) }
                        val scrolling = screenshot(bounds)
                        try { moved = !waiting.sameAs(scrolling) } finally { scrolling.recycle() }
                        if (moved) break
                    }
                    assertTrue("Overflowing title must eventually scroll (rss=$rss)", moved)
                } finally {
                    initial.recycle(); waiting.recycle()
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
            library.close()
            caches.close()
        }
    }
}
