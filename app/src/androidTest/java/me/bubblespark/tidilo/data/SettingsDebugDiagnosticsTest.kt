package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisContent
import me.bubblespark.tidilo.ui.screens.settings.InfoRow
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDebugDiagnosticsTest {
    private val host = HeaderTestHost()

    @Test fun checksOnEntryAndRefreshesTheSameStatus(): Unit = runBlocking {
        val activity = host.launch()
        val initial = CompletableDeferred<AiCoreReadiness>()
        val refresh = CompletableDeferred<AiCoreReadiness>()
        var requests = 0
        suspend fun awaitText(id: Int) = host.await(activity) { host.text(it, host.context.getString(id)) }
        try {
            host.main { activity.setContent { TidiloTheme {
                Surface(Modifier.fillMaxSize()) {
                    SettingsDebugScreen(onNavigateBack = {}, checkAiCoreReadiness = {
                        if (++requests == 1) initial.await() else refresh.await()
                    })
                }
            } } }
            awaitText(R.string.settings_ai_analysis_checking)
            initial.complete(AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE))
            awaitText(R.string.debug_prompt_api_download_required)
            val label = awaitText(R.string.debug_prompt_api_check_status)
            host.main {
                val button = generateSequence(label) { it.parent }
                    .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            awaitText(R.string.settings_ai_analysis_checking)
            refresh.complete(AiCoreReadiness.Failed)
            awaitText(R.string.settings_ai_analysis_check_failed)
            assertEquals(2, requests)
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test fun longValueWrapsBelowItsLabelAtLargeFontSize(): Unit = runBlocking {
        val activity = host.launch()
        val label = "Device model"
        val value = "Manufacturer ".repeat(4) + "LongDeviceModelIdentifier".repeat(3)
        try {
            host.main { activity.setContent { TidiloTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(220.dp)) { InfoRow(label, value) }
                }
            } } }
            val labelNode = host.await(activity) { host.text(it, label) }
            val valueNode = host.await(activity) { host.text(it, value) }
            host.main {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(valueNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
                val layout = layouts.single()
                assertTrue(layout.lineCount > 1)
                assertFalse(layout.hasVisualOverflow)
                assertTrue(valueNode.boundsInRoot.top > labelNode.boundsInRoot.bottom)
            }
        } finally {
            host.main { activity.finish() }
        }
    }

    @Test fun localizedDiagnosticsInLightAndDarkThemes(): Unit = runBlocking {
        val activity = host.launch()
        try {
            for (locale in listOf(Locale.ENGLISH, Locale.JAPANESE)) for (dark in listOf(false, true)) {
                val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
                val localized = activity.createConfigurationContext(configuration)
                val suffix = "${locale.language}-${if (dark) "dark" else "light"}"
                val readiness = AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE)
                for (debug in listOf(true, false)) {
                    host.main { activity.setContent { key(suffix, debug) {
                        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
                            TidiloTheme(darkTheme = dark, dynamicColor = false) {
                                Surface(Modifier.fillMaxSize()) {
                                    if (debug) SettingsDebugScreen({}, checkAiCoreReadiness = { readiness })
                                    else AiAnalysisContent(AiAnalysisMode.AICORE, readiness, {}, {}, {})
                                }
                            }
                        }
                    } } }
                    host.await(activity) {
                        host.text(it, localized.getString(R.string.settings_ai_analysis_unavailable))
                    }
                    if (debug) {
                        val info = localized.applicationInfo
                        val facts = listOf(
                            localized.getString(R.string.debug_android_os_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                            localized.getString(R.string.debug_api_level, info.minSdkVersion),
                            localized.getString(R.string.debug_api_level, info.targetSdkVersion),
                            localized.getString(R.string.debug_api_level, info.compileSdkVersion)
                        )
                        for (fact in facts) host.await(activity) { host.text(it, fact) }
                        host.capture("271-debug-sdk-$suffix")
                        host.scroll(activity, 420f * localized.resources.displayMetrics.density)
                        host.capture("271-debug-status-$suffix")
                    } else {
                        host.capture("271-ai-analysis-$suffix")
                    }
                }
            }
        } finally {
            host.main { activity.finish() }
        }
    }
}
