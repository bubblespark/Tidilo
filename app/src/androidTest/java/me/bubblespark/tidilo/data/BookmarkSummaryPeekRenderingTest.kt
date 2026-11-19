package me.bubblespark.tidilo.data

import android.graphics.Color as PixelColor
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.ScreenBackdropScope
import me.bubblespark.tidilo.ui.components.screenBackdropSource
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkSummaryPeekOverlay
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class BookmarkSummaryPeekRenderingTest {
    @Test
    fun defaultLightBackdrop(): Unit = runBlocking { verifyBackdrop(AppTheme.DEFAULT, dark = false) }

    @Test
    fun defaultDarkBackdrop(): Unit = runBlocking { verifyBackdrop(AppTheme.DEFAULT, dark = true) }

    @Test
    fun modernLightBackdrop(): Unit = runBlocking { verifyBackdrop(AppTheme.MODERN, dark = false) }

    @Test
    fun modernDarkBackdrop(): Unit = runBlocking { verifyBackdrop(AppTheme.MODERN, dark = true) }

    private suspend fun verifyBackdrop(theme: AppTheme, dark: Boolean) {
        val host = HeaderTestHost()
        val activity = host.launch()
        val saved = BookmarkItem(
            "backdrop", "Saved article", "https://backdrop.invalid", "Saved description", Tag.createEmpty()
        )
        var bookmark by mutableStateOf<BookmarkItem?>(saved)
        var enabled by mutableStateOf(false)
        var captureSource by mutableStateOf(true)
        var available by mutableStateOf(true)
        var themeBackground = 0
        var themeText = 0
        val sampleOffset = (3 * activity.resources.displayMetrics.density).roundToInt()
        try {
            host.main {
                val softwareView = View(activity)
                assertFalse(softwareView.isHardwareAccelerated)
                activity.setContent {
                    TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                        val background = MaterialTheme.colorScheme.background.toArgb()
                        val text = MaterialTheme.colorScheme.onBackground.toArgb()
                        SideEffect {
                            themeBackground = background
                            themeText = text
                        }
                        ScreenBackdropScope { backdrop ->
                            Box(Modifier.fillMaxSize()) {
                                val capture = if (captureSource) Modifier.screenBackdropSource(backdrop) else Modifier
                                Row(Modifier.fillMaxSize().then(capture)) {
                                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black))
                                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                                }
                                val view = if (available) LocalView.current else softwareView
                                CompositionLocalProvider(LocalView provides view) {
                                    BookmarkSummaryPeekOverlay(bookmark, 104.dp) { bookmark = null }
                                }
                            }
                        }
                    }
                }
            }
            val paragraph = host.await(activity) { host.text(it, saved.description) }
            val heading = host.await(activity) { host.text(it, activity.getString(R.string.detail_section_summary)) }

            fun sample(): Pair<Int, Int> {
                val image = host.instrumentation.uiAutomation.takeScreenshot()
                return try {
                    val x = image.width / 2
                    val y = image.height / 2
                    image.getPixel(x - sampleOffset, y) to image.getPixel(x + sampleOffset, y)
                } finally { image.recycle() }
            }

            fun edgeContrast(pixels: Pair<Int, Int>): Int =
                abs(PixelColor.red(pixels.second) - PixelColor.red(pixels.first))

            fun sharpTextPixels(text: SemanticsNode): Int {
                val bounds = text.boundsInWindow
                val image = host.instrumentation.uiAutomation.takeScreenshot()
                return try {
                    var count = 0
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                        for (x in bounds.left.toInt() until bounds.right.toInt()) {
                            if (image.getPixel(x, y) == themeText) count++
                        }
                    }
                    count
                } finally { image.recycle() }
            }

            suspend fun awaitSamples(description: String, matches: (Pair<Int, Int>) -> Boolean): Pair<Int, Int> {
                var pixels = sample()
                try {
                    withTimeout(3000) {
                        while (!matches(pixels)) {
                            delay(50)
                            pixels = sample()
                        }
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("$description: contrast=${edgeContrast(pixels)}, pixels=$pixels", error)
                }
                return pixels
            }

            val disabled = awaitSamples("85% opacity leaves 15% of a black-to-white edge") {
                edgeContrast(it) in 37..40
            }
            val disabledTextPixels = sharpTextPixels(paragraph)
            val disabledHeadingPixels = sharpTextPixels(heading)
            assertTrue("The fixture must contain unblended foreground pixels", disabledTextPixels > 0)
            assertTrue("The heading uses the theme reading color with blur off", disabledHeadingPixels > 0)
            host.main { enabled = true }
            fun blurred(pixels: Pair<Int, Int>) = edgeContrast(pixels) in 1..16
            awaitSamples("Enabled blur softens the edge", ::blurred)
            assertTrue("The summary foreground stays sharp", sharpTextPixels(paragraph) >= disabledTextPixels * 0.85f)
            assertTrue("The heading stays sharp with the theme reading color", sharpTextPixels(heading) >= disabledHeadingPixels * 0.85f)

            host.main { captureSource = false }
            awaitSamples("A missing capture clears to the opaque theme background") {
                it.first == themeBackground && it.second == themeBackground
            }
            host.main { captureSource = true }
            awaitSamples("The recovered source is blurred again", ::blurred)

            host.main { available = false }
            awaitSamples("Unsupported rendering matches blur OFF") { it == disabled }

            host.main { bookmark = null }
            awaitSamples("Dismissing releases the preview background") { edgeContrast(it) == 255 }
            host.main { available = true; bookmark = saved }
            awaitSamples("Reopening with blur enabled uses the current source", ::blurred)
        } finally {
            host.main { activity.finish() }
        }
    }
}
