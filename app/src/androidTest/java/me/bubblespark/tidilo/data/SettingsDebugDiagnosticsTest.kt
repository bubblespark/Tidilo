package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.settings.InfoRow
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsDebugDiagnosticsTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun await(text: String): SemanticsNode {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
    }

    @Test fun checksOnEntryAndRefreshesTheSameStatus() {
        val initial = CompletableDeferred<AiCoreReadiness>()
        val refresh = CompletableDeferred<AiCoreReadiness>()
        var requests = 0
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    SettingsDebugScreen(onNavigateBack = {}, checkAiCoreReadiness = {
                        if (++requests == 1) initial.await() else refresh.await()
                    })
                }
            }
        }
        await(context.getString(R.string.settings_ai_analysis_checking))
        initial.complete(AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE))
        await(context.getString(R.string.debug_prompt_api_download_required))
        composeRule.onNode(hasText(context.getString(R.string.debug_prompt_api_check_status)) and hasClickAction())
            .performClick()
        await(context.getString(R.string.settings_ai_analysis_checking))
        refresh.complete(AiCoreReadiness.Failed)
        await(context.getString(R.string.settings_ai_analysis_check_failed))
        assertEquals(2, requests)
    }

    @Test fun longValueWrapsBelowItsLabelAtLargeFontSize() {
        val label = "Device model"
        val value = "Manufacturer ".repeat(4) + "LongDeviceModelIdentifier".repeat(3)
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(220.dp)) { InfoRow(label, value) }
                }
            }
        }
        val labelNode = await(label)
        val valueNode = await(value)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.runOnUiThread {
            assertTrue(valueNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        }
        val layout = layouts.single()
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        assertTrue(valueNode.boundsInRoot.top > labelNode.boundsInRoot.bottom)
    }

    @Test fun localizedDiagnosticsShowSdkFacts() {
        var locale by mutableStateOf(Locale.ENGLISH)
        val readiness = AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE)
        composeRule.setContent {
            val base = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(locale, baseConfiguration) { Configuration(baseConfiguration).apply { setLocale(locale) } }
            val localized = remember(configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) { SettingsDebugScreen({}, checkAiCoreReadiness = { readiness }) }
                }
            }
        }
        for (current in listOf(Locale.ENGLISH, Locale.JAPANESE)) {
            locale = current
            composeRule.waitForIdle()
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(current) })
            val info = localized.applicationInfo
            await(localized.getString(R.string.settings_ai_analysis_unavailable))
            listOf(
                localized.getString(R.string.debug_android_os_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                localized.getString(R.string.debug_api_level, info.minSdkVersion),
                localized.getString(R.string.debug_api_level, info.targetSdkVersion),
                localized.getString(R.string.debug_api_level, info.compileSdkVersion)
            ).forEach(::await)
        }
    }
}
