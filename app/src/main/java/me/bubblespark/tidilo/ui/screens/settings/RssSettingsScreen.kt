package me.bubblespark.tidilo.ui.screens.settings

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet

import me.bubblespark.tidilo.ui.components.TidiloAlertDialog

import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.theme.SurfaceRole

import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.screenActionInsets
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import me.bubblespark.tidilo.data.notifications.hasPostNotificationsPermission
import me.bubblespark.tidilo.data.rss.replaceRssTriggerTime
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.components.rememberFeedVisual
import me.bubblespark.tidilo.ui.theme.DestructiveActionColors

private val TRIGGER_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

@Composable
fun RssSettingsScreen(
    onNavigateBack: () -> Unit,
    model: RssSettingsViewModel = viewModel(),
    isNotificationPermissionGranted: (Context) -> Boolean = ::hasPostNotificationsPermission,
    onLaunchNotificationPermission: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val feeds by model.feeds.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val manualFetch by model.manualFetch.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val wifi by model.wifi.collectAsStateWithLifecycle()
    val registration by model.registration.collectAsStateWithLifecycle()
    val initialFetch by model.initialFetch.collectAsStateWithLifecycle()
    val notificationRationale by model.notificationRationale.collectAsStateWithLifecycle()
    val permissionLaunchPending by model.permissionLaunchPending.collectAsStateWithLifecycle()
    val online by model.online.collectAsStateWithLifecycle()
    val settingsReady by model.settingsReady.collectAsStateWithLifecycle()
    val manualMobilePrompt by model.manualMobilePrompt.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        model::onNotificationPermissionResult
    )
    var isResumed by remember { mutableStateOf(false) }
    val currentLaunchOverride by rememberUpdatedState(onLaunchNotificationPermission)
    LaunchedEffect(permissionLaunchPending, isResumed, permissionLauncher) {
        if (permissionLaunchPending && isResumed && model.consumePermissionLaunchRequest()) {
            val override = currentLaunchOverride
            if (override != null) {
                override()
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    LifecycleResumeEffect(model) {
        isResumed = true
        model.startListening()
        onPauseOrDispose {
            isResumed = false
            model.stopListening()
        }
    }
    RssSettingsContent(
        feeds = feeds,
        settings = settings,
        busy = busy,
        error = error,
        wifi = wifi,
        onBack = onNavigateBack,
        onRetry = model::retry,
        onSettings = { updated -> model.saveSettings(updated, isNotificationPermissionGranted(context)) },
        onFeed = model::saveFeed,
        onDelete = model::deleteFeed,
        onFetch = model::fetchNow,
        onDismissError = model::clearError,
        onEnabled = model::setEnabled,
        manualFetch = manualFetch,
        settingsReady = settingsReady
    )
    if (manualMobilePrompt) {
        RssManualFetchDialog(
            busy = busy,
            onChoice = model::confirmManualFetch,
            onDismiss = model::dismissManualFetchPrompt,
            error = error
        )
    }
    if (initialFetch != null) {
        val mobileNotice = settings.wifiOnly && wifi == false
        RssInitialFetchDialog(
            mobileNotice = mobileNotice,
            connected = online == true && wifi != null,
            busy = busy,
            onFetch = { model.fetchInitial(mobileNotice, isNotificationPermissionGranted(context)) },
            onLater = { model.dismissInitialFetch(isNotificationPermissionGranted(context)) }
        )
    }
    registration?.let {
        RssRegistrationDialog(it.snapshot.savedCount, busy, model::confirmRegistration, model::dismissRegistration)
    }
    if (notificationRationale && initialFetch == null) {
        RssNotificationPermissionDialog(
            onAllow = { model.consumeNotificationRationale(requestPermission = true) },
            onDismiss = { model.consumeNotificationRationale(requestPermission = false) }
        )
    }
}

@Composable
internal fun RssNotificationPermissionDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    TidiloAlertDialog(
        modifier = modifier,
        containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
        shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rss_notification_permission_title)) },
        text = {
            Text(
                text = stringResource(R.string.rss_notification_permission_message),
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(
                onClick = onAllow,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.rss_notification_permission_allow))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.rss_later))
            }
        }
    )
}

