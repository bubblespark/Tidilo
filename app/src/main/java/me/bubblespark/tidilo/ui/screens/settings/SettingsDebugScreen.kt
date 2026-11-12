package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.debug.CrashLogEntry
import me.bubblespark.tidilo.data.debug.CrashLogManager
import me.bubblespark.tidilo.ui.screens.debug.DebugPromptTester
import me.bubblespark.tidilo.ui.screens.debug.GenAiTestState
import me.bubblespark.tidilo.ui.screens.debug.WebParserTestSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsDebugScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var genAiState by remember { mutableStateOf<GenAiTestState>(GenAiTestState.Idle) }
    var crashLogs by remember { mutableStateOf<List<CrashLogEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { CrashLogManager.getCrashLogs(context) }
    }
    var selectedLog by remember { mutableStateOf<CrashLogEntry?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), avoidIme = true,
        modifier = modifier,
        hideHeaderOnScroll = false,
        // Top Header
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.settings_debug_title),
                onNavigateBack = onNavigateBack,
                subtitle = {
                    Text(
                        text = stringResource(R.string.settings_debug_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()

                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
        ) {
            // Device & SDK Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = themeCorner(ThemeCorner.PROMINENT_CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.debug_device_sdk_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    InfoRow(label = "Device Model", value = "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow(label = "Android OS", value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow(label = "Minimum SDK", value = "API 34 (Android 14)")
                    InfoRow(label = "Target / Compile SDK", value = "API 36")
                    InfoRow(label = "Device Agnostic", value = "Generic Android 14+ AICore support")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini Nano / AICore Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = themeCorner(ThemeCorner.PROMINENT_CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.debug_aicore_status_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    InfoRow(label = "AICore Availability", value = "Supported (API >= 34)")
                    InfoRow(label = "On-Device Model", value = "Gemini Nano (System Service)")
                    InfoRow(label = "Inference Constraint", value = "Foreground Execution Only")
                    InfoRow(label = "Engine Pipeline", value = "URL fetch -> Text Extract -> Nano Prompt API")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ML Kit GenAI Prompt API Diagnostic & Test Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = themeCorner(ThemeCorner.PROMINENT_CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.debug_mlkit_genai_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verify on-device Gemini Nano connectivity via Android AICore Prompt API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Check Status Button
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    genAiState = GenAiTestState.Loading("Checking Gemini Nano status...")
                                    val (statusText, code) = DebugPromptTester.checkStatus()
                                    genAiState = GenAiTestState.StatusResult(statusText, code)
                                }
                            },
                            enabled = genAiState !is GenAiTestState.Loading,
                            shape = themeCorner(ThemeCorner.FIELD),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check Status")
                        }

                        // 2. Run Test Inference Button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    genAiState = GenAiTestState.Loading("Running on-device inference via AICore...")
                                    try {
                                        val currentConfig = DebugPromptTester.loadCurrentConfig(context)
                                        val (output, latency) = DebugPromptTester.testGenerateContent(
                                            prompt = "Reply with 'Hello from Gemini Nano!' in English.",
                                            config = currentConfig
                                        )
                                        genAiState = GenAiTestState.InferenceResult(output, latency)
                                    } catch (e: Exception) {
                                        genAiState = GenAiTestState.Error(
                                            errorMessage = e.message ?: "Unknown inference error",
                                            cause = e
                                        )
                                    }
                                }
                            },
                            enabled = genAiState !is GenAiTestState.Loading,
                            shape = themeCorner(ThemeCorner.FIELD),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Test")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Result / Status Area
                    when (val state = genAiState) {
                        is GenAiTestState.Idle -> {
                            Text(
                                text = "Tap 'Check Status' or 'Run Test' to initiate diagnostics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        is GenAiTestState.Loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is GenAiTestState.StatusResult -> {
                            Surface(
                                shape = themeCorner(ThemeCorner.FIELD),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Status: ${state.statusText}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "FeatureStatus code: ${state.statusCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        is GenAiTestState.InferenceResult -> {
                            Surface(
                                shape = themeCorner(ThemeCorner.FIELD),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "AICore Response (${state.latencyMs} ms):",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.responseText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        is GenAiTestState.Error -> {
                            Surface(
                                shape = themeCorner(ThemeCorner.FIELD),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Error:",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = state.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL Web Parser & Inspector Section
            WebParserTestSection()

            Spacer(modifier = Modifier.height(16.dp))

            // Crash Logs Section
            CrashLogsSection(
                crashLogs = crashLogs,
                onLogClick = { selectedLog = it },
                onClearClick = { showClearConfirmDialog = true }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showClearConfirmDialog) {
        TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.debug_crash_logs_clear_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.debug_crash_logs_clear_confirm_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            CrashLogManager.clearLogs(context)
                            val updated = CrashLogManager.getCrashLogs(context)
                            withContext(Dispatchers.Main) {
                                crashLogs = updated
                                showClearConfirmDialog = false
                            }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.debug_crash_logs_clear),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmDialog = false }
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    selectedLog?.let { entry ->
        CrashLogDetailsDialog(
            entry = entry,
            onDismiss = { selectedLog = null },
            onCopy = {
                val formatted = CrashLogUiFormatter.formatForClipboard(entry)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Crash Log", formatted)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(
                    context,
                    context.getString(R.string.debug_crash_logs_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
internal fun CrashLogDetailsDialog(
    entry: CrashLogEntry,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = entry.exceptionClass.substringAfterLast('.'),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            CrashLogDetailsContent(entry)
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.debug_crash_logs_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.debug_crash_logs_close))
            }
        }
    )
}

@Composable
internal fun CrashLogDetailsContent(
    entry: CrashLogEntry,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = entry.exceptionClass,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "App: ${entry.appVersion} | ${entry.deviceModel} (${entry.osVersion})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = CrashLogUiFormatter.formatTimestamp(entry.timestamp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.debug_crash_logs_thread, entry.threadName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        if (!entry.message.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            shape = themeCorner(ThemeCorner.SMALL),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = entry.stackTrace,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
internal fun CrashLogsSection(
    crashLogs: List<CrashLogEntry>,
    onLogClick: (CrashLogEntry) -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = themeCorner(ThemeCorner.PROMINENT_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.debug_crash_logs_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (crashLogs.isNotEmpty()) {
                    TextButton(onClick = onClearClick) {
                        Text(
                            text = stringResource(R.string.debug_crash_logs_clear),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (crashLogs.isEmpty()) {
                Surface(
                    shape = themeCorner(ThemeCorner.FIELD),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.debug_crash_logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    crashLogs.forEach { entry ->
                        CrashLogItemCard(
                            entry = entry,
                            onClick = { onLogClick(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashLogItemCard(
    entry: CrashLogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = themeCorner(ThemeCorner.CHIP),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = CrashLogUiFormatter.formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.debug_crash_logs_thread, entry.threadName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = entry.exceptionClass.substringAfterLast('.'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!entry.message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

object CrashLogUiFormatter {
    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun formatTimestamp(
        timestamp: Long,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return TIMESTAMP_FORMATTER.withLocale(locale)
            .format(Instant.ofEpochMilli(timestamp).atZone(zoneId))
    }

    fun formatForClipboard(
        entry: CrashLogEntry,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val dateStr = formatTimestamp(entry.timestamp, locale, zoneId)
        return buildString {
            appendLine("Timestamp: $dateStr")
            appendLine("App Version: ${entry.appVersion}")
            appendLine("Device: ${entry.deviceModel}")
            appendLine("OS: ${entry.osVersion}")
            appendLine("Thread: ${entry.threadName}")
            appendLine("Exception: ${entry.exceptionClass}")
            appendLine("Message: ${entry.message ?: "None"}")
            appendLine("Stack Trace:")
            append(entry.stackTrace)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
