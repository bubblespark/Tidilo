package me.bubblespark.tidilo.data

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OverlaySheetBoundsTest {
    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun nativeSheetTransformsWrapTheBlurLayer() {
        composeRule.setContent {
            TidiloTheme {
                Box(Modifier.fillMaxSize().background(Color.Blue))
                TidiloModalBottomSheet({}, modifier = Modifier.testTag("sheet-transform")) {
                    Box(Modifier.height(200.dp))
                }
            }
        }
        val sheet = composeRule.awaitNode(hasTestTag("sheet-transform"))
        composeRule.runOnUiThread {
            val names = sheet.layoutInfo.getModifierInfo().map { it.modifier.javaClass.simpleName }
            val blur = names.indexOfFirst { it.contains("TypedHazeEffect") }
            val lastTransform = names.indexOfLast { it.contains("GraphicsLayer") }
            assertTrue(
                "The real native predictive-back/overscroll transforms must wrap blur: $names",
                blur > 0 && lastTransform < blur
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun frostNeverPaintsAboveTheNativeSheet(): Unit = runBlocking {
        var enabled by mutableStateOf(false)
        composeRule.setContent {
            TidiloTheme(blurEnabled = enabled, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color.Blue))
                TidiloModalBottomSheet({}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Box(Modifier.fillMaxWidth().height(200.dp).testTag("sheet-bounds-body"))
                }
            }
        }
        val bodyNode = composeRule.awaitNode(hasTestTag("sheet-bounds-body"))
        val bodyX = (bodyNode.positionOnScreen.x + bodyNode.size.width / 2f).toInt()
        val bodyY = (bodyNode.positionOnScreen.y + bodyNode.size.height / 2f).toInt()
        composeRule.waitForIdle()
        val beforeBody = captureScreen { it.getPixel(bodyX, bodyY) }
        composeRule.runOnUiThread { enabled = true }
        val outside = composeRule.sampleScreen("frosted native sheet") { after ->
            if (after.getPixel(bodyX, bodyY) != beforeBody) {
                after.getPixel(after.width / 2, 250)
            } else {
                null
            }
        }
        // A fading scrim may change blue intensity, but must never add the theme tint.
        assertEquals("No frost tint above the sheet", 0, android.graphics.Color.red(outside))
        assertEquals("No frost tint above the sheet", 0, android.graphics.Color.green(outside))
    }
}
