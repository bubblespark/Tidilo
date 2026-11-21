package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.AiCoreReadiness
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloIconButton
import me.bubblespark.tidilo.ui.components.aiCoreStatusText
import me.bubblespark.tidilo.ui.theme.SurfaceRole

internal sealed interface DetailAiState {
    data object Idle : DetailAiState
    data object Disabled : DetailAiState
    data object Checking : DetailAiState
    data object Ready : DetailAiState
    data class Blocked(val readiness: AiCoreReadiness) : DetailAiState
}

internal class DetailAiActionViewModel : ViewModel() {
    private val _state = MutableStateFlow<DetailAiState>(DetailAiState.Idle)
    val state = _state.asStateFlow()
    private var checkJob: Job? = null

    fun check(readMode: () -> AiAnalysisMode, inspect: suspend () -> AiCoreReadiness) {
        val current = _state.value
        if (current != DetailAiState.Idle && current != DetailAiState.Blocked(AiCoreReadiness.Failed)) return
        if (readMode() == AiAnalysisMode.DISABLE) {
            _state.value = DetailAiState.Disabled
            return
        }

        _state.value = DetailAiState.Checking
        checkJob = viewModelScope.launch {
            val readiness = try {
                inspect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AiCoreReadiness.Failed
            }
            ensureActive()
            _state.value = when {
                readMode() == AiAnalysisMode.DISABLE -> DetailAiState.Disabled
                readiness.isAvailable -> DetailAiState.Ready
                else -> DetailAiState.Blocked(readiness)
            }
        }
    }

    fun dismiss() {
        checkJob?.cancel()
        _state.value = DetailAiState.Idle
    }

    fun consumeReady(mode: AiAnalysisMode): Boolean {
        if (_state.value != DetailAiState.Ready) return false
        if (mode == AiAnalysisMode.DISABLE) {
            _state.value = DetailAiState.Disabled
            return false
        }
        _state.value = DetailAiState.Idle
        return true
    }
}

@Composable
internal fun DetailAiActionButton(
    disabledInSettings: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val offState = stringResource(R.string.ai_analysis_off_state)
    TidiloIconButton(
        role = SurfaceRole.TOOLBAR_ACTION,
        onClick = onClick,
        modifier = modifier.size(HeaderDefaults.DetailActionSize)
    ) {
        Box {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.detail_action_ai_summary),
                modifier = Modifier.size(22.dp).semantics {
                    if (disabledInSettings) stateDescription = offState
                }
            )
            if (disabledInSettings) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(3.dp, 2.dp).size(13.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                )
            }
        }
    }
}

@Composable
internal fun DetailAiStatusDialog(
    state: DetailAiState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    if (state == DetailAiState.Idle) return
    val readiness = (state as? DetailAiState.Blocked)?.readiness
    val modelCode = (readiness as? AiCoreReadiness.Status)?.code
    val checking = state == DetailAiState.Checking || state == DetailAiState.Ready
    val canOpenSettings = state == DetailAiState.Disabled ||
        modelCode == FeatureStatus.DOWNLOADABLE || modelCode == FeatureStatus.DOWNLOADING
    val canRetry = readiness == AiCoreReadiness.Failed

    TidiloAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_action_ai_summary)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(
                    text = stringResource(
                        if (state == DetailAiState.Disabled) R.string.ai_analysis_off_message
                        else aiCoreStatusText(readiness)
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                if (!checking) Text(stringResource(R.string.detail_ai_manual_fallback))
            }
        },
        confirmButton = {
            when {
                canOpenSettings -> TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.ai_analysis_open_settings))
                }
                canRetry -> TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                else -> TextButton(onClick = onDismiss) {
                    Text(stringResource(if (checking) R.string.common_cancel else R.string.common_close))
                }
            }
        },
        dismissButton = {
            if (canOpenSettings || canRetry) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    )
}
