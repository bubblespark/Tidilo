package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.AiCoreAvailability
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

/** Availability restricts selection without changing the user's persisted mode. */
@Composable
fun AiAnalysisScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGenerateContentRequest: () -> Unit,
    onNavigateToNsfwContents: () -> Unit,
    modifier: Modifier = Modifier,
    checkAiCoreAvailable: suspend () -> Boolean = { AiCoreAvailability.check() }
) {
    val mode by AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    val fallback by AppConfig.aiCoreFallbackMode.collectAsStateWithLifecycle()
    val autoRun by AppConfig.isAutoRunAiOnActive.collectAsStateWithLifecycle()
    var available by remember { mutableStateOf<Boolean?>(null) }
    val currentCheck by rememberUpdatedState(checkAiCoreAvailable)
    LaunchedEffect(Unit) { available = currentCheck() }

    AiAnalysisContent(
        mode = mode,
        fallback = fallback,
        autoRun = autoRun,
        available = available,
        onNavigateBack = onNavigateBack,
        onModeChange = AppConfig::setAiAnalysisMode,
        onFallbackChange = AppConfig::setAiCoreFallbackMode,
        onAutoRunChange = AppConfig::setAutoRunAiOnActive,
        onNavigateToParameters = onNavigateToGenerateContentRequest,
        onNavigateToSafety = onNavigateToNsfwContents,
        modifier = modifier
    )
}

@Composable
internal fun AiAnalysisContent(
    mode: AiAnalysisMode,
    fallback: AiCoreFallbackMode,
    autoRun: Boolean,
    available: Boolean?,
    onNavigateBack: () -> Unit,
    onModeChange: (AiAnalysisMode) -> Unit,
    onFallbackChange: (AiCoreFallbackMode) -> Unit,
    onAutoRunChange: (Boolean) -> Unit,
    onNavigateToParameters: () -> Unit,
    onNavigateToSafety: () -> Unit,
    modifier: Modifier = Modifier
) {
    TidiloScreenScaffold(modifier = modifier, header = {
        TidiloPageHeader(title = stringResource(R.string.settings_ai_analysis_title), onNavigateBack = onNavigateBack)
    }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsSectionTitle(stringResource(R.string.settings_ai_analysis_using_model_title))
            AiModelChoices(mode, available, onModeChange)
            AnimatedVisibility(visible = mode == AiAnalysisMode.AICORE,
                enter = slideInVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { -it / 3 } +
                    fadeIn(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) +
                    expandVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)),
                exit = slideOutVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { -it / 3 } +
                    fadeOut(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) +
                    shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow))) {
                Column {
                    Spacer(Modifier.height(20.dp))
                    AiAutomation(autoRun, onAutoRunChange)
                    Spacer(Modifier.height(20.dp))
                    AiFallbackChoices(fallback, onFallbackChange)
                    Spacer(Modifier.height(20.dp))
                    SettingsSectionTitle(stringResource(R.string.settings_section_aicore_parameters))
                    SettingsGroup {
                        SettingsNavigationRow(stringResource(R.string.settings_generate_content_title),
                            stringResource(R.string.settings_generate_content_subtitle), Icons.Default.Tune, onNavigateToParameters)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_content_safety))
            SettingsGroup {
                SettingsNavigationRow(stringResource(R.string.settings_nsfw_title),
                    stringResource(R.string.settings_nsfw_subtitle), Icons.Default.Visibility, onNavigateToSafety)
            }
        }
    }
}

@Composable
private fun AiModelChoices(mode: AiAnalysisMode, available: Boolean?, onModeChange: (AiAnalysisMode) -> Unit) {
    SettingsGroup(Modifier.selectableGroup()) {
        SettingsRadioRow(stringResource(R.string.settings_ai_analysis_aicore), mode == AiAnalysisMode.AICORE,
            onClick = { onModeChange(AiAnalysisMode.AICORE) }, enabled = available == true,
            subtitle = stringResource(when (available) {
                null -> R.string.settings_ai_analysis_checking
                false -> R.string.settings_ai_analysis_unavailable
                true -> R.string.settings_ai_analysis_aicore_desc
            }), isError = available == false)
        SettingsDivider()
        SettingsRadioRow(stringResource(R.string.settings_ai_analysis_disable), mode == AiAnalysisMode.DISABLE,
            onClick = { onModeChange(AiAnalysisMode.DISABLE) }, subtitle = stringResource(R.string.settings_ai_analysis_disable_desc))
    }
}

@Composable
private fun AiAutomation(autoRun: Boolean, onAutoRunChange: (Boolean) -> Unit) {
    SettingsSectionTitle(stringResource(R.string.settings_section_automation))
    SettingsGroup {
        Row(Modifier.fillMaxWidth().toggleable(value = autoRun, role = Role.Switch, onValueChange = onAutoRunChange)
            .padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(Icons.Default.PlayArrow)
            Spacer(Modifier.width(16.dp))
            SettingsRowText(stringResource(R.string.settings_auto_run_ai_title),
                stringResource(R.string.settings_auto_run_ai_subtitle), Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Switch(checked = autoRun, onCheckedChange = null)
        }
    }
}

@Composable
private fun AiFallbackChoices(fallback: AiCoreFallbackMode, onFallbackChange: (AiCoreFallbackMode) -> Unit) {
    SettingsSectionTitle(stringResource(R.string.settings_aicore_fallback_section_title),
        description = stringResource(R.string.settings_aicore_fallback_section_desc))
    SettingsGroup(Modifier.selectableGroup()) {
        AiCoreFallbackMode.entries.forEachIndexed { index, choice ->
            if (index > 0) SettingsDivider()
            SettingsRadioRow(stringResource(choice.labelRes), fallback == choice, onClick = { onFallbackChange(choice) })
        }
    }
}
