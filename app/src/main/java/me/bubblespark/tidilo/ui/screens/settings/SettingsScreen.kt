package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAiAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToDebug: () -> Unit = {},
    onNavigateToStorage: () -> Unit = {},
    onNavigateToSummary: () -> Unit = onNavigateToStorage,
    onNavigateToRss: () -> Unit = {}
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val aiAnalysisMode by AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launchContext = LocalView.current.context
    val localConfig = LocalConfiguration.current
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }

    TidiloScreenScaffold(modifier = modifier, header = {
        TidiloPageHeader(title = stringResource(R.string.settings_title), onNavigateBack = onNavigateBack)
    }) { contentPadding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)
            .navigationBarsPadding().padding(bottom = 32.dp)) {
            SettingsSectionTitle(stringResource(R.string.settings_section_general))
            SettingsGroup {
                SettingsNavigationRow(stringResource(R.string.settings_language_title), currentLanguage.displayName,
                    Icons.Default.Language, onClick = { showLanguageDialog = true })
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
            }
            Spacer(Modifier.height(24.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_data))
            SettingsGroup {
                SettingsNavigationRow(stringResource(R.string.summary_title), stringResource(R.string.summary_subtitle),
                    ImageVector.vectorResource(R.drawable.ic_summarize), onNavigateToStorage)
                SettingsDivider()
                SettingsNavigationRow(stringResource(R.string.rss_title), stringResource(R.string.rss_subtitle),
                    Icons.Default.RssFeed, onNavigateToRss)
            }
            Spacer(Modifier.height(24.dp))
            SettingsSectionTitle(stringResource(R.string.settings_section_debug))
            SettingsGroup {
                SettingsNavigationRow(stringResource(R.string.settings_debug_title), stringResource(R.string.settings_debug_subtitle),
                    Icons.Default.BugReport, onNavigateToDebug)
            }
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