@Composable
internal fun RssManualFetchDialog(
    busy: Boolean,
    onChoice: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null
) {
    val surface = currentThemeSurfaces().resolve(SurfaceRole.DIALOG)
    TidiloAlertDialog(
        containerColor = surface.containerColor,
        shape = surface.shape,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.rss_manual_mobile_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rss_manual_mobile_message))
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoice(true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.rss_manual_use_mobile))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(false) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.rss_manual_wait_wifi))
            }
        }
    )
}

@Composable
internal fun RssInitialFetchDialog(mobileNotice: Boolean, connected: Boolean, busy: Boolean,
    onFetch: () -> Unit, onLater: () -> Unit) {
    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,onDismissRequest = { if (!busy) onLater() }, title = { Text(stringResource(R.string.rss_initial_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rss_initial_message))
            if (connected && mobileNotice) Text(stringResource(R.string.rss_initial_mobile), color = MaterialTheme.colorScheme.error)
            if (!connected) Text(stringResource(R.string.rss_initial_offline), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onFetch, enabled = connected && !busy) { Text(stringResource(R.string.rss_fetch_now)) } },
        dismissButton = { TextButton(onClick = onLater, enabled = !busy) { Text(stringResource(R.string.rss_later)) } })
}

@Composable
internal fun RssRegistrationDialog(count: Int, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rss_register_existing_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.rss_register_existing_count, count))
            Text(stringResource(R.string.rss_retention_note))
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.rss_register)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.rss_cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssSettingsContent(
    feeds: StorageLoad<List<RssFeedEntity>>, settings: RssSettings, busy: Boolean, error: String?, wifi: Boolean?,
    onBack: () -> Unit, onRetry: () -> Unit, onSettings: (RssSettings) -> Unit,
    onFeed: (RssFeedEntity?, String) -> Unit, onDelete: (String) -> Unit,
    onFetch: () -> Unit, onDismissError: () -> Unit, onEnabled: (String, Boolean) -> Unit = { _, _ -> },
    manualFetch: ManualFetchStatus = ManualFetchStatus.Idle, settingsReady: Boolean = true
) {
    val context = LocalContext.current
    var showTime by rememberSaveable { mutableStateOf(false) }
    var timeToEdit by rememberSaveable { mutableStateOf<Int?>(null) }
    var deletingTriggerMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentSettings by rememberUpdatedState(settings)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val readyFeeds = (feeds as? StorageLoad.Ready)?.value.orEmpty()
    val editing = readyFeeds.firstOrNull { it.id == editingId }
    val deleting = readyFeeds.firstOrNull { it.id == deletingId }
    val listState = rememberLazyListState()
    val showAddFab = readyFeeds.isNotEmpty()
    val openNewFeed = { editingId = null; showEditor = true }
    TidiloScreenScaffold(
        contentWindowInsets = screenSystemInsets(),
        bottomContentPadding = if (showAddFab) HeaderDefaults.RssActionClearance else 0.dp,
        hideHeaderOnScroll = false,
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.rss_title),
                onNavigateBack = onBack,
                scrollState = listState
            )
        },
        overlay = {
        if (showAddFab) FloatingActionButton(onClick = { if (!busy) openNewFeed() },
            modifier = Modifier.align(Alignment.BottomEnd).screenActionInsets()
                .padding(end = HeaderDefaults.HorizontalPadding + 16.dp, bottom = 16.dp).size(HeaderDefaults.RssActionSize)
                .semantics { if (busy) disabled() },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.rss_add_feed))
        }
        }
    ) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState,
            contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) item(key = "error") { RssCard {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.rss_dismiss)) }
            } }
            if (readyFeeds.isNotEmpty()) {
                item(key = "feed-heading") { Text(stringResource(R.string.rss_feed_count, readyFeeds.size),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            }
            when (feeds) {
                StorageLoad.Loading -> item(key = "loading") { Text(stringResource(R.string.summary_loading)) }
                StorageLoad.Error -> item(key = "load-error") {
                    Column {
                    Text(stringResource(R.string.summary_error))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.summary_retry)) }
                    }
                }
                is StorageLoad.Ready -> {
                    if (readyFeeds.isEmpty()) item(key = "empty", contentType = "first-feed") {
                        RssCard {
                            Text(stringResource(R.string.rss_first_feed_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.rss_first_feed_description), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.rss_first_feed_example), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = openNewFeed, enabled = !busy) {
                                Text(stringResource(R.string.rss_add_feed))
                            }
                        }
                    }
                    items(readyFeeds, key = { "feed:${it.id}" }, contentType = { "feed" }) { feed ->
                            RssFeedCard(
                                feed = feed,
                                busy = busy,
                                onEdit = { editingId = feed.id; showEditor = true },
                                onDelete = { deletingId = feed.id },
                                onEnabled = { onEnabled(feed.id, it) },
                                context = context
                            )
                    }
                }
            }
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
                if (settings.wifiOnly && wifi == false) Text(stringResource(R.string.rss_wifi_blocked),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().toggleable(settings.wifiOnly, enabled = !busy && settingsReady, role = Role.Switch,
                    onValueChange = { onSettings(settings.copy(wifiOnly = it)) }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RssPreferenceIcon(Icons.Default.Wifi)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rss_wifi_only))
                        Text(stringResource(R.string.rss_wifi_only_description), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.wifiOnly, onCheckedChange = null, enabled = !busy && settingsReady)
                }
                Text(stringResource(R.string.rss_schedule_note), style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = onFetch, modifier = Modifier.fillMaxWidth(), enabled = !busy && settingsReady && manualFetch == ManualFetchStatus.Idle && readyFeeds.any { it.enabled }) {
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
            item(key = "trigger-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rss_trigger_time), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.rss_trigger_count, settings.triggerTimes.size),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { timeToEdit = null; showTime = true }, enabled = !busy && settings.triggerTimes.size < 3,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.Default.Add, stringResource(R.string.rss_add_trigger_time))
                    }
                }
            }
            if (settings.triggerTimes.isEmpty()) item(key = "trigger-empty") {
                RssCard(compact = true) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.rss_trigger_unset), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(settings.triggerTimes.sorted(), key = { "trigger:${it.hour * 60 + it.minute}" }, contentType = { "trigger" }) { trigger ->
                RssTriggerTimeCard(trigger, busy,
                    onRemove = { deletingTriggerMinute = trigger.hour * 60 + trigger.minute },
                    onEdit = { timeToEdit = trigger.hour * 60 + trigger.minute; showTime = true })
            }

        }

        }
    }
    deletingTriggerMinute?.let { minute ->
        val trigger = LocalTime.of(minute / 60, minute % 60)
        LaunchedEffect(settings.triggerTimes, minute) {
            if (trigger !in settings.triggerTimes) deletingTriggerMinute = null
        }
        if (trigger in settings.triggerTimes) TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,
            onDismissRequest = { deletingTriggerMinute = null },
            title = { Text(stringResource(R.string.rss_delete_trigger_title)) },
            text = { Text(stringResource(R.string.rss_delete_trigger_message,
                trigger.format(TRIGGER_TIME_FORMATTER))) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                if (deletingTriggerMinute != null && !busy) {
                    deletingTriggerMinute = null
                    if (trigger in currentSettings.triggerTimes) onSettings(currentSettings.copy(triggerTimes = currentSettings.triggerTimes - trigger))
                }
            }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { deletingTriggerMinute = null }) { Text(stringResource(R.string.rss_cancel)) } }
        )
    }
    if (showTime) {
        val original = timeToEdit?.let { LocalTime.of(it / 60, it % 60) }
        val time = rememberTimePickerState(original?.hour ?: 8, original?.minute ?: 0, is24Hour = true)
        val replacement = LocalTime.of(time.hour, time.minute)
        val duplicate = settings.triggerTimes.any { it == replacement && it != original }
        LaunchedEffect(settings.triggerTimes, original) {
            if ((original != null && original !in settings.triggerTimes) || (original == null && settings.triggerTimes.size >= 3)) showTime = false
        }
        TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,onDismissRequest = { showTime = false }, title = { Text(stringResource(R.string.rss_trigger_time)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimePicker(time)
                if (duplicate) Text(stringResource(R.string.rss_trigger_duplicate), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {
                TextButton(enabled = !busy && !duplicate, onClick = {
                    if (showTime && !busy) {
                        val updated = replaceRssTriggerTime(currentSettings.triggerTimes, original, replacement)
                        showTime = false
                        if (updated != null) onSettings(currentSettings.copy(triggerTimes = updated))
                    }
                }) {
                    Text(stringResource(if (original == null) R.string.rss_add_trigger else R.string.rss_save))
                }
            }, dismissButton = { TextButton(onClick = { showTime = false }) { Text(stringResource(R.string.rss_cancel)) } })
    }
    if (showEditor && (editingId == null || editing != null)) RssFeedEditor(editing, onDismiss = { showEditor = false }, onDelete = editing?.let { feed -> { showEditor = false; deletingId = feed.id } }) { url ->
        onFeed(editing, url)
        showEditor = false
    }
    deleting?.let { feed ->
        TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,onDismissRequest = { deletingId = null }, title = { Text(stringResource(R.string.rss_delete)) },
            text = { Text(stringResource(R.string.rss_delete_note)) },
            confirmButton = { TextButton(onClick = { onDelete(feed.id); deletingId = null }) { Text(stringResource(R.string.rss_delete)) } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text(stringResource(R.string.rss_cancel)) } })
    }
}

