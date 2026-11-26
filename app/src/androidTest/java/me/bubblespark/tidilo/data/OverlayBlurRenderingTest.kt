package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.overlayBlurBackground
import me.bubblespark.tidilo.ui.components.rememberOverlayBlurLayer
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class OverlayBlurRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun sourceBackedDialogChangesOnlyItsBackgroundAndKeepsForegroundSharp(): Unit = runBlocking {
        var enabled by mutableStateOf(false)
        val tint = Color(0xFFE0EAE8)
        composeRule.setContent {
            TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
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
                )
                val layer = rememberOverlayBlurLayer(true)
                Dialog(onDismissRequest = {}) {
                    Surface(
                        Modifier.size(260.dp, 200.dp).overlayBlurBackground(layer, RoundedCornerShape(28.dp), tint),
                        shape = RoundedCornerShape(28.dp),
                        color = if (layer == null) tint else Color.Transparent
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(16.dp).background(Color.Magenta))
                        }
                    }
                }
            }
        }
        val (y, outsideBefore, surfaceBefore) = composeRule.sampleScreen("unblurred dialog with foreground marker") { frame ->
            val x = frame.width / 2
            val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
            if (rows.isEmpty()) return@sampleScreen null
            val row = (rows.first() + rows.last()) / 2
            Triple(row, frame.getPixel(20, row), frame.getPixel(x + 80, row))
        }
        composeRule.runOnUiThread { enabled = true }
        val (centerAfter, outsideAfter, surfaceAfter) = composeRule.sampleScreen("frosted dialog sampling striped source") { frame ->
            val x = frame.width / 2
            val center = frame.getPixel(x, y)
            val surface = frame.getPixel(x + 80, y)
            if (center == Color.Magenta.toArgb() && surface != surfaceBefore) {
                Triple(center, frame.getPixel(20, y), surface)
            } else {
                null
            }
        }
        assertEquals(Color.Magenta.toArgb(), centerAfter)
        assertEquals(outsideBefore, outsideAfter)
        assertNotEquals(
            "Frost must sample the striped source, rather than draw an ordinary opaque tint",
            surfaceBefore,
            surfaceAfter
        )
    }
}

/** Captures the screen and always recycles the [Bitmap] after [read] returns. */
internal fun <T> captureScreen(read: (Bitmap) -> T): T {
    val frame = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    return try {
        read(frame)
    } finally {
        frame.recycle()
    }
}

/**
 * Samples the rendered screen after [ComposeTestRule.waitForIdle] up to [maxAttempts] times spaced
 * [intervalMs] apart until [read] returns a non-null result.
 */
internal suspend fun <T : Any> ComposeTestRule.sampleScreen(
    label: String,
    describe: (Bitmap) -> String = { "" },
    intervalMs: Long = 150L,
    maxAttempts: Int = 10,
    read: (Bitmap) -> T?
): T {
    require(intervalMs >= 100L) { "Screenshot polling interval must be >= 100ms, was $intervalMs" }
    var last = ""
    repeat(maxAttempts) { attempt ->
        waitForIdle()
        captureScreen { frame -> read(frame).also { if (it == null) last = describe(frame) } }?.let { return it }
        if (attempt < maxAttempts - 1) delay(intervalMs)
    }
    throw AssertionError("The screen never showed $label. Last sample: $last")
}
