package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class ScreenBackdropRenderingTest {
    @Test fun headerSamplesBodyWhileForegroundRemainsSharp(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var body by mutableStateOf(Color.Red)
        var enabled by mutableStateOf(true)
        var striped by mutableStateOf(true)
        var bounds = Rect.Zero
        try {
            host.main { activity.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = enabled) {
                TidiloScreenScaffold(header = {
                    FrostedHeaderContainer(Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(16.dp).background(Color.Magenta))
                        }
                    }
                }) { Box(Modifier.fillMaxSize().drawBehind {
                    drawRect(body)
                    if (striped) {
                        val stripe = 8.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawRect(if ((x / stripe).toInt() % 2 == 0) Color.Red else Color.Blue, Offset(x, 0f), Size(stripe, size.height))
                            x += stripe
                        }
                    }
                }) }
            } } }
            delay(700)
            fun pixel(fractionY: Float = .3f): Int {
                val frame = host.instrumentation.uiAutomation.takeScreenshot()
                return try { frame.getPixel((bounds.left + bounds.width * .25f).toInt(), (bounds.top + bounds.height * fractionY).toInt()) }
                finally { frame.recycle() }
            }
            fun contrast(): Int {
                val frame = host.instrumentation.uiAutomation.takeScreenshot()
                return try {
                    val y = (bounds.top + bounds.height * .25f).toInt()
                    val values = (frame.width / 4 until frame.width * 3 / 4).map { android.graphics.Color.red(frame.getPixel(it, y)) }
                    values.max() - values.min()
                } finally { frame.recycle() }
            }
            val blurredContrast = contrast()
            host.main { enabled = false }
            delay(400)
            val plainContrast = contrast()
            assertTrue("Body stripes must be blurred: ON=$blurredContrast OFF=$plainContrast", blurredContrast < plainContrast / 2)
            host.main { enabled = true; striped = false }
            delay(400)
            val red = pixel()
            host.main { body = Color.Blue }
            delay(500)
            assertNotEquals("Header must consume the underlying body, not just its own tint", red, pixel())
            val frame = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                assertTrue("Foreground marker must remain sharp", (0 until frame.height).any { frame.getPixel(frame.width / 2, it) == Color.Magenta.toArgb() })
                val tail = frame.getPixel((bounds.left + bounds.width * .25f).toInt(), bounds.bottom.toInt() - 2)
                assertTrue("Fade tail must expose the blue body", android.graphics.Color.blue(tail) > 245 && android.graphics.Color.red(tail) < 10)
            } finally { frame.recycle() }
            host.main { enabled = false }
            delay(400)
            val off = pixel()
            host.main { body = Color.Green }
            delay(400)
            // The ordinary translucent header still responds to body color; it must not retain blue captured pixels.
            assertNotEquals(off, pixel())
        } finally { host.main { activity.finish() } }
    }
}
