package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssSettings

@Composable
fun RssSettingsScreen(onNavigateBack: () -> Unit, model: RssSettingsViewModel = viewModel()) {
    val feeds by model.feeds.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val wifi by model.wifi.collectAsStateWithLifecycle()
    val registration by model.registration.collectAsStateWithLifecycle()
    val initialFetch by model.initialFetch.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.startListening()
        onPauseOrDispose { model.stopListening() }
    }
    RssSettingsContent(feeds, settings, busy, error, wifi, onNavigateBack, model::retry,
        model::saveSettings, model::saveFeed, model::deleteFeed, model::fetchNow, model::clearError, model::setEnabled)
    if (initialFetch != null) {
        val mobileNotice = settings.wifiOnly && wifi == false
        RssInitialFetchDialog(mobileNotice, online == true && wifi != null, busy,
            onFetch = { model.fetchInitial(mobileNotice) }, onLater = model::dismissInitialFetch)
    }
    registration?.let {
        RssRegistrationDialog(it.snapshot.savedCount, it.limit, busy, model::confirmRegistration, model::dismissRegistration)
    }
}

@Composable
internal fun RssInitialFetchDialog(mobileNotice: Boolean, connected: Boolean, busy: Boolean,
    onFetch: () -> Unit, onLater: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onLater() }, title = { Text(stringResource(R.string.rss_initial_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rss_initial_message))
            if (connected && mobileNotice) Text(stringResource(R.string.rss_initial_mobile), color = MaterialTheme.colorScheme.error)
            if (!connected) Text(stringResource(R.string.rss_initial_offline), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onFetch, enabled = connected && !busy) { Text(stringResource(R.string.rss_fetch_now)) } },
        dismissButton = { TextButton(onClick = onLater, enabled = !busy) { Text(stringResource(R.string.rss_later)) } })
}

@Composable
internal fun RssRegistrationDialog(count: Int, limit: Int?, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rss_register_existing_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rss_register_existing_count, count))
            Text(if (limit == null) stringResource(R.string.rss_register_existing_unlimited)
                else stringResource(R.string.rss_register_existing_limit, limit))
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.rss_register)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.rss_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssSettingsContent(
    feeds: SummaryLoad<List<RssFeedEntity>>, settings: RssSettings, busy: Boolean, error: String?, wifi: Boolean?,
    onBack: () -> Unit, onRetry: () -> Unit, onSettings: (RssSettings) -> Unit,
    onFeed: (RssFeedEntity?, String, String, Int?, Boolean) -> Unit, onDelete: (String) -> Unit,
    onFetch: () -> Unit, onDismissError: () -> Unit, onEnabled: (String, Boolean) -> Unit = { _, _ -> }
) {
    var showTime by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RssFeedEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RssFeedEntity?>(null) }
    TidiloScreenScaffold(
        hideHeaderOnScroll = false,
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.rss_title),
                onNavigateBack = onBack
            )
        }
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RssCard {
                Text(stringResource(R.string.rss_schedule), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showTime = true }, enabled = !busy,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 56.dp)) {
                        Text(String.format(Locale.US, "%02d:%02d", settings.hour, settings.minute),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (settings.wifiOnly && wifi == false) Text(stringResource(R.string.rss_wifi_blocked),
                        modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().toggleable(settings.wifiOnly, enabled = !busy, role = Role.Switch,
                    onValueChange = { onSettings(settings.copy(wifiOnly = it)) }), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rss_wifi_only), Modifier.weight(1f))
                    Switch(checked = settings.wifiOnly, onCheckedChange = null, enabled = !busy)
                }
                Text(stringResource(R.string.rss_schedule_note), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onFetch, enabled = !busy && (feeds as? SummaryLoad.Ready)?.value?.any { it.enabled } == true) {
                    Text(stringResource(R.string.rss_fetch_now))
                }
            }
            val ready = (feeds as? SummaryLoad.Ready)?.value.orEmpty()
            val aiFeeds = ready.count { it.enabled && it.mode == "AI" }
            RssCard {
                Text(stringResource(R.string.rss_max_queue, aiFeeds * 5), style = MaterialTheme.typography.bodySmall)
                if (aiFeeds * 5 > 20) Text(stringResource(R.string.rss_quota_warning), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.rss_queue_note), style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) RssCard {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.rss_dismiss)) }
            }
            Button(onClick = { editing = null; showEditor = true }, enabled = !busy) { Text(stringResource(R.string.rss_add_feed)) }
            when (feeds) {
                SummaryLoad.Loading -> Text(stringResource(R.string.summary_loading))
                SummaryLoad.Error -> {
                    Text(stringResource(R.string.summary_error))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.summary_retry)) }
                }
                is SummaryLoad.Ready -> {
                    if (ready.isEmpty()) Text(stringResource(R.string.rss_no_feeds))
                    ready.forEach { feed ->
                        key(feed.id) {
                            RssCard(compact = true) {
                                Text(feed.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(feed.url, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (feed.mode == "AI") R.string.rss_ai_mode else R.string.rss_direct_mode),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(feed.retentionLimit?.let { stringResource(R.string.rss_retention_compact, it) }
                                        ?: stringResource(R.string.rss_unlimited),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                feed.error?.let {
                                    Text(if (it == "XML_FETCH_FAILED") stringResource(R.string.rss_xml_fetch_failed) else it,
                                        color = MaterialTheme.colorScheme.error)
                                    if (it == "XML_FETCH_FAILED" && !feed.enabled) Text(stringResource(R.string.rss_enable_to_retry),
                                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                val enabledLabel = stringResource(R.string.rss_enabled)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { editing = feed; showEditor = true }, enabled = !busy) { Text(stringResource(R.string.rss_edit_compact)) }
                                    TextButton(onClick = { deleting = feed }, enabled = !busy) { Text(stringResource(R.string.common_delete)) }
                                    Spacer(Modifier.weight(1f))
                                    Row(Modifier.toggleable(feed.enabled, enabled = !busy, role = Role.Switch,
                                        onValueChange = { onEnabled(feed.id, it) }).semantics { contentDescription = enabledLabel },
                                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(if (feed.enabled) R.string.rss_on else R.string.rss_off),
                                            style = MaterialTheme.typography.labelSmall)
                                        Switch(checked = feed.enabled, onCheckedChange = null, enabled = !busy)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showTime) {
        val time = rememberTimePickerState(settings.hour, settings.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { showTime = false }, title = { Text(stringResource(R.string.rss_schedule)) },
            text = { TimePicker(time) }, confirmButton = {
                TextButton(onClick = { onSettings(settings.copy(hour = time.hour, minute = time.minute)); showTime = false }) {
                    Text(stringResource(R.string.rss_save))
                }
            }, dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.rss_cancel)) } })
    }
    if (showEditor) RssFeedEditor(editing, onDismiss = { showEditor = false }) { url, mode, limit, convert ->
        onFeed(editing, url, mode, limit, convert)
        showEditor = false
    }
    deleting?.let { feed ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.rss_delete)) },
            text = { Text(stringResource(R.string.rss_delete_note)) },
            confirmButton = { TextButton(onClick = { onDelete(feed.id); deleting = null }) { Text(stringResource(R.string.rss_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.rss_cancel)) } })
    }
}

