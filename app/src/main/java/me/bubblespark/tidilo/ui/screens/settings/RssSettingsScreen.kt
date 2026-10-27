package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.HeaderDefaults

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
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
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.components.rememberFeedIcon

@Composable
fun RssSettingsScreen(onNavigateBack: () -> Unit, model: RssSettingsViewModel = viewModel()) {
    val feeds by model.feeds.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val manualFetch by model.manualFetch.collectAsStateWithLifecycle()
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
        model::saveSettings, model::saveFeed, model::deleteFeed, model::fetchNow, model::clearError, model::setEnabled, manualFetch)
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
    onFetch: () -> Unit, onDismissError: () -> Unit, onEnabled: (String, Boolean) -> Unit = { _, _ -> },
    manualFetch: ManualFetchStatus = ManualFetchStatus.Idle
) {
    var showTime by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val readyFeeds = (feeds as? SummaryLoad.Ready)?.value.orEmpty()
    val editing = readyFeeds.firstOrNull { it.id == editingId }
    val deleting = readyFeeds.firstOrNull { it.id == deletingId }
    val listState = rememberLazyListState()
    TidiloScreenScaffold(
        hideHeaderOnScroll = false,
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.rss_title),
                onNavigateBack = onBack,
                scrollState = listState
            )
        }
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = listState,
            contentPadding = PaddingValues(start = HeaderDefaults.HorizontalPadding, end = HeaderDefaults.HorizontalPadding,
                top = contentPadding.calculateTopPadding(), bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "schedule-heading") {
            Text(stringResource(R.string.rss_schedule), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            item(key = "schedule") {
            RssCard {
                Row(Modifier.fillMaxWidth().toggleable(settings.dailyEnabled, enabled = !busy, role = Role.Switch,
                    onValueChange = { onSettings(settings.copy(dailyEnabled = it)) }).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RssPreferenceIcon(Icons.Default.Sync)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rss_daily_fetch), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.rss_daily_fetch_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.dailyEnabled, onCheckedChange = null, enabled = !busy)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().alpha(if (settings.dailyEnabled) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RssPreferenceIcon(Icons.Default.Schedule)
                    Text(stringResource(R.string.rss_schedule), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { showTime = true }, enabled = !busy && settings.dailyEnabled,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 56.dp)) {
                        Text(String.format(Locale.US, "%02d:%02d", settings.hour, settings.minute),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (settings.wifiOnly && wifi == false) Text(stringResource(R.string.rss_wifi_blocked),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().alpha(if (settings.dailyEnabled) 1f else 0.45f).toggleable(settings.wifiOnly, enabled = !busy && settings.dailyEnabled, role = Role.Switch,
                    onValueChange = { onSettings(settings.copy(wifiOnly = it)) }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RssPreferenceIcon(Icons.Default.Wifi)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.rss_wifi_only), Modifier.weight(1f))
                    Switch(checked = settings.wifiOnly, onCheckedChange = null, enabled = !busy && settings.dailyEnabled)
                }
                Text(stringResource(R.string.rss_schedule_note), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = onFetch, modifier = Modifier.fillMaxWidth(), enabled = !busy && manualFetch == ManualFetchStatus.Idle && (feeds as? SummaryLoad.Ready)?.value?.any { it.enabled } == true) {
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        if (manualFetch == ManualFetchStatus.Running) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(when (manualFetch) {
                        ManualFetchStatus.Checking -> R.string.rss_fetch_checking
                        ManualFetchStatus.Waiting -> R.string.rss_fetch_waiting
                        else -> R.string.rss_fetch_now
                    }))
                }
            }
            }
            val ready = readyFeeds
            val aiFeeds = ready.count { it.enabled && it.mode == "AI" }
            item(key = "queue") {
            RssCard {
                Text(stringResource(R.string.rss_max_queue, aiFeeds * 5), style = MaterialTheme.typography.bodySmall)
                if (aiFeeds * 5 > 20) Text(stringResource(R.string.rss_quota_warning), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.rss_queue_note), style = MaterialTheme.typography.bodySmall)
            }
            }
            if (error != null) item(key = "error") { RssCard {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.rss_dismiss)) }
            } }
            if (feeds is SummaryLoad.Ready) item(key = "feed-heading") { Text(stringResource(R.string.rss_feed_count, ready.size),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            when (feeds) {
                SummaryLoad.Loading -> item(key = "loading") { Text(stringResource(R.string.summary_loading)) }
                SummaryLoad.Error -> item(key = "load-error") {
                    Column {
                    Text(stringResource(R.string.summary_error))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.summary_retry)) }
                    }
                }
                is SummaryLoad.Ready -> {
                    if (ready.isEmpty()) item(key = "empty") { RssCard { Text(stringResource(R.string.rss_no_feeds)) } }
                    items(ready, key = { "feed:${it.id}" }, contentType = { "feed" }) { feed ->
                            RssFeedCard(feed, busy, onEdit = { editingId = feed.id; showEditor = true },
                                onDelete = { deletingId = feed.id }, onEnabled = { onEnabled(feed.id, it) })
                    }
                }
            }
        }
        FloatingActionButton(onClick = { if (!busy) { editingId = null; showEditor = true } },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(end = HeaderDefaults.HorizontalPadding + 16.dp, bottom = 16.dp)
                .semantics { if (busy) disabled() },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.rss_add_feed))
        }
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
    if (showEditor && (editingId == null || editing != null)) RssFeedEditor(editing, onDismiss = { showEditor = false }, onDelete = editing?.let { feed -> { showEditor = false; deletingId = feed.id } }) { url, mode, limit, convert ->
        onFeed(editing, url, mode, limit, convert)
        showEditor = false
    }
    deleting?.let { feed ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text(stringResource(R.string.rss_delete)) },
            text = { Text(stringResource(R.string.rss_delete_note)) },
            confirmButton = { TextButton(onClick = { onDelete(feed.id); deletingId = null }) { Text(stringResource(R.string.rss_delete)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.rss_cancel)) } })
    }
}

