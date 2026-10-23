package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bubblespark.tidilo.R
import java.util.Locale
import kotlin.math.round

@Composable
fun GenerateContentRequestScreen(
    onNavigateBack: () -> Unit,
    viewModel: GenerateContentRequestViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // Top Header: Back Button, Title, Default Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = stringResource(R.string.title_generate_content_request),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Reset Wrench icon button (without background)
            IconButton(
                onClick = { showResetConfirmDialog = true },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_reset_wrench),
                    contentDescription = stringResource(R.string.gcr_reset_default_content_desc),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Parameter Items List (1 parameter 2 lines)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. cachedContextName: String, null, Text fields
            item(key = "cachedContextName") {
                ParameterCard(
                    enabled = state.cachedContextNameEnabled,
                    onEnabledChange = { viewModel.setCachedContextNameEnabled(it) },
                    name = "cachedContextName",
                    displayValue = if (state.cachedContextName.isBlank()) "null" else state.cachedContextName
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedTextField(
                            value = state.cachedContextName,
                            onValueChange = { viewModel.setCachedContextName(it) },
                            placeholder = { Text("null") },
                            singleLine = true,
                            enabled = state.cachedContextNameEnabled,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(220.dp)
                        )
                    }
                }
            }

            // 2. candidateCount: Int, 1, Text fields (numeric digits only)
            item(key = "candidateCount") {
                ParameterCard(
                    enabled = state.candidateCountEnabled,
                    onEnabledChange = { viewModel.setCandidateCountEnabled(it) },
                    name = "candidateCount",
                    displayValue = "${state.candidateCount}"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedTextField(
                            value = "${state.candidateCount}",
                            onValueChange = { input ->
                                val digitsOnly = input.filter { it.isDigit() }
                                val intVal = digitsOnly.toIntOrNull() ?: 0
                                viewModel.setCandidateCount(intVal)
                            },
                            singleLine = true,
                            enabled = state.candidateCountEnabled,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(160.dp)
                        )
                    }
                }
            }

            // 3. enableThinking: Boolean, false, Switch(on/off)
            item(key = "enableThinking") {
                ParameterCard(
                    enabled = state.enableThinkingEnabled,
                    onEnabledChange = { viewModel.setEnableThinkingEnabled(it) },
                    name = "enableThinking",
                    displayValue = if (state.enableThinking) "true" else "false"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Switch(
                            checked = state.enableThinking,
                            onCheckedChange = { viewModel.setEnableThinking(it) },
                            enabled = state.enableThinkingEnabled
                        )
                    }
                }
            }

            // 4. maxOutputTokens: Int, 4096, Text fields (numeric digits only)
            item(key = "maxOutputTokens") {
                ParameterCard(
                    enabled = state.maxOutputTokensEnabled,
                    onEnabledChange = { viewModel.setMaxOutputTokensEnabled(it) },
                    name = "maxOutputTokens",
                    displayValue = "${state.maxOutputTokens}"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedTextField(
                            value = "${state.maxOutputTokens}",
                            onValueChange = { input ->
                                val digitsOnly = input.filter { it.isDigit() }
                                val intVal = digitsOnly.toIntOrNull() ?: 0
                                viewModel.setMaxOutputTokens(intVal)
                            },
                            singleLine = true,
                            enabled = state.maxOutputTokensEnabled,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(160.dp)
                        )
                    }
                }
            }

            // 5. seed: Int, 0, Text fields (numeric digits only)
            item(key = "seed") {
                ParameterCard(
                    enabled = state.seedEnabled,
                    onEnabledChange = { viewModel.setSeedEnabled(it) },
                    name = "seed",
                    displayValue = "${state.seed}"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedTextField(
                            value = "${state.seed}",
                            onValueChange = { input ->
                                val digitsOnly = input.filter { it.isDigit() }
                                val intVal = digitsOnly.toIntOrNull() ?: 0
                                viewModel.setSeed(intVal)
                            },
                            singleLine = true,
                            enabled = state.seedEnabled,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(160.dp)
                        )
                    }
                }
            }

            // 6. temperature: Float, 0.0f, Horizontal Slider (Standard) 0.1 step, 0.0-1.0 (center-aligned)
            item(key = "temperature") {
                ParameterCard(
                    enabled = state.temperatureEnabled,
                    onEnabledChange = { viewModel.setTemperatureEnabled(it) },
                    name = "temperature",
                    displayValue = String.format(Locale.US, "%.1f", state.temperature)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Slider(
                            value = state.temperature,
                            onValueChange = { newValue ->
                                val rounded = round(newValue * 10f) / 10f
                                viewModel.setTemperature(rounded)
                            },
                            valueRange = 0.0f..1.0f,
                            steps = 9, // 9 steps for 0.1 increments (0.1, 0.2, ..., 0.9)
                            enabled = state.temperatureEnabled,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                }
            }

            // 7. topK: Int, 3, Text fields (numeric digits only)
            item(key = "topK") {
                ParameterCard(
                    enabled = state.topKEnabled,
                    onEnabledChange = { viewModel.setTopKEnabled(it) },
                    name = "topK",
                    displayValue = "${state.topK}"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedTextField(
                            value = "${state.topK}",
                            onValueChange = { input ->
                                val digitsOnly = input.filter { it.isDigit() }
                                val intVal = digitsOnly.toIntOrNull() ?: 0
                                viewModel.setTopK(intVal)
                            },
                            singleLine = true,
                            enabled = state.topKEnabled,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.width(160.dp)
                        )
                    }
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    // Default reset confirmation dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.gcr_reset_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.gcr_reset_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetToDefault()
                        showResetConfirmDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_ok),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmDialog = false }
                ) {
                    Text(
                        text = stringResource(R.string.common_cancel),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

/**
 * Two-row parameter card:
 * Row 1: Checkbox, parameter name, parameter value (right-aligned).
 * Row 2: Value modification component (Text fields / Switch right-aligned, Slider centered).
 * Grayed out when Checkbox is unchecked.
 */
@Composable
private fun ParameterCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    name: String,
    displayValue: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.22f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Row 1: Checkbox, parameter name, parameter value (right-aligned)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 2: Value modification component (Text fields / Switch right-aligned, Slider centered)
            content()
        }
    }
}