@Composable
internal fun RssFeedEditor(original: RssFeedEntity?, onDismiss: () -> Unit, onSave: (String, String, Int?, Boolean) -> Unit) {
    val aiAnalysisMode by me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    var url by remember(original) { mutableStateOf(original?.url.orEmpty()) }
    var mode by remember(original) { mutableStateOf(original?.mode ?: "DIRECT") }
    var unlimited by remember(original) { mutableStateOf(original != null && original.retentionLimit == null) }
    var limit by remember(original) { mutableStateOf((original?.retentionLimit ?: 30).toString()) }
    var convert by remember { mutableStateOf(false) }
    val validLimit = unlimited || limit.toIntOrNull()?.let { it in 10..500 } == true
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (original == null) R.string.rss_add_feed else R.string.rss_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.rss_feed_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                Text(stringResource(R.string.rss_import_mode), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    Row(Modifier.fillMaxWidth().selectable(mode == "DIRECT", role = Role.RadioButton,
                        onClick = { mode = "DIRECT" }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "DIRECT", onClick = null)
                        Text(stringResource(R.string.rss_direct_mode))
                    }
                    Row(Modifier.fillMaxWidth().selectable(mode == "AI", role = Role.RadioButton,
                        onClick = { mode = "AI" }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == "AI", onClick = null)
                        Text(stringResource(R.string.rss_ai_mode))
                    }
                }
                if (aiAnalysisMode == me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE) {
                    Text(stringResource(R.string.rss_ai_disabled_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Text(stringResource(R.string.rss_max_saved_items), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().toggleable(unlimited, role = Role.Checkbox,
                    onValueChange = { unlimited = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(unlimited, null)
                    Text(stringResource(R.string.rss_unlimited))
                }
                if (!unlimited) OutlinedTextField(limit, { limit = it },
                    label = { Text(stringResource(R.string.rss_retention_input)) }, isError = !validLimit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.rss_retention_note), style = MaterialTheme.typography.bodySmall)
                if (original?.mode == "AI" && mode == "DIRECT") {
                    Text(stringResource(R.string.rss_mode_change_note), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().toggleable(convert, role = Role.Checkbox,
                        onValueChange = { convert = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(convert, null)
                        Text(stringResource(R.string.rss_convert_pending))
                    }
                }
                if (original != null && url.trim() != original.url) Text(stringResource(R.string.rss_url_change_note),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url.trim(), mode, if (unlimited) null else limit.toInt(), convert) },
            enabled = url.isNotBlank() && validLimit) { Text(stringResource(R.string.rss_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rss_cancel)) } })
}

@Composable
private fun RssCard(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp), content = content)
    }
}
