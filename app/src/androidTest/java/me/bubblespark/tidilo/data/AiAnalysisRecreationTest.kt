package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.google.mlkit.genai.common.FeatureStatus
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The AI Analysis model setup state across one Activity recreation. */
class AiAnalysisRecreationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun recreationRetainsDownloadFailureUntilExplicitRefresh() {
        var checks = 0
        val inspect: suspend () -> AiCoreReadiness = {
            checks++
            AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)
        }
        val download: suspend ((Float?) -> Unit) -> AiCoreDownloadResult = { AiCoreDownloadResult.FAILED }
        val previous = AppConfig.aiAnalysisMode.value
        fun render() = composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    AiAnalysisScreen({}, {}, checkAiCoreReadiness = inspect, requestDownload = download)
                }
            }
        }
        try {
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(AiAnalysisMode.AICORE) }
            render()
            click(R.string.ai_model_download)
            val failed = composeRule.activity.getString(R.string.ai_model_download_failed)
            await(failed)

            composeRule.activityRule.scenario.recreate()
            render()
            await(failed)
            assertEquals(2, checks)
            click(R.string.ai_model_check_again)
            await(composeRule.activity.getString(R.string.ai_model_download))
            assertEquals(3, checks)
        } finally {
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(previous) }
        }
    }

    private fun await(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(label: Int) {
        val text = composeRule.activity.getString(label)
        await(text)
        composeRule.onNode(hasText(text) and hasClickAction()).performClick()
    }
}
