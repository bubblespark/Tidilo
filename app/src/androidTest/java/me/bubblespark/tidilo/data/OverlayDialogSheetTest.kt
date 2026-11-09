package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorContent
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorState
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class OverlayDialogSheetTest {
    @Test fun importDraftSurvivesBlurAndThemeChanges(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(true)
        var theme by mutableStateOf(me.bubblespark.tidilo.config.AppTheme.DEFAULT)
        try {
            host.main { activity.setContent { TidiloTheme(theme = theme, blurEnabled = enabled) { ImportUrlDialog({}, {}) } } }
            val field = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("https://example.com/retained")) }
            for (next in listOf(false, true, false)) {
                host.main { enabled = next; theme = if (next) me.bubblespark.tidilo.config.AppTheme.MODERN else me.bubblespark.tidilo.config.AppTheme.DEFAULT }
                host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://example.com/retained" }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun importDialogUsesFrostedBackground() = verify { ImportUrlDialog({}, {}) }
    @Test fun collectionSheetUsesFrostedBackground() = verify {
        CollectionEditorContent(CollectionEditorState(CollectionDraft(title = "Fixture")), true, {}, {}, {}, {}, {})
    }

    private fun verify(overlay: @Composable () -> Unit): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize().background(Color(0xFF003399)))
                overlay()
            } } }
            delay(700)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { enabled = true }
            delay(700)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                var changed = 0
                for (y in after.height / 4 until after.height * 3 / 4 step 3) {
                    for (x in after.width / 4 until after.width * 3 / 4 step 3) {
                        if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                    }
                }
                assertTrue("Enabling frost must change the actual native surface background", changed > 1000)
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
