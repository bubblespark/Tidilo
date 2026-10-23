package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.OgImageManager
import android.widget.Toast
import androidx.compose.material.icons.filled.DeleteSweep
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToGenerateContentRequest: () -> Unit,
    onNavigateToNsfwContents: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToSummary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val isAutoRunAiOnActive by AppConfig.isAutoRunAiOnActive.collectAsStateWithLifecycle()
    val aiCoreFallbackMode by AppConfig.aiCoreFallbackMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localConfig = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    var showFallbackDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
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
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // General Section Header
        Text(
            text = stringResource(R.string.settings_section_general),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        // Language Setting Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLanguageDialog = true },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.Language,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_language_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentLanguage.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // AI Section Header
        Text(
            text = stringResource(R.string.settings_section_ai),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        // Auto-run AI Tagging on Active Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.PlayArrow,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_auto_run_ai_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_auto_run_ai_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Switch(
                    checked = isAutoRunAiOnActive,
                    onCheckedChange = { AppConfig.setAutoRunAiOnActive(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // AICore Error Fallback Mode Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showFallbackDialog = true },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.Refresh,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_aicore_fallback_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(aiCoreFallbackMode.labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // GenerateContentRequest Item Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToGenerateContentRequest() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.AutoAwesome,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_generate_content_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_generate_content_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // NSFW Contents Item Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToNsfwContents() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.Visibility,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_nsfw_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_nsfw_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Data Management Section Header
        Text(
            text = stringResource(R.string.settings_section_data),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        Card(
            onClick = onNavigateToSummary,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    BoxContent(
                        icon = ImageVector.vectorResource(R.drawable.ic_summarize),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.summary_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // Clear Image Cache Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showClearCacheConfirmDialog = true },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.DeleteSweep,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_clear_image_cache_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_clear_image_cache_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Debug Section Header
        Text(
            text = stringResource(R.string.settings_section_debug),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        // Debug Screen Navigation Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToDebug() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        BoxContent(
                            icon = Icons.Default.BugReport,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_debug_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_debug_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showClearCacheConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_clear_image_cache_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_clear_image_cache_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirmDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                OgImageManager.clearImageCache(context)
                                DomainIconManager.clearCache(context)
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_clear_image_cache_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_clear),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showFallbackDialog) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides localConfig
        ) {
            AlertDialog(
                onDismissRequest = { showFallbackDialog = false },
                title = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Text(
                            text = stringResource(R.string.settings_aicore_fallback_dialog_title),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Column {
                            AiCoreFallbackMode.entries.forEach { mode ->
                                val isSelected = mode == aiCoreFallbackMode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            AppConfig.setAiCoreFallbackMode(mode)
                                            showFallbackDialog = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            AppConfig.setAiCoreFallbackMode(mode)
                                            showFallbackDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = stringResource(mode.labelRes),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(mode.descRes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        TextButton(onClick = { showFallbackDialog = false }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
    }

    if (showLanguageDialog) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides localConfig
        ) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Text(
                            text = stringResource(R.string.settings_language_dialog_title),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Column {
                            AppLanguage.entries.forEach { lang ->
                                val isSelected = lang == currentLanguage
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            AppConfig.setLanguage(lang)
                                            showLanguageDialog = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            AppConfig.setLanguage(lang)
                                            showLanguageDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = lang.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        TextButton(onClick = { showLanguageDialog = false }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
private fun BoxContent(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color
) {
    androidx.compose.foundation.layout.Box(
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
