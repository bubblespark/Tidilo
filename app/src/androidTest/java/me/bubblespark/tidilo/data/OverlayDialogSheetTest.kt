package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorContent
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorState
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OverlayDialogSheetTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun importDraftSurvivesBlurAndThemeChanges() {
        var enabled by mutableStateOf(true)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        composeRule.setContent {
            TidiloTheme(theme = theme, blurEnabled = enabled) {
                ImportUrlDialog({}, {})
            }
        }
        val field = composeRule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        composeRule.runOnUiThread {
            check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("https://example.com/retained")) == true)
        }
        for (next in listOf(false, true, false)) {
            composeRule.runOnUiThread {
                enabled = next
                theme = if (next) AppTheme.MODERN else AppTheme.DEFAULT
            }
            composeRule.awaitNode(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("https://example.com/retained"))
            )
        }
    }

    @Test
    fun importDialogUsesFrostedBackground() =
        verify(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)) { ImportUrlDialog({}, {}) }

    @Test
    fun collectionSheetUsesFrostedBackground() = verify(hasText("Fixture")) {
        CollectionEditorContent(CollectionEditorState(CollectionDraft(title = "Fixture")), true, {}, {}, {}, {}, {})
    }

    private fun verify(readyMatcher: SemanticsMatcher, overlay: @Composable () -> Unit): Unit = runBlocking {
        var enabled by mutableStateOf(false)
        composeRule.setContent {
            TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize().background(Color(0xFF003399)))
                overlay()
            }
        }
        composeRule.awaitNode(readyMatcher)
        composeRule.waitForIdle()
        val before = captureScreen { sampleCenterGrid(it) }
        composeRule.runOnUiThread { enabled = true }
        val changed = composeRule.sampleScreen("frosted native surface") { after ->
            val current = sampleCenterGrid(after)
            var diff = 0
            for (i in before.indices) {
                if (before[i] != current[i]) diff++
            }
            diff.takeIf { it > 1000 }
        }
        assertTrue("Enabling frost must change the actual native surface background", changed > 1000)
    }

    private fun sampleCenterGrid(bitmap: Bitmap): IntArray {
        val ys = (bitmap.height / 4 until bitmap.height * 3 / 4 step 3).count()
        val xs = (bitmap.width / 4 until bitmap.width * 3 / 4 step 3).count()
        val samples = IntArray(ys * xs)
        var idx = 0
        for (y in bitmap.height / 4 until bitmap.height * 3 / 4 step 3) {
            for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 3) {
                samples[idx++] = bitmap.getPixel(x, y)
            }
        }
        return samples
    }
}
