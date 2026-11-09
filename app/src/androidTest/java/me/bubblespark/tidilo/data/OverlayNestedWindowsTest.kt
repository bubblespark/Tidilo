package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class OverlayNestedWindowsTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun siblingConfirmationSamplesChangesInTheSheetBelow(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var lowerColor by mutableStateOf(Color.Green)
        var confirmation by mutableStateOf(false)
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(Color.Red))
                TidiloModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Box(Modifier.fillMaxWidth().height(650.dp).background(lowerColor))
                }
                if (confirmation) TidiloAlertDialog({}, {}, title = {
                    Box(Modifier.fillMaxWidth().height(140.dp))
                })
            } } }
            delay(600)
            host.main { confirmation = true }
            delay(500)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { lowerColor = Color.Blue }
            delay(500)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                for ((name, bitmap) in listOf("nested-before.png" to before, "nested-after.png" to after)) {
                    java.io.File(host.context.cacheDir, name).outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                val x = after.width / 2
                val y = after.height / 2
                assertNotEquals("The upper dialog must capture the changed sheet, not only the red activity",
                    before.getPixel(x, y), after.getPixel(x, y))
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
