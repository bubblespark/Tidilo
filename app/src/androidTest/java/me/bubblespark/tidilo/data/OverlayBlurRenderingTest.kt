package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class OverlayBlurRenderingTest {
    @Test fun sourceBackedDialogChangesOnlyItsBackgroundAndKeepsForegroundSharp(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        val tint = Color(0xFFE0EAE8)
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize().drawBehind {
                    val stripe = 8.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawRect(if ((x / stripe).toInt() % 2 == 0) Color.Red else Color.Blue,
                            Offset(x, 0f), Size(stripe, size.height))
                        x += stripe
                    }
                })
                val layer = rememberOverlayBlurLayer(true)
                Dialog(onDismissRequest = {}) {
                    Surface(Modifier.size(260.dp, 200.dp).overlayBlurBackground(layer, RoundedCornerShape(28.dp), tint),
                        shape = RoundedCornerShape(28.dp), color = if (layer == null) tint else Color.Transparent) {
                        Box(contentAlignment = Alignment.Center) { Box(Modifier.size(16.dp).background(Color.Magenta)) }
                    }
                }
            } } }
            delay(500)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { enabled = true }
            delay(600)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                val x = after.width / 2
                val markerRows = (0 until before.height).filter { before.getPixel(x, it) == Color.Magenta.toArgb() }
                assertTrue("Fixture must render the sharp foreground marker", markerRows.isNotEmpty())
                val y = (markerRows.first() + markerRows.last()) / 2
                for ((name, bitmap) in listOf("overlay-before.png" to before, "overlay-after.png" to after)) {
                    java.io.File(host.context.cacheDir, name).outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertEquals(Color.Magenta.toArgb(), after.getPixel(x, y))
                assertEquals(before.getPixel(20, y), after.getPixel(20, y))
                assertNotEquals("Frost must sample the striped source, rather than draw an ordinary opaque tint",
                    before.getPixel(x + 80, y), after.getPixel(x + 80, y))
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
