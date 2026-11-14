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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.AiCoreAvailability
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

/** Availability restricts selection without changing the user's persisted mode. */
@Composable
fun AiAnalysisScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGenerateContentRequest: () -> Unit,
    modifier: Modifier = Modifier,
    checkAiCoreAvailable: suspend () -> Boolean = { AiCoreAvailability.check() }
) {
    val mode by AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()

    var available by remember { mutableStateOf<Boolean?>(null) }
    val currentCheck by rememberUpdatedState(checkAiCoreAvailable)
    LaunchedEffect(Unit) { available = currentCheck() }

    AiAnalysisContent(
        mode = mode,

        available = available,
        onNavigateBack = onNavigateBack,
        onModeChange = AppConfig::setAiAnalysisMode,

        onNavigateToParameters = onNavigateToGenerateContentRequest,
        modifier = modifier
    )
}

@Composable
internal fun AiAnalysisContent(
    mode: AiAnalysisMode,

    available: Boolean?,
    onNavigateBack: () -> Unit,
    onModeChange: (AiAnalysisMode) -> Unit,

    onNavigateToParameters: () -> Unit,
    modifier: Modifier = Modifier
) {
    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), modifier = modifier, header = {
        TidiloPageHeader(title = stringResource(R.string.settings_ai_analysis_title), onNavigateBack = onNavigateBack)
    }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .padding(bottom = 32.dp)) {
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
                    SettingsSectionTitle(stringResource(R.string.settings_section_aicore_parameters))
                    SettingsGroup {
                        SettingsNavigationRow(stringResource(R.string.settings_generate_content_title),
                            stringResource(R.string.settings_generate_content_subtitle), Icons.Default.Tune, onNavigateToParameters)
                    }
                }
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
