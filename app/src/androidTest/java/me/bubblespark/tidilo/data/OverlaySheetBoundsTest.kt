package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySheetBoundsTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun nativeSheetTransformsWrapTheBlurLayer(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { TidiloTheme {
                Box(Modifier.fillMaxSize().background(Color.Blue))
                TidiloModalBottomSheet({}, modifier = Modifier.testTag("sheet-transform")) { Box(Modifier.height(200.dp)) }
            } } }
            val sheet = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "sheet-transform" }
            host.main {
                val names = sheet.layoutInfo.getModifierInfo().map { it.modifier.javaClass.simpleName }
                val blur = names.indexOfFirst { it.contains("TypedHazeEffect") }
                val lastTransform = names.indexOfLast { it.contains("GraphicsLayer") }
                assertTrue("The real native predictive-back/overscroll transforms must wrap blur: $names",
                    blur > 0 && lastTransform < blur)
            }
        } finally { host.main { activity.finish() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun frostNeverPaintsAboveTheNativeSheet(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        try {
            host.main { activity.setContent { TidiloTheme(blurEnabled = enabled, dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color.Blue))
                TidiloModalBottomSheet({}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Box(Modifier.fillMaxWidth().height(200.dp))
                }
            } } }
            delay(700)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { enabled = true }
            delay(500)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                // A fading scrim may change blue intensity, but must never add the theme tint.
                val outside = after.getPixel(after.width / 2, 250)
                assertEquals("No frost tint above the sheet", 0, android.graphics.Color.red(outside))
                assertEquals("No frost tint above the sheet", 0, android.graphics.Color.green(outside))
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
