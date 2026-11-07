package me.bubblespark.tidilo.ui.screens.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
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
    val rows = remember(history, errors, feeds) { buildNotificationList(history, errors, feeds) }
    LaunchedEffect(Unit) { viewModel.markAllNotificationsAsRead() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actionError) { actionError?.let { snackbar.showSnackbar(it); viewModel.dismissNotificationActionError() } }
    Box(modifier.fillMaxSize()) {
        NotificationContent(rows, history.isNotEmpty(), busy, onNavigateBack, viewModel::clearAllNotifications,
            viewModel::retryNotification, viewModel::deleteNotification, { row ->
                val notification = row.notification
                viewModel.findBookmarkIdByUrl(notification.url) { id -> id?.let(onNavigateToDetail) }
            })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
fun NotificationContent(items: List<NotificationListItem>, canClear: Boolean, busyKeys: Set<String>,
    onBack: () -> Unit, onClear: () -> Unit, onRetry: (NotificationListItem) -> Unit,
    onDelete: (NotificationListItem) -> Unit, onOpen: (NotificationListItem.History) -> Unit) {
    LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    TidiloScreenScaffold(header = {
        TidiloPageHeader(stringResource(R.string.settings_notification_title), onBack, actions = {
            TextButton(onClick = onClear, enabled = canClear) { Text(stringResource(R.string.common_clear)) }
        })
    }, bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp) { padding ->
        if (items.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.notification_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(items, key = { it.key }) { row ->
                val failure = row !is NotificationListItem.History
                val title = when (row) {
                    is NotificationListItem.History -> row.notification.domain
                    is NotificationListItem.Failure -> row.task.title
                    is NotificationListItem.RssXmlFailure -> row.feed.title
                }
                val detail = when (row) {
                    is NotificationListItem.History -> row.notification.message
                    is NotificationListItem.Failure -> row.task.errorMessage ?: row.task.statusText
                    is NotificationListItem.RssXmlFailure -> row.feed.error.orEmpty()
                }
                val url = when (row) {
                    is NotificationListItem.History -> row.notification.url
                    is NotificationListItem.Failure -> row.task.url
                    is NotificationListItem.RssXmlFailure -> row.feed.url
                }
                var menuOpen by remember(row) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().then(if (row is NotificationListItem.History)
                    Modifier.clickable { onOpen(row) } else Modifier).padding(vertical = 16.dp),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodyMedium,
                            color = if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatNotificationTimestamp(row.createdAt, zone), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (failure) Box {
                        IconButton(onClick = { menuOpen = true }, enabled = row.key !in busyKeys,
                            modifier = Modifier.testTag("notification_actions:${row.key}")) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.notification_more_actions))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_retry)) },
                                enabled = row.key !in busyKeys, onClick = { menuOpen = false; onRetry(row) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                                enabled = row.key !in busyKeys, onClick = { menuOpen = false; onDelete(row) })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
