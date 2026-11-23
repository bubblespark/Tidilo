package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.discover.DiscoverRepository
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RssFeed
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

/** Readable maximum width for the Settings list on expanded windows. */
internal val SettingsContentMaxWidth: Dp = 840.dp

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAiAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPrivateContent: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToStorage: () -> Unit = {},
    onNavigateToRss: () -> Unit = {},
    onResetDiscoverHistory: (suspend () -> Unit)? = null
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val currentTheme by AppConfig.currentTheme.collectAsStateWithLifecycle()
    val blurSetting = LocalOverlayBlurSetting.current
    val aiAnalysisMode by AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launchContext = LocalView.current.context
    val localConfig = LocalConfiguration.current
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    val discoverPreviewEnabled by AppConfig.discoverPreviewEnabled.collectAsStateWithLifecycle()
    val resetScope = rememberCoroutineScope()
    val resetDone = stringResource(R.string.settings_discover_reset_done)
    val dialogSurface = currentThemeSurfaces().resolve(SurfaceRole.DIALOG)

    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), modifier = modifier, header = {
        TidiloPageHeader(title = stringResource(R.string.settings_title), onNavigateBack = onNavigateBack)
    }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = SettingsContentMaxWidth).fillMaxWidth()) {
                SettingsSectionTitle(stringResource(R.string.settings_section_general))
                SettingsGroup {
                    SettingsNavigationRow(stringResource(R.string.settings_language_title), currentLanguage.displayName,
                        Icons.Default.Language, onClick = { showLanguageDialog = true })
                    SettingsDivider()
                    SettingsNavigationRow(
                        title = stringResource(R.string.settings_theme_title),
                        subtitle = stringResource(currentTheme.labelRes),
                        icon = Icons.Default.Palette,
                        onClick = { showThemeDialog = true }
                    )
                    SettingsDivider()
                    SettingsNavigationRow(stringResource(R.string.settings_notification_title),
                        stringResource(R.string.settings_notification_description), ImageVector.vectorResource(R.drawable.ic_notifications),
                        onClick = {
                            val intent = NotificationCoordinator.getInstance(context).settingsIntent()
                            if (launchContext !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            launchContext.startActivity(intent)
                        })
                    SettingsDivider()
                    SettingsNavigationRow(stringResource(R.string.settings_ai_analysis_title),
                        stringResource(if (aiAnalysisMode == AiAnalysisMode.AICORE) R.string.settings_ai_analysis_aicore
                            else R.string.settings_ai_analysis_disable), Icons.Default.AutoAwesome, onNavigateToAiAnalysis)
                    SettingsDivider()
                    SettingsNavigationRow(stringResource(R.string.settings_private_title),
                        stringResource(R.string.settings_private_subtitle), Icons.Default.Lock, onNavigateToPrivateContent)
                }
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle(stringResource(R.string.settings_section_data))
                SettingsGroup {
                    SettingsNavigationRow(stringResource(R.string.summary_title), stringResource(R.string.summary_subtitle),
                        ImageVector.vectorResource(R.drawable.ic_summarize), onNavigateToStorage)
                    SettingsDivider()
                    SettingsNavigationRow(stringResource(R.string.rss_title), stringResource(R.string.rss_subtitle),
                        Icons.Default.RssFeed, onNavigateToRss)
                    if (discoverPreviewEnabled) {
                        SettingsDivider()
                        SettingsNavigationRow(stringResource(R.string.settings_discover_reset_title),
                            stringResource(R.string.settings_discover_reset_subtitle), Icons.Default.History,
                            onClick = { showResetDialog = true }, modifier = Modifier.testTag("discover_reset_history"))
                    }
                }
                Spacer(Modifier.height(24.dp))
                SettingsSectionTitle(stringResource(R.string.settings_section_debug))
                SettingsGroup {
                    SettingsNavigationRow(stringResource(R.string.settings_debug_title), stringResource(R.string.settings_debug_subtitle),
                        Icons.Default.BugReport, onNavigateToDebug)
                }
            }
        }
    }

    if (showResetDialog) {
        TidiloAlertDialog(
            containerColor = dialogSurface.containerColor,
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_discover_reset_dialog_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.settings_discover_reset_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    resetScope.launch {
                        // A failed reset rolls back in its transaction; it must not crash Settings.
                        val reset = try {
                            onResetDiscoverHistory?.invoke() ?: DiscoverRepository.getInstance(context).resetHistory()
                            true
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.e("SettingsScreen", "Failed to reset rediscovery history", error)
                            false
                        }
                        if (reset) Toast.makeText(context, resetDone, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.settings_discover_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
            shape = dialogSurface.shape
        )
    }

    if (showLanguageDialog) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides localConfig
        ) {
            TidiloAlertDialog(
                containerColor = dialogSurface.containerColor,
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
                        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                            AppLanguage.entries.forEach { lang ->
                                val isSelected = lang == currentLanguage
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.RadioButton,
                                            onClick = {
                                                AppConfig.setLanguage(lang)
                                                showLanguageDialog = false
                                            }
                                        )
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = null
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
                shape = dialogSurface.shape
            )
        }
    }

    if (showThemeDialog) {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides localConfig
        ) {
            TidiloAlertDialog(
                containerColor = dialogSurface.containerColor,
                onDismissRequest = { showThemeDialog = false },
                title = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Text(
                            text = stringResource(R.string.settings_theme_dialog_title),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Column(modifier = Modifier.selectableGroup()) {
                                AppTheme.entries.forEach { theme ->
                                    val isSelected = theme == currentTheme
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = isSelected,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    AppConfig.setTheme(theme)
                                                    showThemeDialog = false
                                                }
                                            )
                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = stringResource(theme.labelRes),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                            ThemeBlurSetting(blurSetting, AppConfig::setOverlayBlurEnabled)
                        }
                    }
                },
                confirmButton = {
                    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig) {
                        TextButton(onClick = { showThemeDialog = false }) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                },
                shape = dialogSurface.shape
            )
        }
    }
}
