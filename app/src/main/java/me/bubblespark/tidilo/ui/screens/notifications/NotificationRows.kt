package me.bubblespark.tidilo.ui.screens.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu

@Composable
internal fun NotificationSectionHeader(title: String, topPadding: Dp, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding, bottom = 4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (description != null) {
            Spacer(Modifier.size(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun NeedsAttentionRow(row: NotificationListItem.NeedsAttention, busy: Boolean, zone: ZoneId,
    onRetry: (NotificationListItem) -> Unit, onDelete: (NotificationListItem) -> Unit) {
    val status = stringResource(when (row) {
        is NotificationListItem.Failure -> R.string.notification_status_import_failed
        is NotificationListItem.RssXmlFailure -> R.string.notification_status_feed_disabled
    })
    val title = when (row) {
        is NotificationListItem.Failure -> row.task.title
        is NotificationListItem.RssXmlFailure -> row.feed.title
    }
    val url = when (row) {
        is NotificationListItem.Failure -> row.task.url
        is NotificationListItem.RssXmlFailure -> row.feed.url
    }
    val diagnostic = row.diagnostic
    val reason = remember(diagnostic) { classifyFailure(diagnostic) }
    var menuOpen by remember(row) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(reason.explanationRes), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        if (row is NotificationListItem.RssXmlFailure) {
            Text(stringResource(R.string.notification_feed_retry_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        if (diagnostic.isNotBlank()) FailureDiagnostic(row.key, title, diagnostic)
        NotificationMetadata(url, row.createdAt, zone)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RetryButton(title, busy) { onRetry(row) }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy,
                    modifier = Modifier.testTag("notification_actions:${row.key}")) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.notification_more_actions_item, title))
                }
                TidiloDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                        enabled = !busy, onClick = { menuOpen = false; onDelete(row) })
                }
            }
        }
    }
}

@Composable
private fun RetryButton(itemTitle: String, busy: Boolean, onClick: () -> Unit) {
    val retrying = stringResource(R.string.notification_retrying)
    val label = stringResource(R.string.notification_retry_item, itemTitle)
    FilledTonalButton(onClick = onClick, enabled = !busy,
        modifier = Modifier.semantics {
            contentDescription = label
            if (busy) stateDescription = retrying
        }) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp).clearAndSetSemantics {}, color = LocalContentColor.current,
                strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.common_retry), maxLines = 1)
    }
}

/** Bounded preview of the original diagnostic; the full text stays available through the details toggle. */
@Composable
private fun FailureDiagnostic(key: String, itemTitle: String, text: String) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.error
    if (expanded) {
        SelectionContainer { Text(text, style = style, color = color) }
    } else {
        Text(text, style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflows = it.hasVisualOverflow })
    }
    // A restored expanded state has no collapsed measurement yet, so keep the toggle while expanded.
    if (expanded || overflows) {
        val state = stringResource(
            if (expanded) R.string.notification_details_expanded else R.string.notification_details_collapsed)
        val label = stringResource(
            if (expanded) R.string.notification_details_hide_item else R.string.notification_details_show_item, itemTitle)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = state
        }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (expanded) R.string.notification_details_hide else R.string.notification_details_show))
        }
    }
}

@Composable
internal fun HistoryRow(row: NotificationListItem.History, zone: ZoneId,
    onOpenSavedPage: (NotificationListItem.History) -> Unit) {
    val notification = row.notification
    val kind = remember(notification.message) { historyKind(notification.message) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(notification.domain, style = MaterialTheme.typography.titleSmall)
        Text(kind.messageRes?.let { stringResource(it) } ?: notification.message,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        NotificationMetadata(notification.url, row.createdAt, zone)
        if (kind.opensSavedPage) {
            TextButton(onClick = { onOpenSavedPage(row) }) { Text(stringResource(R.string.import_open_saved_page)) }
        }
    }
}

@Composable
private fun NotificationMetadata(url: String, createdAt: Long, zone: ZoneId) {
    Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(formatNotificationTimestamp(createdAt, zone), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