@Composable
private fun RssTriggerTimeCard(time: LocalTime, busy: Boolean, onRemove: () -> Unit, onEdit: () -> Unit) {
    val label = time.format(TRIGGER_TIME_FORMATTER)
    val editLabel = stringResource(R.string.rss_edit_trigger_time, label)
    RssCard(compact = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.size(48.dp)) {
                TidiloSurface(role = SurfaceRole.DESTRUCTIVE_ACTION, modifier = Modifier.size(20.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Remove, stringResource(R.string.rss_remove_trigger_time, label),
                            tint = currentThemeSurfaces().resolve(SurfaceRole.DESTRUCTIVE_ACTION).contentColor, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = editLabel }) {
                Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
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
private fun RssFeedCard(
    feed: RssFeedEntity,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    context: Context = LocalContext.current
) {
    val visualSource = LocalCacheVisualSource.current
    val icon by rememberFeedVisual(feed, context = context, source = visualSource)
    val enabledLabel = stringResource(R.string.rss_enabled)
    val deleteLabel = stringResource(R.string.rss_delete)
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !busy, onClick = onEdit, onLongClickLabel = deleteLabel,
        onLongClick = onDelete), shape = themeCorner(ThemeCorner.CARD),
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
                    Text(visualSource.feedPublisherDomain(feed) ?: feed.url, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.toggleable(feed.enabled, enabled = !busy, role = Role.Switch, onValueChange = onEnabled)
                    .semantics { contentDescription = enabledLabel }, verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = feed.enabled, onCheckedChange = null, enabled = !busy)
                }
            }
            feed.error?.let {
                Text(if (it == "XML_FETCH_FAILED") stringResource(R.string.rss_xml_fetch_failed) else it,
                    color = MaterialTheme.colorScheme.error)
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssFeedEditor(original: RssFeedEntity?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String) -> Unit) {
    var url by rememberSaveable(original?.id) { mutableStateOf(original?.url.orEmpty()) }
    var showRetentionDetails by rememberSaveable(original?.id) { mutableStateOf(false) }
    TidiloModalBottomSheet(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.SHEET).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.SHEET).shape,onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (original == null) R.string.rss_add_feed else R.string.rss_edit), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rss_cancel)) }
                }
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.rss_feed_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                Text(stringResource(R.string.rss_retention_unread), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.rss_retention_interacted), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.rss_retention_protected), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.rss_retention_permanent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = { showRetentionDetails = !showRetentionDetails }) {
                    Text(stringResource(
                        if (showRetentionDetails) R.string.rss_retention_hide_details else R.string.rss_retention_details
                    ))
                }
                if (showRetentionDetails) {
                    Text(stringResource(R.string.rss_retention_interactions), style = MaterialTheme.typography.bodySmall)
                }
                if (original != null && url.trim() != original.url) Text(stringResource(R.string.rss_url_change_note),
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.rss_delete), color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(url.trim()) },
                        enabled = url.isNotBlank()) { Text(stringResource(R.string.rss_save)) }
                }
                Spacer(Modifier.height(16.dp))
            }
    }
}

@Composable
private fun RssCard(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    TidiloSurface(role = SurfaceRole.SETTINGS_GROUP, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp), content = content)
    }
}
