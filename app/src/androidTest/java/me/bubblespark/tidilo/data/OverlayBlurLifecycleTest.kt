package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.chrisbanes.haze.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class OverlayBlurLifecycleTest {
    @Test fun missingHostUsesOrdinaryDrawing() {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme { assertNull(rememberOverlayBlurLayer(true)) } } }
        } finally { host.main { activity.finish() } }
    }

    @Test fun siblingLayersReleaseAfterTogglingAndDismissal(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var controller: OverlayBlurController? = null
        try {
            host.main { activity.setContent { TidiloTheme(blurEnabled = enabled) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                if (visible) repeat(2) { TidiloAlertDialog({}, {}, title = {}) }
            } } }
            delay(400)
            host.main { assertEquals(2, controller!!.activeCount) }
            repeat(4) {
                host.main { enabled = false }
                delay(150)
                host.main { assertEquals(0, controller!!.activeCount); enabled = true }
                delay(150)
                host.main { assertEquals(2, controller!!.activeCount) }
            }
            host.main { visible = false }
            delay(250)
            host.main { assertEquals(0, controller!!.activeCount) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun sourceLossClearsPixelsToReadableOpaqueFallback(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var sourceVisible by mutableStateOf(true)
        val tint = Color(0xFFE0EAE8)
        try {
            host.main { activity.setContent { MaterialTheme {
                val haze = rememberHazeState()
                if (sourceVisible) Box(Modifier.fillMaxSize().hazeSource(haze, 0f).background(Color.Green))
                val layer = remember(haze) { OverlayBlurLayer(1L, Modifier.hazeSource(haze, 1f),
                    HazeInput.Sources(haze, HazeSourceSelection.All.where { it.zIndex < 1f }, HazeSourceRetention.ClearWhenUnavailable)) }
                Dialog({}) { Surface(Modifier.size(200.dp).overlayBlurBackground(layer, RoundedCornerShape(20.dp), tint),
                    color = Color.Transparent) {} }
            } } }
            delay(500)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { sourceVisible = false }
            delay(500)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                val x = after.width / 2
                val y = after.height / 2
                assertNotEquals(tint.toArgb(), before.getPixel(x, y))
                assertEquals(tint.toArgb(), after.getPixel(x, y))
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
