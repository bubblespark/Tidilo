package me.bubblespark.tidilo.ui.screens.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.screenActionInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import java.time.ZoneId

@Composable
fun NotificationScreen(viewModel: ImportViewModel, onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit, modifier: Modifier = Modifier) {
    val history by viewModel.notifications.collectAsStateWithLifecycle()
    val errors by viewModel.errorTasks.collectAsStateWithLifecycle()
    val feeds by viewModel.notificationFeeds.collectAsStateWithLifecycle()
    val busy by viewModel.notificationBusyKeys.collectAsStateWithLifecycle()
    val actionError by viewModel.notificationActionError.collectAsStateWithLifecycle()
    val unavailableMessage = stringResource(R.string.notification_bookmark_unavailable)
    val sections = remember(history, errors, feeds) { buildNotificationSections(history, errors, feeds) }
    LaunchedEffect(Unit) { viewModel.markAllNotificationsAsRead() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actionError) { actionError?.let { snackbar.showSnackbar(it); viewModel.dismissNotificationActionError() } }
    Box(modifier.fillMaxSize()) {
        NotificationContent(sections, busy, onNavigateBack, viewModel::clearAllNotifications,
            viewModel::retryNotification, viewModel::deleteNotification, { row ->
                val notification = row.notification
                viewModel.resolveNotificationBookmark(notification.url, notification.existingBookmarkId,
                    unavailableMessage, onNavigateToDetail)
            })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).screenActionInsets())
    }
}

@Composable
fun NotificationContent(sections: NotificationSections, busyKeys: Set<String>, onBack: () -> Unit,
    onClearHistory: () -> Unit, onRetry: (NotificationListItem) -> Unit, onDelete: (NotificationListItem) -> Unit,
    onOpenSavedPage: (NotificationListItem.History) -> Unit) {
    LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), header = {
        TidiloPageHeader(stringResource(R.string.settings_notification_title), onBack, actions = {
            TextButton(onClick = onClearHistory, enabled = sections.history.isNotEmpty()) {
                Text(stringResource(R.string.notification_clear_history), maxLines = 1, softWrap = false)
            }
        })
    }, bottomContentPadding = 24.dp) { padding ->
        if (sections.isEmpty) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.notification_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (sections.needsAttention.isNotEmpty()) {
                item(key = "section:attention", contentType = "section") {
                    NotificationSectionHeader(stringResource(R.string.notification_section_attention), topPadding = 8.dp,
                        description = stringResource(R.string.notification_section_attention_description))
                }
                items(sections.needsAttention, key = { it.key }, contentType = { "failure" }) { row ->
                    NeedsAttentionRow(row, row.key in busyKeys, zone, onRetry, onDelete)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (sections.history.isNotEmpty()) {
                item(key = "section:history", contentType = "section") {
                    NotificationSectionHeader(stringResource(R.string.notification_section_history),
                        topPadding = if (sections.needsAttention.isEmpty()) 8.dp else 24.dp)
                }
                items(sections.history, key = { it.key }, contentType = { "history" }) { row ->
                    HistoryRow(row, zone, onOpenSavedPage)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
