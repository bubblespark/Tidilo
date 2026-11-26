package me.bubblespark.tidilo.data

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks painted pixels: measured image bounds alone cannot detect layout overflow. */
class BookmarkDetailImageLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    private enum class Artwork(val width: Int, val height: Int) {
        SQUARE(40, 40), PORTRAIT(20, 40), LANDSCAPE(80, 20), STANDARD_OG(60, 31), SIXTEEN_BY_NINE(64, 36)
    }

    @Test fun tallArtworkStaysInsideBanner() = verifyArtwork(listOf(
        Artwork.SQUARE to 320, Artwork.SQUARE to 600, Artwork.PORTRAIT to 320, Artwork.PORTRAIT to 600, Artwork.STANDARD_OG to 600
    ))

    @Test fun wideArtworkKeepsNaturalHeight() = verifyArtwork(listOf(
        Artwork.LANDSCAPE to 320, Artwork.LANDSCAPE to 600, Artwork.SIXTEEN_BY_NINE to 320, Artwork.SIXTEEN_BY_NINE to 600,
        Artwork.STANDARD_OG to 320
    ))

    private fun verifyArtwork(cases: List<Pair<Artwork, Int>>): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val items = cases.map { it.first }.distinct().associateWith { artwork ->
            val domain = "issue123-${artwork.name.lowercase().replace('_', '-')}-$token.example"
            BookmarkEntity("$token-${artwork.name}", "Banner ${artwork.name.lowercase()}", "https://$domain/article", "Summary",
                createdAt = 1, updatedAt = 1, domain = domain, ogImageUrl = "https://$domain/image.png")
        }
        val bytes = items.entries.associate { (artwork, item) -> item.ogImageUrl to tinyPng(artwork.width, artwork.height, Color.BLUE) }
        // Delay resolution so the test also exercises the asynchronous insertion of the banner.
        val caches = OfflineVisualCaches { url -> delay(100); bytes[url] }
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) { library.db.bookmarkDao().insertAll(items.values.toList()) }
            val vm = library.viewModel()
            val case = mutableStateOf(cases.first())
            composeRule.setContent {
                val (artwork, width) = case.value
                CompositionLocalProvider(LocalDensity provides Density(DENSITY)) {
                    TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        Surface {
                            Box(Modifier.width(width.dp)) {
                                key(case.value) { BookmarkDetailScreen(items.getValue(artwork).id, vm, {}) }
                            }
                        }
                    }
                }
            }
            for ((artwork, availableWidth) in cases) {
                val item = items.getValue(artwork)
                composeRule.runOnUiThread { case.value = artwork to availableWidth }
                composeRule.waitUntil(10_000) {
                    OgImageManager.getCachedOgImageBitmap(requireNotNull(item.domain), requireNotNull(item.ogImageKey)) != null
                }
                composeRule.waitForIdle()
                val titleTop = composeRule.awaitNode(hasText(item.title)).positionOnScreen.y.roundToInt()
                val expectedHeight = minOf(availableWidth * artwork.height.toFloat() / artwork.width, 300f) * DENSITY
                // The recreated screen inserts the cached banner asynchronously; screenshots are the heavy
                // wait tier, so sample at most 10 frames 100ms apart until the artwork is painted.
                var scan = scanArtwork(titleTop, availableWidth)
                var attempt = 1
                while (scan.inside <= 1000 && attempt++ < 10) {
                    delay(100)
                    scan = scanArtwork(titleTop, availableWidth)
                }
                val label = "$artwork at ${availableWidth}dp"
                assertTrue("$label: fixture must render blue artwork", scan.inside > 1000)
                assertTrue("$label: artwork must remain above the title (${scan.last} < $titleTop)", scan.last < titleTop)
                val paintedHeight = scan.last - scan.first + 1
                assertTrue(
                    "$label: painted $paintedHeight, expected <= ${expectedHeight.roundToInt()}",
                    paintedHeight in (expectedHeight * 0.35f).roundToInt()..(expectedHeight + 4 * DENSITY).roundToInt()
                )
                // Full-bleed horizontally: pure blue pixels appear near both left and right edges inside the core band
                assertTrue("$label: full-bleed left edge must paint artwork", Color.blue(scan.leftEdge) > 200)
                assertTrue("$label: full-bleed right edge must paint artwork", Color.blue(scan.rightEdge) > 200)
            }
        } finally {
            caches.close()
            library.close()
            items.values.forEach { item ->
                OgImageManager.getOgImageFile(context, requireNotNull(item.ogImageKey)).delete()
                OgImageManager.getDomainLatestImageFile(context, requireNotNull(item.domain)).delete()
            }
        }
    }

    private class ArtworkScan(val inside: Int, val first: Int, val last: Int, val leftEdge: Int, val rightEdge: Int)

    /** Finds the pure-blue artwork rows in one screenshot, recycled before returning. */
    private fun scanArtwork(titleTop: Int, availableWidth: Int): ArtworkScan {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            var inside = 0
            var first = screenshot.height
            var last = 0
            val row = IntArray(screenshot.width)
            for (y in 0 until screenshot.height) {
                screenshot.getPixels(row, 0, screenshot.width, 0, y, screenshot.width, 1)
                for (pixel in row) if (Color.blue(pixel) > 245 && Color.red(pixel) < 10 && Color.green(pixel) < 10) {
                    if (y < titleTop) inside++
                    first = minOf(first, y)
                    last = maxOf(last, y)
                }
            }
            val midY = ((first + last) / 2).coerceIn(0, screenshot.height - 1)
            return ArtworkScan(inside, first, last,
                screenshot.getPixel((4 * DENSITY).roundToInt(), midY),
                screenshot.getPixel((availableWidth * DENSITY).roundToInt() - (4 * DENSITY).roundToInt(), midY))
        } finally { screenshot.recycle() }
    }

    private companion object {
        const val DENSITY = 1.75f
    }
}
