package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class ScreenBackdropSourceLossTest {
    @Test fun disappearingBodyClearsToLocalFallback(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var present by mutableStateOf(true)
        val tint = Color(0xFFE0EAE8)
        try {
            host.main { activity.setContent { TidiloTheme(darkTheme = false, dynamicColor = false) {
                ScreenBackdropScope { state ->
                    Box(Modifier.fillMaxSize().background(Color.Red), contentAlignment = Alignment.Center) {
                        if (present) Box(Modifier.fillMaxSize().screenBackdropSource(state).background(Color.Blue))
                        ChromeBlurBackground(RectangleShape, tint, Modifier.size(220.dp), outline = false)
                    }
                }
            } } }
            fun pixel(): Int {
                val image = host.instrumentation.uiAutomation.takeScreenshot()
                return try { image.getPixel(image.width / 2, image.height / 2) } finally { image.recycle() }
            }
            delay(500)
            val captured = pixel()
            assertNotEquals("The source must have been captured", tint.toArgb(), captured)
            host.main { present = false }
            withTimeout(5000) { while (pixel() != tint.toArgb()) delay(40) }
        } finally { host.main { activity.finish() } }
    }
}
