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
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class OverlayNestedWindowsTest {
    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun siblingConfirmationSamplesChangesInTheSheetBelow(): Unit = runBlocking {
        var lowerColor by mutableStateOf(Color.Green)
        var confirmation by mutableStateOf(false)
        composeRule.setContent {
            TidiloTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color.Red))
                TidiloModalBottomSheet(
                    onDismissRequest = {},
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Box(Modifier.fillMaxWidth().height(650.dp).background(lowerColor).testTag("nested-lower-sheet"))
                }
                if (confirmation) {
                    TidiloAlertDialog(
                        {},
                        {},
                        title = { Box(Modifier.fillMaxWidth().height(140.dp).testTag("nested-upper-dialog")) }
                    )
                }
            }
        }
        composeRule.awaitNode(hasTestTag("nested-lower-sheet"))
        composeRule.runOnUiThread { confirmation = true }
        composeRule.awaitNode(hasTestTag("nested-upper-dialog"))

        val before = composeRule.sampleScreen("upper dialog over green sheet") { frame ->
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            pixel.takeIf { android.graphics.Color.green(it) > android.graphics.Color.blue(it) }
        }
        composeRule.runOnUiThread { lowerColor = Color.Blue }
        val after = composeRule.sampleScreen("upper dialog over updated blue sheet") { frame ->
            val pixel = frame.getPixel(frame.width / 2, frame.height / 2)
            pixel.takeIf { it != before && android.graphics.Color.blue(it) > android.graphics.Color.green(it) }
        }
        assertNotEquals(
            "The upper dialog must capture the changed sheet, not only the red activity",
            before,
            after
        )
    }
}
