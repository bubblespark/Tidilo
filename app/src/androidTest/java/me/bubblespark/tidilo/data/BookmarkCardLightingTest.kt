package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LocalCardAccentColor
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.OverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks actual painted surfaces and rims instead of testing copies of style constants. */
class BookmarkCardLightingTest {
    @get:Rule val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    private data class CardState(val dark: Boolean, val grid: Boolean, val accent: Color = Color.Unspecified)

    @Test fun cardsPaintWhiteOrDarkSurfacesWithPrimaryRims() = verifyCards(listOf(
        CardState(dark = false, grid = false), CardState(dark = false, grid = true),
        CardState(dark = true, grid = false), CardState(dark = true, grid = true)
    ))

    @Test fun cardsFollowScopedBrandChanges() = verifyCards(listOf(
        CardState(dark = false, grid = false, Color.Blue), CardState(dark = false, grid = true, Color.Green),
        CardState(dark = true, grid = false, Color.Green), CardState(dark = true, grid = true, Color.Blue)
    ))

    @Test fun libraryDrillDownCardsUseTheFeedAndDomainAccents(): Unit = runBlocking {
        val context = instrumentation.targetContext
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        try {
            val id = UUID.randomUUID().toString()
            val domain = "lighting-$id.invalid"
            val feed = RssFeedEntity(id, "https://feed-$id.invalid/rss", "Lighting feed", enabled = false)
            val manual = BookmarkEntity("$id-manual", "Lighting manual", "https://$domain/article", "Summary", createdAt = 1, updatedAt = 1)
            val rss = BookmarkEntity("$id-rss", "Lighting RSS", "https://other-$id.invalid/article", "Summary",
                createdAt = 2, updatedAt = 2, rssFeedId = id)
            withContext(Dispatchers.IO) {
                library.db.rssDao().putFeed(feed)
                library.db.bookmarkDao().insertAll(listOf(manual, rss))
            }
            val vm = library.viewModel()
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Gray)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LibraryScreen(vm) }
                    }
                }
            }
            for ((mode, isRss) in listOf(LibraryViewMode.LIST to false, LibraryViewMode.GRID to true,
                LibraryViewMode.LIST to true, LibraryViewMode.GRID to false)) {
                composeRule.runOnUiThread {
                    vm.setViewMode(mode)
                    vm.setFilterMode(if (isRss) LibraryFilterMode.RSS else LibraryFilterMode.DOMAIN)
                    if (isRss) vm.selectRssFeedFilter(feed) else vm.selectDomainFilter(domain)
                }
                composeRule.waitForIdle()
                val title = composeRule.awaitNode(hasText(if (isRss) rss.title else manual.title))
                val bounds = generateSequence(title) { it.parent }.first { SemanticsActions.OnClick in it.config }.screenRect()
                val inset = ((if (mode == LibraryViewMode.GRID) 20 else 28) * density).roundToInt()
                // Without a declared feed image, the publisher supplies the feed accent.
                val key = if (isRss) requireNotNull(DomainIconManager.feedPublisherDomain(feed)) else domain
                val expectedHsv = FloatArray(3).also { PixelColor.colorToHSV(DomainIconManager.generateDomainAccentColor(key).toArgb(), it) }
                fun hueDistance(hsv: FloatArray) = kotlin.math.abs(hsv[0] - expectedHsv[0]).let { minOf(it, 360f - it) }
                val actualHsv = sampleScreen(
                    read = { FloatArray(3).also { hsv -> PixelColor.colorToHSV(it.getPixel(bounds.right - 1, bounds.top + inset), hsv) } },
                    accept = { hueDistance(it) < 25f && it[1] > 0.05f }
                )
                assertTrue("$mode rss=$isRss: the rim must match the destination, hue ${actualHsv[0]} vs ${expectedHsv[0]}",
                    hueDistance(actualHsv) < 25f && actualHsv[1] > 0.05f)
            }
        } finally {
            caches.close()
            library.close()
        }
    }

    private fun verifyCards(states: List<CardState>): Unit = runBlocking {
        val state = mutableStateOf(states.first())
        val bookmark = BookmarkItem("lighting", "", "", "", Tag.createEmpty(), domain = null)
        composeRule.setContent {
            val current = state.value
            val scheme = if (current.dark) darkColorScheme(primary = Color.Red) else lightColorScheme(primary = Color.Red)
            // TidiloTheme's surfaces would replace the red primary this test checks; only blur is turned off.
            CompositionLocalProvider(LocalOverlayBlurSetting provides OverlayBlurSetting(enabled = false, userEditable = true)) {
                MaterialTheme(colorScheme = scheme) {
                    Column(Modifier.fillMaxSize().background(scheme.background).padding(24.dp)) {
                        Box(Modifier.width(300.dp).testTag(CARD_TAG)) {
                            CompositionLocalProvider(LocalCardAccentColor provides current.accent) {
                                if (current.grid) BookmarkGridCardFixture(bookmark) else BookmarkItemCard(bookmark)
                            }
                        }
                    }
                }
            }
        }
        for (current in states) {
            composeRule.runOnUiThread { state.value = current }
            composeRule.waitForIdle()
            val bounds = composeRule.awaitNode(androidx.compose.ui.test.hasTestTag(CARD_TAG)).screenRect()
            val inset = ((if (current.grid) 20 else 28) * density).roundToInt()
            fun accentStrength(pixel: Int) = when (current.accent) {
                Color.Blue -> PixelColor.blue(pixel) - PixelColor.red(pixel)
                Color.Green -> PixelColor.green(pixel) - PixelColor.red(pixel)
                else -> PixelColor.red(pixel) - PixelColor.green(pixel)
            }
            val sample = sampleScreen(
                read = { screenshot ->
                    CardPixels(
                        shadow = screenshot.getPixel(bounds.centerX(), bounds.bottom + (2 * density).roundToInt()),
                        backdrop = screenshot.getPixel(bounds.centerX(), bounds.bottom + (24 * density).roundToInt()),
                        surface = screenshot.getPixel(bounds.left + (5 * density).roundToInt(), bounds.bottom - (40 * density).roundToInt()),
                        incident = screenshot.getPixel(bounds.right - 1, bounds.top + inset),
                        opposite = screenshot.getPixel(bounds.left, bounds.bottom - inset)
                    )
                },
                accept = { it.problems(current.dark, ::accentStrength).isEmpty() }
            )
            val problems = sample.problems(current.dark, ::accentStrength)
            assertTrue("$current: $problems", problems.isEmpty())
        }
    }

    private class CardPixels(val shadow: Int, val backdrop: Int, val surface: Int, val incident: Int, val opposite: Int) {
        fun problems(dark: Boolean, accentStrength: (Int) -> Int): List<String> = buildList {
            val shadowDifference = kotlin.math.abs(PixelColor.red(shadow) - PixelColor.red(backdrop)) +
                kotlin.math.abs(PixelColor.green(shadow) - PixelColor.green(backdrop)) +
                kotlin.math.abs(PixelColor.blue(shadow) - PixelColor.blue(backdrop))
            if (shadowDifference <= 2) add("The shadow must paint outside the rounded card clip")
            if (dark) {
                if (PixelColor.red(surface) >= 80) add("Dark card surfaces must remain dark")
            } else if (PixelColor.red(surface) < 246 || PixelColor.green(surface) < 246 || PixelColor.blue(surface) < 246) {
                add("Light card surfaces must be crisp white: ${Integer.toHexString(surface)}")
            }
            if (accentStrength(incident) <= accentStrength(opposite) + 20) {
                add("The top-right rim must follow the active accent: ${accentStrength(incident)} / ${accentStrength(opposite)}")
            }
        }
    }

    /** Samples at most 10 screenshots 100ms apart and returns the first accepted (or last) reading. */
    private suspend fun <T> sampleScreen(read: (Bitmap) -> T, accept: (T) -> Boolean): T {
        var value: T
        var attempt = 0
        while (true) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            value = try { read(screenshot) } finally { screenshot.recycle() }
            if (accept(value) || ++attempt == 10) return value
            delay(100)
        }
    }

    private fun SemanticsNode.screenRect(): Rect {
        val left = positionOnScreen.x.roundToInt()
        val top = positionOnScreen.y.roundToInt()
        return Rect(left, top, left + size.width, top + size.height)
    }

    private companion object {
        const val CARD_TAG = "lighting_card"
    }
}
