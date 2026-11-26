package me.bubblespark.tidilo.data

import android.graphics.Color as PixelColor
import android.view.View
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class BookmarkSummaryPeekRenderingTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val saved = BookmarkItem(
        "backdrop", "Saved article", "https://backdrop.invalid", "Saved description", Tag.createEmpty()
    )
    private var theme by mutableStateOf(AppTheme.DEFAULT)
    private var dark by mutableStateOf(false)
    private var bookmark by mutableStateOf<BookmarkItem?>(saved)
    private var enabled by mutableStateOf(false)
    private var captureSource by mutableStateOf(true)
    private var available by mutableStateOf(true)
    private var themeBackground = 0
    private var themeText = 0

    @Test
    fun defaultLightBackdropLifecycle(): Unit = runBlocking {
        render()
        val paragraph = composeRule.awaitNode(hasText(saved.description))
        val heading = composeRule.awaitNode(hasText(composeRule.activity.getString(R.string.detail_section_summary)))

        val disabled = awaitSamples("85% opacity leaves 15% of a black-to-white edge") { edgeContrast(it) in 37..40 }
        val disabledTextPixels = sharpTextPixels(paragraph)
        val disabledHeadingPixels = sharpTextPixels(heading)
        assertTrue("The fixture must contain unblended foreground pixels", disabledTextPixels > 0)
        assertTrue("The heading uses the theme reading color with blur off", disabledHeadingPixels > 0)
        update { enabled = true }
        awaitSamples("Enabled blur softens the edge", ::blurred)
        assertTrue("The summary foreground stays sharp", sharpTextPixels(paragraph) >= disabledTextPixels * 0.85f)
        assertTrue("The heading stays sharp with the theme reading color", sharpTextPixels(heading) >= disabledHeadingPixels * 0.85f)

        update { captureSource = false }
        awaitSamples("A missing capture clears to the opaque theme background") {
            it.first == themeBackground && it.second == themeBackground
        }
        update { captureSource = true }
        awaitSamples("The recovered source is blurred again", ::blurred)

        update { available = false }
        awaitSamples("Unsupported rendering matches blur OFF") { it == disabled }

        update { bookmark = null }
        awaitSamples("Dismissing releases the preview background") { edgeContrast(it) == 255 }
        update { available = true; bookmark = saved }
        awaitSamples("Reopening with blur enabled uses the current source", ::blurred)
    }

    @Test
    fun themeBackdropsMatchTheirScrimAndBackground(): Unit = runBlocking {
        val states = listOf(AppTheme.DEFAULT to true, AppTheme.MODERN to false, AppTheme.MODERN to true)
        theme = states.first().first
        dark = states.first().second
        render()
        composeRule.awaitNode(hasText(saved.description))
        for ((nextTheme, nextDark) in states) {
            update { theme = nextTheme; dark = nextDark; enabled = false; captureSource = true }
            val label = "$nextTheme dark=$nextDark"
            awaitSamples("$label: 85% opacity leaves 15% of a black-to-white edge") { edgeContrast(it) in 37..40 }
            update { enabled = true; captureSource = false }
            awaitSamples("$label: a missing capture clears to the opaque theme background") {
                it.first == themeBackground && it.second == themeBackground
            }
        }
    }

    private fun render() {
        val activity = composeRule.activity
        lateinit var softwareView: View
        composeRule.runOnUiThread {
            softwareView = View(activity)
            assertFalse(softwareView.isHardwareAccelerated)
        }
        composeRule.setContent {
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

    private fun update(change: () -> Unit) {
        composeRule.runOnUiThread(change)
        composeRule.waitForIdle()
    }

    private fun sample(): Pair<Int, Int> {
        val sampleOffset = (3 * composeRule.activity.resources.displayMetrics.density).roundToInt()
        val image = automation.takeScreenshot()
        return try {
            val x = image.width / 2
            val y = image.height / 2
            image.getPixel(x - sampleOffset, y) to image.getPixel(x + sampleOffset, y)
        } finally { image.recycle() }
    }

    private fun edgeContrast(pixels: Pair<Int, Int>): Int =
        abs(PixelColor.red(pixels.second) - PixelColor.red(pixels.first))

    private fun blurred(pixels: Pair<Int, Int>) = edgeContrast(pixels) in 1..16

    private fun sharpTextPixels(text: SemanticsNode): Int {
        // Screenshots are in screen pixels; the test window need not start at the screen origin.
        val left = text.positionOnScreen.x.toInt()
        val top = text.positionOnScreen.y.toInt()
        val image = automation.takeScreenshot()
        return try {
            var count = 0
            for (y in top until top + text.size.height) {
                for (x in left until left + text.size.width) {
                    if (image.getPixel(x, y) == themeText) count++
                }
            }
            count
        } finally { image.recycle() }
    }

    /** Screenshots are the heavy wait tier: at most 10 samples, 100ms apart, after idle. */
    private suspend fun awaitSamples(description: String, matches: (Pair<Int, Int>) -> Boolean): Pair<Int, Int> {
        composeRule.waitForIdle()
        var pixels = sample()
        var attempt = 1
        while (!matches(pixels) && attempt++ < 10) {
            delay(100)
            pixels = sample()
        }
        if (!matches(pixels)) throw AssertionError("$description: contrast=${edgeContrast(pixels)}, pixels=$pixels")
        return pixels
    }
}
