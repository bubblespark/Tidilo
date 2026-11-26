package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.settings.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsHeaderTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sharedSettingsHeaderKeepsDebugSubtitleAndResetDialog() {
        var debug by mutableStateOf(true)
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                if (debug) SettingsDebugScreen(onNavigateBack = {}, checkAiCoreReadiness = { AiCoreReadiness.Failed })
                else GenerateContentRequestScreen(onNavigateBack = {})
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.settings_debug_subtitle), useUnmergedTree = true).assertExists()
        debug = false
        composeRule.onNode(hasContentDescription(context.getString(R.string.gcr_reset_default_content_desc)) and hasClickAction())
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.gcr_reset_dialog_title)).assertExists()
    }

    @Test fun settingsHeadersStayFixed() = verifyHeaders(listOf(
        { back -> SettingsScreen(onNavigateBack = back, onNavigateToAiAnalysis = {}) },
        { back -> AiAnalysisScreen(onNavigateBack = back, onNavigateToGenerateContentRequest = {}, checkAiCoreReadiness = { AiCoreReadiness.Failed }) },
        { back -> PrivateSettingsScreen(onNavigateBack = back) },
        { back -> SettingsDebugScreen(onNavigateBack = back, checkAiCoreReadiness = { AiCoreReadiness.Failed }) }
    ))

    @Test fun secondaryHeadersStayFixed() = verifyHeaders(listOf(
        { back -> GenerateContentRequestScreen(onNavigateBack = back) },
        { back -> StorageScreen(onNavigateBack = back) },
        { back -> RssSettingsScreen(onNavigateBack = back) }
    ))

    private fun verifyHeaders(screens: List<@Composable (() -> Unit) -> Unit>) {
        var index by mutableIntStateOf(0)
        var returned = false
        val back = composeRule.onNode(hasContentDescription(context.getString(R.string.common_back)) and hasClickAction())
        val caches = OfflineVisualCaches()
        try {
            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    CompositionLocalProvider(LocalBarsVisible provides false) {
                        Box(Modifier.size(360.dp, 350.dp)) { screens[index] { returned = true } }
                    }
                }
            }
            screens.indices.forEach { current ->
                index = current
                returned = false
                composeRule.waitForIdle()
                val before = back.fetchSemanticsNode().boundsInRoot.top
                composeRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
                composeRule.waitForIdle()
                assertEquals("Screen $current header remains fixed when body scrolls",
                    before, back.fetchSemanticsNode().boundsInRoot.top, 1f)
                back.performClick()
                assertTrue("Screen $current keeps its Back callback", returned)
            }
        } finally {
            caches.close()
        }
    }
}
