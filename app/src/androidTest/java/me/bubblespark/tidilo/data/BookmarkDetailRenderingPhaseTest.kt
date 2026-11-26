package me.bubblespark.tidilo.data

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.library.detailBannerLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Color updates must repaint the approved perimeter without invalidating its composition. */
class BookmarkDetailRenderingPhaseTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun animatedColorRepaintsWithoutRecomposingAndPreservesApprovedRim(): Unit = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val color = mutableStateOf(Color(0xFFFFAA00))
        val compositions = AtomicInteger()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Row(Modifier.fillMaxSize().background(Color(0xFF141414)).padding(horizontal = 24.dp, vertical = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Candidate(color, compositions)
                    Reference(color)
                }
            }
        }
        val a = composeRule.awaitNode(hasTestTag(CANDIDATE)).screenRect()
        val b = composeRule.awaitNode(hasTestTag(REFERENCE)).screenRect()
        var initialCompositions = 0
        for ((index, target) in listOf(Color(0xFFFFAA00), Color(0xFF00CCFF), Color(0xFF66DD33)).withIndex()) {
            composeRule.runOnUiThread { color.value = target }
            composeRule.waitForIdle()
            val components = listOf(target.red, target.green, target.blue)
            val high = components.indices.maxBy { components[it] }
            val low = components.indices.minBy { components[it] }
            var painted = false
            var attempt = 0
            // Screenshots are the heavy wait tier: at most 10 samples, 100ms apart.
            while (!painted) {
                val bitmap = automation.takeScreenshot()
                try {
                    fun channels(pixel: Int) = listOf(android.graphics.Color.red(pixel),
                        android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                    val actual = channels(bitmap.getPixel(a.centerX(), a.top))
                    val expected = channels(bitmap.getPixel(b.centerX(), b.top))
                    painted = actual[high] - actual[low] > 30 && expected[high] - expected[low] > 30
                    if (painted) {
                        val samples = mutableListOf<Pair<Int, Int>>()
                        // Straight edges and all four rounded corner quadrants.
                        for (offset in 0..3) {
                            samples += a.width() / 2 to offset
                            samples += a.width() / 2 to a.height() - 1 - offset
                            samples += offset to a.height() / 2
                            samples += a.width() - 1 - offset to a.height() / 2
                        }
                        for (x in 8..31 step 2) for (y in 8..31 step 2) {
                            // Compare the approved image/rim footprint, excluding the separate outer bloom.
                            if ((x + 0.5f - 32) * (x + 0.5f - 32) + (y + 0.5f - 32) * (y + 0.5f - 32) > 32 * 32) continue
                            samples += x to y
                            samples += a.width() - 1 - x to y
                            samples += x to a.height() - 1 - y
                            samples += a.width() - 1 - x to a.height() - 1 - y
                        }
                        for ((x, y) in samples) {
                            val left = channels(bitmap.getPixel(a.left + x, a.top + y))
                            val right = channels(bitmap.getPixel(b.left + x, b.top + y))
                            assertTrue("Approved rim differs at ($x,$y): $left vs $right",
                                left.indices.all { abs(left[it] - right[it]) <= 4 })
                        }
                    }
                } finally { bitmap.recycle() }
                if (!painted) {
                    assertTrue("$target was not painted within 10 samples", ++attempt < 10)
                    delay(100)
                }
            }
            if (index == 0) initialCompositions = compositions.get() else {
                assertEquals("Changing the painted accent must not recompose the banner", initialCompositions, compositions.get())
            }
        }
    }

    private fun SemanticsNode.screenRect(): Rect {
        val left = positionOnScreen.x.roundToInt()
        val top = positionOnScreen.y.roundToInt()
        return Rect(left, top, left + size.width, top + size.height)
    }

    @Composable
    private fun Candidate(color: State<Color>, compositions: AtomicInteger) {
        val modifier = Modifier.size(180.dp, 110.dp).testTag(CANDIDATE).detailBannerLight(color)
        SideEffect { compositions.incrementAndGet() }
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF183F52)))
    }

    @Composable
    private fun Reference(color: State<Color>) {
        val accent = color.value
        Box(Modifier.size(180.dp, 110.dp).testTag(REFERENCE)
            .shadow(8.dp, RoundedCornerShape(16.dp), clip = false,
                ambientColor = accent.copy(alpha = 0.16f), spotColor = accent.copy(alpha = 0.24f))
            .border(1.dp, accent.copy(alpha = accent.alpha * 0.60f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)).background(Color(0xFF183F52)))
    }

    private companion object {
        const val CANDIDATE = "rendering_candidate"
        const val REFERENCE = "rendering_reference"
    }
}
