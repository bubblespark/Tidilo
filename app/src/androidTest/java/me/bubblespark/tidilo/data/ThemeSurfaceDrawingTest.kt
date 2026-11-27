package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.ResolvedTidiloSurface
import me.bubblespark.tidilo.ui.theme.*
import org.junit.Rule
import org.junit.Test

/** Actual painted pixels: background ownership cannot be proven by modifier identity. */
class ThemeSurfaceDrawingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun decoratedZeroElevationDrawsOpaqueFillAndBorder() = runBlocking {
        val fill = Color(0xFF995533)
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.padding(30.dp)) {
                    ResolvedTidiloSurface(
                        ResolvedSurfaceStyle(containerColor = fill, treatment = SurfaceTreatment.DECORATED,
                            elevation = 0.dp, borderWidth = 1.dp, borderColor = Color.Black),
                        Modifier.size(180.dp, 100.dp).testTag("painted-surface")
                    ) { Text("Sharp content") }
                }
            }
        }
        val node = composeRule.awaitNode(hasTestTag("painted-surface"))
        val left = node.positionOnScreen.x
        val top = node.positionOnScreen.y
        val centerX = (left + node.size.width / 2f).toInt()
        val bottom = top + node.size.height
        composeRule.sampleScreen("opaque fill and dark top border", describe = { frame ->
            "fill=${Integer.toHexString(frame.getPixel(centerX, (bottom - 25).toInt()))}"
        }) { frame ->
            Unit.takeIf {
                frame.getPixel(centerX, (bottom - 25).toInt()) == fill.toArgb() &&
                    (0..3).any { offset -> android.graphics.Color.red(frame.getPixel(centerX, top.toInt() + offset)) < 70 }
            }
        }
        composeRule.onNodeWithText("Sharp content").assertExists()
        Unit
    }
}
