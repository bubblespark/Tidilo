package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.FrostedHeaderContainer
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenBackdropRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun headerSamplesBodyWhileForegroundRemainsSharp(): Unit = runBlocking {
        var body by mutableStateOf(Color.Red)
        var enabled by mutableStateOf(true)
        var striped by mutableStateOf(true)
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = enabled) {
                TidiloScreenScaffold(header = {
                    FrostedHeaderContainer(Modifier.testTag("frosted-header")) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .testTag("frosted-header-content"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(16.dp).background(Color.Magenta))
                            }
                        }
                    }
                }) {
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            drawRect(body)
                            if (striped) {
                                val stripe = 8.dp.toPx()
                                var x = 0f
                                while (x < size.width) {
                                    drawRect(
                                        if ((x / stripe).toInt() % 2 == 0) Color.Red else Color.Blue,
                                        Offset(x, 0f),
                                        Size(stripe, size.height)
                                    )
                                    x += stripe
                                }
                            }
                        }
                    )
                }
            }
        }
        val header = composeRule.awaitNode(hasTestTag("frosted-header"))
        val content = composeRule.awaitNode(hasTestTag("frosted-header-content"))
        val contentLeft = content.positionOnScreen.x
        val contentTop = content.positionOnScreen.y
        val contentWidth = content.size.width.toFloat()
        val contentHeight = content.size.height.toFloat()
        val headerBottom = (header.positionOnScreen.y + header.size.height).toInt()

        fun samplePixel(frame: Bitmap, fractionY: Float = .3f): Int =
            frame.getPixel((contentLeft + contentWidth * .25f).toInt(), (contentTop + contentHeight * fractionY).toInt())

        fun contrast(frame: Bitmap): Int {
            val y = (contentTop + contentHeight * .25f).toInt()
            val startX = (contentLeft + contentWidth / 4f).toInt()
            val endX = (contentLeft + contentWidth * 3f / 4f).toInt()
            val values = (startX until endX).map { android.graphics.Color.red(frame.getPixel(it, y)) }
            return values.max() - values.min()
        }

        val blurredContrast = composeRule.sampleScreen("blurred header stripes") { frame ->
            contrast(frame).takeIf { it < 120 }
        }
        composeRule.runOnUiThread { enabled = false }
        val plainContrast = composeRule.sampleScreen("unblurred header stripes") { frame ->
            contrast(frame).takeIf { it > blurredContrast * 2 }
        }
        assertTrue("Body stripes must be blurred: ON=$blurredContrast OFF=$plainContrast", blurredContrast < plainContrast / 2)

        composeRule.runOnUiThread {
            enabled = true
            striped = false
        }
        val red = composeRule.sampleScreen("solid red body under frosted header") { frame ->
            samplePixel(frame).takeIf { android.graphics.Color.red(it) > android.graphics.Color.blue(it) }
        }
        composeRule.runOnUiThread { body = Color.Blue }
        val (blue, hasSharpMarker, tail) = composeRule.sampleScreen("solid blue body under frosted header") { frame ->
            val p = samplePixel(frame)
            if (p != red && android.graphics.Color.blue(p) > android.graphics.Color.red(p)) {
                val markerX = (contentLeft + contentWidth / 2f).toInt()
                val marker = (contentTop.toInt() until (contentTop + contentHeight).toInt()).any {
                    frame.getPixel(markerX, it) == Color.Magenta.toArgb()
                }
                val tailPixel = frame.getPixel((contentLeft + contentWidth * .25f).toInt(), headerBottom - 2)
                Triple(p, marker, tailPixel)
            } else {
                null
            }
        }
        assertNotEquals("Header must consume the underlying body, not just its own tint", red, blue)
        assertTrue("Foreground marker must remain sharp", hasSharpMarker)
        assertTrue(
            "Fade tail must expose the blue body",
            android.graphics.Color.blue(tail) > 245 && android.graphics.Color.red(tail) < 10
        )

        composeRule.runOnUiThread { enabled = false }
        val off = composeRule.sampleScreen("unblurred blue body") { frame ->
            samplePixel(frame).takeIf { it != blue }
        }
        composeRule.runOnUiThread { body = Color.Green }
        val green = composeRule.sampleScreen("unblurred green body") { frame ->
            samplePixel(frame).takeIf { it != off && android.graphics.Color.green(it) > android.graphics.Color.blue(it) }
        }
        // The ordinary translucent header still responds to body color; it must not retain blue captured pixels.
        assertNotEquals(off, green)
    }
}
