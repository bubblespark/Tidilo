package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.LocalOverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurLayer
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.overlayBlurBackground
import me.bubblespark.tidilo.ui.components.rememberOverlayBlurLayer
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class OverlayBlurLifecycleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun missingHostUsesOrdinaryDrawing() {
        var observed: OverlayBlurLayer? = null
        var measured = false
        composeRule.setContent {
            MaterialTheme {
                observed = rememberOverlayBlurLayer(true)
                measured = true
            }
        }
        composeRule.waitForIdle()
        assertEquals(true, measured)
        assertNull(observed)
    }

    @Test
    fun siblingLayersReleaseAfterTogglingAndDismissal() {
        var enabled by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var controller: OverlayBlurController? = null
        composeRule.setContent {
            TidiloTheme(blurEnabled = enabled) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                if (visible) repeat(2) { TidiloAlertDialog({}, {}, title = {}) }
            }
        }
        fun awaitCount(expected: Int) {
            composeRule.waitUntil(5_000) {
                var count = -1
                composeRule.runOnUiThread { count = controller?.activeCount ?: -1 }
                count == expected
            }
        }
        awaitCount(2)
        repeat(4) {
            composeRule.runOnUiThread { enabled = false }
            awaitCount(0)
            composeRule.runOnUiThread { enabled = true }
            awaitCount(2)
        }
        composeRule.runOnUiThread { visible = false }
        awaitCount(0)
    }

    @Test
    fun sourceLossClearsPixelsToReadableOpaqueFallback(): Unit = runBlocking {
        var sourceVisible by mutableStateOf(true)
        val tint = Color(0xFFE0EAE8)
        composeRule.setContent {
            MaterialTheme {
                val haze = rememberHazeState()
                if (sourceVisible) Box(Modifier.fillMaxSize().hazeSource(haze, 0f).background(Color.Green))
                val layer = remember(haze) {
                    OverlayBlurLayer(
                        1L,
                        Modifier.hazeSource(haze, 1f),
                        HazeInput.Sources(
                            haze,
                            HazeSourceSelection.All.where { it.zIndex < 1f },
                            HazeSourceRetention.ClearWhenUnavailable
                        )
                    )
                }
                Dialog({}) {
                    Surface(
                        Modifier.size(200.dp).overlayBlurBackground(layer, RoundedCornerShape(20.dp), tint),
                        color = Color.Transparent
                    ) {}
                }
            }
        }
        val before = composeRule.sampleScreen("source-backed overlay") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2).takeIf { it != tint.toArgb() }
        }
        assertNotEquals(tint.toArgb(), before)
        composeRule.runOnUiThread { sourceVisible = false }
        val after = composeRule.sampleScreen("opaque fallback tint") { frame ->
            frame.getPixel(frame.width / 2, frame.height / 2).takeIf { it == tint.toArgb() }
        }
        assertEquals(tint.toArgb(), after)
    }
}
