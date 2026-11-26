package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.ChromeBlurBackground
import me.bubblespark.tidilo.ui.components.ScreenBackdropScope
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.components.screenBackdropSource
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ScreenBackdropSourceLossTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun maskAttenuatesTheCompositedBackgroundExactlyOnce(): Unit = runBlocking {
        var alpha by mutableStateOf(1f)
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false) {
                ScreenBackdropScope { state ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().screenBackdropSource(state).background(Color.Blue))
                        val mask = remember(alpha) {
                            Brush.verticalGradient(listOf(Color.White.copy(alpha = alpha), Color.White.copy(alpha = alpha)))
                        }
                        ChromeBlurBackground(RectangleShape, Color(0xFF808080), Modifier.size(220.dp), mask, outline = false)
                    }
                }
            }
        }
        val full = composeRule.sampleScreen("full alpha backdrop") { frame ->
            android.graphics.Color.red(frame.getPixel(frame.width / 2, frame.height / 2)).takeIf { it > 0 }
        }
        composeRule.runOnUiThread { alpha = .5f }
        val half = composeRule.sampleScreen("half alpha backdrop") { frame ->
            val red = android.graphics.Color.red(frame.getPixel(frame.width / 2, frame.height / 2))
            red.takeIf { abs(full * .5f - it.toFloat()) <= 2f }
        }
        assertEquals("Mask must attenuate the final composite, not accumulate across layers", full * .5f, half.toFloat(), 2f)
    }

    @Test
    fun overlappingDestinationBodyIsExcludedFromChromeInput(): Unit = runBlocking {
        var own by mutableStateOf(Color.Red)
        var other by mutableStateOf(Color.Blue)
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScreenBackdropScope { first ->
                        Box(Modifier.fillMaxSize().screenBackdropSource(first).background(own))
                        ScreenBackdropScope { second ->
                            Box(Modifier.fillMaxSize().screenBackdropSource(second).background(other))
                        }
                        ChromeBlurBackground(RectangleShape, Color(0xFF404040), Modifier.size(220.dp), outline = false)
                    }
                }
            }
        }
        val first = composeRule.sampleScreen("owner red body") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2)
        }
        composeRule.runOnUiThread { other = Color.Green }
        composeRule.waitForIdle()
        val afterOther = captureScreen { frame -> frame.getPixel(frame.width / 2, frame.height / 2) }
        assertEquals("Another destination must not enter the input", first, afterOther)

        composeRule.runOnUiThread { own = Color.Blue }
        val updated = composeRule.sampleScreen("owner blue body") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2).takeIf { it != first }
        }
        assertNotEquals("The owner body must remain live", first, updated)
    }

    @Test
    fun transparentBodyIncludesStationaryCanvas(): Unit = runBlocking {
        var theme by mutableStateOf(AppTheme.DEFAULT)
        composeRule.setContent {
            TidiloTheme(theme = theme, darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TidiloBackground(Modifier.fillMaxSize())
                    ScreenBackdropScope { state ->
                        Box(Modifier.fillMaxSize().screenBackdropSource(state))
                        ChromeBlurBackground(RectangleShape, Color(0xFF404040), Modifier.size(220.dp), outline = false)
                    }
                }
            }
        }
        val initial = composeRule.sampleScreen("default theme stationary canvas") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2)
        }
        composeRule.runOnUiThread { theme = AppTheme.MODERN }
        val updated = composeRule.sampleScreen("modern theme stationary canvas") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2).takeIf { it != initial }
        }
        assertNotEquals("Transparent body must include the stationary canvas, not the fallback tint", initial, updated)
    }

    @Test
    fun disappearingBodyClearsToLocalFallback(): Unit = runBlocking {
        var present by mutableStateOf(true)
        val tint = Color(0xFFE0EAE8)
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false) {
                ScreenBackdropScope { state ->
                    Box(Modifier.fillMaxSize().background(Color.Red), contentAlignment = Alignment.Center) {
                        if (present) Box(Modifier.fillMaxSize().screenBackdropSource(state).background(Color.Blue))
                        ChromeBlurBackground(RectangleShape, tint, Modifier.size(220.dp), outline = false)
                    }
                }
            }
        }
        val captured = composeRule.sampleScreen("captured blue source") { image ->
            image.getPixel(image.width / 2, image.height / 2).takeIf { it != tint.toArgb() }
        }
        assertNotEquals("The source must have been captured", tint.toArgb(), captured)
        composeRule.runOnUiThread { present = false }
        val cleared = composeRule.sampleScreen("cleared local fallback") { image ->
            image.getPixel(image.width / 2, image.height / 2).takeIf { it == tint.toArgb() }
        }
        assertEquals(tint.toArgb(), cleared)
    }
}
