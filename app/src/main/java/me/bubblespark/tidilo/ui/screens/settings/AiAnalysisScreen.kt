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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.AiCoreAvailability
import me.bubblespark.tidilo.data.AiCoreDownloadResult
import me.bubblespark.tidilo.data.AiCoreReadiness
import me.bubblespark.tidilo.data.downloadAiCoreModel
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.aiCoreStatusText
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

internal data class AiModelSetupState(
    val readiness: AiCoreReadiness? = null,
    val downloading: Boolean = false,
    val downloadFailed: Boolean = false
)

internal class AiModelSetupViewModel : ViewModel() {
    private val _state = MutableStateFlow(AiModelSetupState())
    val state = _state.asStateFlow()
    private val _progress = MutableStateFlow<Float?>(null)
    val progress = _progress.asStateFlow()
    private var operation: Job? = null

    fun initialize(inspect: suspend () -> AiCoreReadiness) {
        if (operation == null) refresh(inspect)
    }

    fun refresh(inspect: suspend () -> AiCoreReadiness) {
        if (operation?.isCompleted == false) return
        _state.value = AiModelSetupState()
        operation = viewModelScope.launch {
            val readiness = inspectSafely(inspect)
            ensureActive()
            _state.value = AiModelSetupState(readiness)
        }
    }

    fun download(
        inspect: suspend () -> AiCoreReadiness,
        requestDownload: suspend ((Float?) -> Unit) -> AiCoreDownloadResult
    ) {
        if (operation?.isCompleted == false ||
            _state.value.readiness != AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)) return
        _progress.value = null
        _state.value = AiModelSetupState(downloading = true)
        operation = viewModelScope.launch {
            val result = requestDownload { fraction -> _progress.value = fraction }
            ensureActive()
            val readiness = inspectSafely(inspect)
            ensureActive()
            // The latest SDK status takes precedence over a failed local download attempt.
            _state.value = AiModelSetupState(
                readiness = readiness,
                downloadFailed = result == AiCoreDownloadResult.FAILED &&
                    !readiness.isAvailable && readiness != AiCoreReadiness.Status(FeatureStatus.DOWNLOADING)
            )
            _progress.value = null
        }
    }

    private suspend fun inspectSafely(inspect: suspend () -> AiCoreReadiness): AiCoreReadiness = try {
        inspect()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AiCoreReadiness.Failed
    }
}

/** Availability restricts selection without changing the user's persisted mode. */
@Composable
internal fun AiAnalysisScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGenerateContentRequest: () -> Unit,
    modifier: Modifier = Modifier,
    checkAiCoreReadiness: suspend () -> AiCoreReadiness = { AiCoreAvailability.inspect() },
    requestDownload: suspend ((Float?) -> Unit) -> AiCoreDownloadResult = { downloadAiCoreModel(it) }
) {
    val mode by AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    val setupModel = viewModel<AiModelSetupViewModel>()
    val modelState by setupModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(setupModel) { setupModel.initialize(checkAiCoreReadiness) }

    AiAnalysisContent(
        mode = mode,

        readiness = modelState.readiness,
        onNavigateBack = onNavigateBack,
        onModeChange = { selected ->
            if (selected == AiAnalysisMode.DISABLE || setupModel.state.value.readiness?.isAvailable == true) {
                AppConfig.setAiAnalysisMode(selected)
            }
        },

        onNavigateToParameters = onNavigateToGenerateContentRequest,
        modifier = modifier,
        downloading = modelState.downloading,
        downloadProgress = setupModel.progress,
        downloadFailed = modelState.downloadFailed,
        onRefresh = { setupModel.refresh(checkAiCoreReadiness) },
        onDownload = { setupModel.download(checkAiCoreReadiness, requestDownload) }
    )
}

@Composable
internal fun AiAnalysisContent(
    mode: AiAnalysisMode,

    readiness: AiCoreReadiness?,
    onNavigateBack: () -> Unit,
    onModeChange: (AiAnalysisMode) -> Unit,

    onNavigateToParameters: () -> Unit,
    modifier: Modifier = Modifier,
    downloading: Boolean = false,
    downloadProgress: StateFlow<Float?>? = null,
    downloadFailed: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null
) {
    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), modifier = modifier, header = {
        TidiloPageHeader(title = stringResource(R.string.settings_ai_analysis_title), onNavigateBack = onNavigateBack)
    }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .padding(bottom = 32.dp)) {
            SettingsSectionTitle(stringResource(R.string.settings_ai_analysis_using_model_title))
            AiModelChoices(mode, readiness, onModeChange)
            Spacer(Modifier.height(20.dp))
            AiModelSetup(readiness, downloading, downloadProgress, downloadFailed, onRefresh, onDownload)
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
private fun AiModelChoices(mode: AiAnalysisMode, readiness: AiCoreReadiness?, onModeChange: (AiAnalysisMode) -> Unit) {
    SettingsGroup(Modifier.selectableGroup()) {
        SettingsRadioRow(stringResource(R.string.settings_ai_analysis_aicore), mode == AiAnalysisMode.AICORE,
            onClick = { onModeChange(AiAnalysisMode.AICORE) }, enabled = readiness?.isAvailable == true,
            subtitle = stringResource(R.string.settings_ai_analysis_aicore_desc))
        SettingsDivider()
        SettingsRadioRow(stringResource(R.string.settings_ai_analysis_disable), mode == AiAnalysisMode.DISABLE,
            onClick = { onModeChange(AiAnalysisMode.DISABLE) }, subtitle = stringResource(R.string.settings_ai_analysis_disable_desc))
    }
}

@Composable
private fun AiModelSetup(
    readiness: AiCoreReadiness?,
    downloading: Boolean,
    progress: StateFlow<Float?>?,
    downloadFailed: Boolean,
    onRefresh: (() -> Unit)?,
    onDownload: (() -> Unit)?
) {
    SettingsSectionTitle(stringResource(R.string.ai_model_status_title))
    val status = when {
        downloading -> R.string.ai_model_downloading
        downloadFailed -> R.string.ai_model_download_failed
        else -> aiCoreStatusText(readiness)
    }
    SettingsGroup {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium
            )
            if (downloading) {
                DownloadProgress(progress)
            } else if (readiness == AiCoreReadiness.Status(FeatureStatus.DOWNLOADING)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!downloading && readiness == AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE) && onDownload != null) {
                Button(onClick = onDownload) {
                    Text(stringResource(if (downloadFailed) R.string.common_retry else R.string.ai_model_download))
                }
            }
            if (!downloading && readiness != null && onRefresh != null) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.ai_model_check_again)) }
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: StateFlow<Float?>?) {
    val observed = progress?.collectAsStateWithLifecycle()
    val hasProgress by remember(observed) {
        derivedStateOf(structuralEqualityPolicy()) { observed?.value != null }
    }
    if (hasProgress) LinearProgressIndicator(progress = { observed?.value ?: 0f }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}