@Composable
private fun RssPreferenceIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RssFeedCard(feed: RssFeedEntity, busy: Boolean, onEdit: () -> Unit,
    onDelete: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val icon by rememberFeedIcon(feed)
    val enabledLabel = stringResource(R.string.rss_enabled)
    val deleteLabel = stringResource(R.string.rss_delete)
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !busy, onClick = onEdit, onLongClickLabel = deleteLabel,
        onLongClick = onDelete), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        val bitmap = icon.bitmap
                        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(28.dp))
                        else Text(feed.title.take(1).uppercase(Locale.ROOT), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(feed.title, modifier = Modifier.fillMaxWidth().basicMarquee(initialDelayMillis = 2000, repeatDelayMillis = 2000),
                        maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(DomainIconManager.feedPublisherDomain(feed) ?: feed.url, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            feed.error?.let {
                Text(if (it == "XML_FETCH_FAILED") stringResource(R.string.rss_xml_fetch_failed) else it,
                    color = MaterialTheme.colorScheme.error)
                if (it == "XML_FETCH_FAILED" && !feed.enabled) Text(stringResource(R.string.rss_enable_to_retry),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (label in listOf(stringResource(if (feed.mode == "AI") R.string.rss_ai_mode else R.string.rss_direct_mode),
                    feed.retentionLimit?.let { stringResource(R.string.rss_retention_compact, it) } ?: stringResource(R.string.rss_unlimited))) {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                }
                Row(Modifier.toggleable(feed.enabled, enabled = !busy, role = Role.Switch, onValueChange = onEnabled)
                    .semantics { contentDescription = enabledLabel }, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = feed.enabled, onCheckedChange = null, enabled = !busy)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssFeedEditor(original: RssFeedEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String, String, Int?, Boolean) -> Unit) {
    val aiAnalysisMode by me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    var url by rememberSaveable(original?.id) { mutableStateOf(original?.url.orEmpty()) }
    var mode by rememberSaveable(original?.id) { mutableStateOf(original?.mode ?: "DIRECT") }
    var unlimited by rememberSaveable(original?.id) { mutableStateOf(original != null && original.retentionLimit == null) }
    var limit by rememberSaveable(original?.id) { mutableStateOf((original?.retentionLimit ?: 30).toString()) }
    var convert by rememberSaveable(original?.id) { mutableStateOf(false) }
    val validLimit = unlimited || limit.toIntOrNull()?.let { it in 10..500 } == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (original == null) R.string.rss_add_feed else R.string.rss_edit), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rss_cancel)) }
                }
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.rss_feed_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                Text(stringResource(R.string.rss_import_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DIRECT" to R.string.rss_direct_mode, "AI" to R.string.rss_ai_mode).forEach { (value, label) ->
                        Surface(modifier = Modifier.weight(1f).selectable(mode == value, role = Role.RadioButton, onClick = { mode = value }),
                            shape = RoundedCornerShape(12.dp), color = if (mode == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text(stringResource(label)) }
                        }
                    }
                }
                if (aiAnalysisMode == me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE) {
                    Text(stringResource(R.string.rss_ai_disabled_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Text(stringResource(R.string.rss_max_saved_items), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().toggleable(unlimited, role = Role.Switch,
                    onValueChange = { unlimited = it }), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rss_unlimited), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(unlimited, null)
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.rss_delete), color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(url.trim(), mode, if (unlimited) null else limit.toInt(), convert) },
                        enabled = url.isNotBlank() && validLimit) { Text(stringResource(R.string.rss_save)) }
                }
                Spacer(Modifier.height(16.dp))
            }
    }
}

@Composable
private fun RssCard(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp), content = content)
    }
}
