package me.bubblespark.tidilo.ui.screens.settings
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.rememberFeedIcon
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
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
    var isDailyFetchEnabled by remember { mutableStateOf(true) }
    val ready = (feeds as? SummaryLoad.Ready)?.value.orEmpty()
    val aiFeeds = ready.count { it.enabled && it.mode == "AI" }
    TidiloScreenScaffold(
        hideHeaderOnScroll = false,
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.rss_title),
                onNavigateBack = onBack
            )
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (error != null) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissError) {
                                Text(
                                    stringResource(R.string.rss_dismiss),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
                // ==========================================
                // Section 1: Sync & Schedule (Preference Card)
                // ==========================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.rss_schedule),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    RssCard {
                        // 0. Daily fetch master toggle row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .alpha(if (isDailyFetchEnabled) 1f else 0.45f)
                                .toggleable(
                                    value = isDailyFetchEnabled,
                                    enabled = !busy,
                                    role = Role.Switch,
                                    onValueChange = { isDailyFetchEnabled = it }
                                )
                                .padding(vertical = 4.dp),
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
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.rss_daily_fetch),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.rss_daily_fetch_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            Switch(
                                checked = isDailyFetchEnabled,
                                onCheckedChange = null,
                                enabled = !busy
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        // 1. Daily fetch time row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy && isDailyFetchEnabled) { showTime = true }
                                .alpha(if (isDailyFetchEnabled) 1f else 0.45f)
                                .padding(vertical = 4.dp),
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
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.rss_schedule),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.rss_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                            // Time pill in primaryContainer (M3 Floating Action style)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shadowElevation = 1.dp,
                                modifier = Modifier.height(36.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%02d:%02d", settings.hour, settings.minute),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        // 2. Fetch only over Wi-Fi row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .alpha(if (isDailyFetchEnabled) 1f else 0.45f)
                                .toggleable(
                                    value = settings.wifiOnly,
                                    enabled = !busy && isDailyFetchEnabled,
                                    role = Role.Switch,
                                    onValueChange = { onSettings(settings.copy(wifiOnly = it)) }
                                )
                                .padding(vertical = 4.dp),
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
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = stringResource(R.string.rss_wifi_only),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Switch(
                                checked = settings.wifiOnly,
                                onCheckedChange = null,
                                enabled = !busy && isDailyFetchEnabled
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        // 3. Fetch now button (Full-width expressive tonal button)
                        FilledTonalButton(
                            onClick = onFetch,
                            enabled = !busy && ready.any { it.enabled },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.rss_fetch_now),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // AI Queue Info Callout
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp).padding(top = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.rss_max_queue, aiFeeds * 5),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (aiFeeds * 5 > 20) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.rss_quota_warning),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.rss_queue_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
                // ==========================================
                // Section 2: Subscriptions (Feed List)
                // ==========================================
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${stringResource(R.string.rss_title)} (${ready.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    when (feeds) {
                        SummaryLoad.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        SummaryLoad.Error -> {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.summary_error),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(onClick = onRetry) {
                                        Text(stringResource(R.string.summary_retry))
                                    }
                                }
                            }
                        }
                        is SummaryLoad.Ready -> {
                            if (ready.isEmpty()) {
                                RssEmptyStateCard()
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ready.forEach { feed ->
                                        key(feed.id) {
                                            RssFeedItemCard(
                                                feed = feed,
                                                busy = busy,
                                                onEdit = {
                                                    editing = feed
                                                    showEditor = true
                                                },
                                                onDelete = { deleting = feed },
                                                onToggleEnabled = { onEnabled(feed.id, it) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Floating Action Button (matching TidiloBottomBar action button specification)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 24.dp, bottom = 24.dp)
                    .size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 8.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = MaterialTheme.colorScheme.onPrimaryContainer, bounded = true),
                            enabled = !busy
                        ) {
                            editing = null
                            showEditor = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.rss_add_feed),
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
    if (showTime) {
        val time = rememberTimePickerState(settings.hour, settings.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.rss_schedule)) },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(onClick = {
                    onSettings(settings.copy(hour = time.hour, minute = time.minute))
                    showTime = false
                }) {
                    Text(stringResource(R.string.rss_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(R.string.rss_cancel))
                }
            }
        )
    }
    // Modal Bottom Sheet for Add / Edit Feed
    if (showEditor) {
        RssFeedBottomSheet(
            original = editing,
            onDismiss = { showEditor = false },
            onDelete = editing?.let { feed -> { deleting = feed } },
            onSave = { url, mode, limit, convert ->
                onFeed(editing, url, mode, limit, convert)
                showEditor = false
            }
        )
    }
    deleting?.let { feed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.rss_delete)) },
            text = { Text(stringResource(R.string.rss_delete_note)) },
            confirmButton = {
                TextButton(onClick = { onDelete(feed.id); deleting = null }) {
                    Text(stringResource(R.string.rss_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.rss_cancel))
                }
            }
        )
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RssFeedItemCard(
    feed: RssFeedEntity,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    val iconVisual by rememberFeedIcon(feed)
    val publisherDomain = remember(feed) { DomainIconManager.feedPublisherDomain(feed) ?: feed.url }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = !busy,
                onClick = onEdit,
                onLongClick = onDelete
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Upper row: Domain icon + Title (full width marquee) & Publisher domain
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Domain / Favicon Icon
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = iconVisual.color.copy(alpha = 0.15f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (iconVisual.bitmap != null) {
                            Image(
                                bitmap = iconVisual.bitmap!!,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RssFeed,
                                contentDescription = null,
                                tint = iconVisual.color,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                // Title + Domain: Takes full available horizontal width!
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = feed.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(
                            initialDelayMillis = 2000,
                            repeatDelayMillis = 2000
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = publisherDomain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Lower row: Metadata Pills (left) + Switch Toggle (bottom-right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Mode & Limit Pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAi = feed.mode == "AI"
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAi) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isAi) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = stringResource(if (isAi) R.string.rss_ai_mode else R.string.rss_direct_mode),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAi) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = feed.retentionLimit?.let { stringResource(R.string.rss_retention_compact, it) }
                                ?: stringResource(R.string.rss_unlimited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                // Right: Switch Toggle moved to bottom-right
                Switch(
                    checked = feed.enabled,
                    onCheckedChange = { onToggleEnabled(it) },
                    enabled = !busy
                )
            }
            // Error notice
            feed.error?.let { err ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = if (err == "XML_FETCH_FAILED") stringResource(R.string.rss_xml_fetch_failed) else err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (err == "XML_FETCH_FAILED" && !feed.enabled) {
                            Text(
                                text = stringResource(R.string.rss_enable_to_retry),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun RssEmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(56.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.rss_no_feeds),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.rss_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
/**
 * Modern Material 3 ModalBottomSheet for adding or editing an RSS subscription.
 * Replaces cramped modal dialog with spacious, organized bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssFeedBottomSheet(
    original: RssFeedEntity?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, String, Int?, Boolean) -> Unit
) {
    val aiAnalysisMode by me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.collectAsStateWithLifecycle()
    var url by remember(original) { mutableStateOf(original?.url.orEmpty()) }
    var mode by remember(original) { mutableStateOf(original?.mode ?: "DIRECT") }
    var unlimited by remember(original) { mutableStateOf(original != null && original.retentionLimit == null) }
    var limit by remember(original) { mutableStateOf((original?.retentionLimit ?: 30).toString()) }
    var convert by remember { mutableStateOf(false) }
    val validLimit = unlimited || limit.toIntOrNull()?.let { it in 10..500 } == true
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(if (original == null) R.string.rss_add_feed else R.string.rss_edit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.rss_cancel),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            // 1. Feed URL field
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.rss_feed_url)) },
                placeholder = { Text("https://example.com/rss.xml") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (original != null && url.trim() != original.url) {
                Text(
                    text = stringResource(R.string.rss_url_change_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            // 2. Import Mode selection (Pill style toggle cards)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.rss_import_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Direct mode chip
                    val isDirectSelected = mode == "DIRECT"
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { mode = "DIRECT" },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDirectSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (!isDirectSelected) androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        ) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = stringResource(R.string.rss_direct_mode),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isDirectSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isDirectSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // AI mode chip
                    val isAiSelected = mode == "AI"
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { mode = "AI" },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isAiSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (!isAiSelected) androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        ) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isAiSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.rss_ai_mode),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isAiSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAiSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (aiAnalysisMode == me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE) {
                    Text(
                        text = stringResource(R.string.rss_ai_disabled_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (original?.mode == "AI" && mode == "DIRECT") {
                    Text(
                        text = stringResource(R.string.rss_mode_change_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(convert, role = Role.Checkbox, onValueChange = { convert = it }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(convert, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.rss_convert_pending),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            // 3. Max saved items
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.rss_max_saved_items),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Unlimited toggle row
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(unlimited, role = Role.Switch, onValueChange = { unlimited = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.rss_unlimited),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(checked = unlimited, onCheckedChange = null)
                }
                if (!unlimited) {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = { limit = it },
                        label = { Text(stringResource(R.string.rss_retention_input)) },
                        isError = !validLimit,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = stringResource(R.string.rss_retention_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 4. Action Buttons (Delete on left if editing, Save on right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (original != null && onDelete != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onDelete()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.rss_delete),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Button(
                    onClick = {
                        onSave(url.trim(), mode, if (unlimited) null else limit.toInt(), convert)
                    },
                    enabled = url.isNotBlank() && validLimit,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.rss_save),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
@Composable
private fun RssCard(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp), content = content)
    }
}
