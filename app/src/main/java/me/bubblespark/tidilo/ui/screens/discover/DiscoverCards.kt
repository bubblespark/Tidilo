package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.util.TimeFormatter

/** Callbacks shared by every Discover card. Stable so shelves skip recomposition when only data changes. */
@Stable
class DiscoverActions(
    val onOpen: (id: String, shelf: DiscoverShelf) -> Unit,
    val onImpression: (id: String, shelf: DiscoverShelf) -> Unit,
    val onEntity: (value: String) -> Unit,
    val onSaveUrl: () -> Unit,
    val onRetryImport: (taskId: String) -> Unit,
    val onToggleDeckLike: (item: BookmarkItem) -> Unit,
    val onNextCard: () -> Unit,
    val onDealAnother: () -> Unit
)

/** Impression after ≥1 s at ≥50% visibility (§5.1). The repository keeps one per item per day. */
internal fun Modifier.discoverImpression(id: String, shelf: DiscoverShelf, actions: DiscoverActions): Modifier =
    onVisibilityChanged(minDurationMs = 1_000L, minFractionVisible = 0.5f) { visible ->
        if (visible) actions.onImpression(id, shelf)
    }

/** Tap target, impression tracking, and one merged announcement (title, reason, domain) for a card. */
@Composable
internal fun Modifier.discoverCard(
    item: BookmarkItem,
    shelf: DiscoverShelf,
    reason: String?,
    actions: DiscoverActions
): Modifier {
    val domain = rememberDomainLabel(item)
    val description = listOf(item.title, reason, domain).filterNot { it.isNullOrBlank() }.joinToString(", ")
    return this
        .testTag("discover_card_${item.id}")
        .discoverImpression(item.id, shelf, actions)
        .clickable { actions.onOpen(item.id, shelf) }
        .semantics(mergeDescendants = true) { contentDescription = description }
}

/** "domain · 3 days ago" byline. */
@Composable
internal fun Byline(item: BookmarkItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val domain = rememberDomainLabel(item)
    val time = androidx.compose.runtime.remember(item.createdAt, context) { TimeFormatter.formatRelativeTime(item.createdAt, context) }
    Text(
        text = listOf(domain, time).filter { it.isNotBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Reason overline above a plate: `labelMedium`, primary, filled icon (§8.2). */
@Composable
internal fun ReasonOverline(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Reason chip on a mat: `secondaryContainer`, `shape.small`. */
@Composable
internal fun ReasonChip(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** A carousel card: optional overline, the plate, then the caption below it (never on the art). */
@Composable
internal fun PlateCard(
    item: BookmarkItem,
    reason: DiscoverReason?,
    shelf: DiscoverShelf,
    width: Dp,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
    overline: String? = null,
    showReasonOverline: Boolean = false
) {
    val reasonLabel = reasonText(reason)
    Column(
        modifier.width(width).discoverCard(item, shelf, reasonLabel, actions),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        overline?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (showReasonOverline && reasonLabel != null) ReasonOverline(reasonLabel)
        DiscoverPlate(item, plateShape(width), Modifier.fillMaxWidth())
        Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Byline(item)
    }
}

/** Plates under 190dp use `medium`, larger ones `large` (§8.2). */
@Composable
internal fun plateShape(width: Dp) = if (width < 190.dp) MaterialTheme.shapes.medium else MaterialTheme.shapes.large

/** A list row: XS plate, title, and a trailing reason (Sleeping gems, Fresh from RSS). */
@Composable
internal fun PlateRow(
    item: BookmarkItem,
    reason: DiscoverReason?,
    shelf: DiscoverShelf,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
    reasonAsChip: Boolean = false
) {
    val reasonLabel = reasonText(reason)
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).discoverCard(item, shelf, reasonLabel, actions).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiscoverPlate(item, MaterialTheme.shapes.medium, Modifier.width(PlateSize.XS.width), compact = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (reasonLabel != null && reasonAsChip) ReasonChip(reasonLabel)
            else if (reasonLabel != null) Text(reasonLabel, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Byline(item)
        }
    }
}

/** An import still running (§4.5): a skeleton plate with an expressive loading indicator. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SavingCard(task: ImportTask, width: Dp, modifier: Modifier = Modifier) {
    val saving = stringResource(R.string.discover_saving)
    Column(modifier.width(width).testTag("discover_task_${task.id}").semantics(mergeDescendants = true) {
        contentDescription = "$saving ${taskLabel(task)}"
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(PLATE_RATIO).clip(plateShape(width))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            ContainedLoadingIndicator(Modifier.size(48.dp))
        }
        Text(taskLabel(task), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(saving, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A failed import with an inline Retry (§4.5). */
@Composable
internal fun FailedSaveCard(task: ImportTask, width: Dp, actions: DiscoverActions, modifier: Modifier = Modifier) {
    Column(modifier.width(width).testTag("discover_task_${task.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(PLATE_RATIO).clip(plateShape(width))
            .background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(taskLabel(task), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.discover_save_failed), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { actions.onRetryImport(task.id) }, modifier = Modifier.widthIn(min = 48.dp)) {
            Text(stringResource(R.string.discover_retry))
        }
    }
}

private fun taskLabel(task: ImportTask): String =
    task.title.takeIf { it.isNotBlank() } ?: DomainIconManager.extractDomain(task.url).orEmpty()
