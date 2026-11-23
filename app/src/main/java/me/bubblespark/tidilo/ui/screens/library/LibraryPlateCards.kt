package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.discover.DiscoverPlate
import me.bubblespark.tidilo.ui.screens.discover.PlateSize
import me.bubblespark.tidilo.ui.screens.discover.rememberDomainLabel
import me.bubblespark.tidilo.ui.util.TimeFormatter

/** Minimum grid cell: two S plates side by side on a 412dp phone, more columns on wider windows. */
internal val LibraryPlateMinCellWidth = 160.dp

/** Tap opens, long-press peeks; one merged description of title, domain, time, and the RSS source. */
@Composable
private fun Modifier.libraryCard(
    item: BookmarkItem,
    tag: String,
    showRssSource: Boolean,
    domain: String,
    time: String,
    onOpen: (String) -> Unit,
    onPeek: (BookmarkItem) -> Unit
): Modifier {
    val haptic = LocalHapticFeedback.current
    val rss = stringResource(R.string.rss_title)
    val description = listOfNotNull(item.title, domain, time, rss.takeIf { showRssSource })
        .filter { it.isNotBlank() }.joinToString(", ")
    return this
        .testTag(tag)
        .combinedClickable(
            onClickLabel = stringResource(R.string.discover_open),
            onLongClickLabel = stringResource(R.string.discover_peek),
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPeek(item)
            },
            onClick = { onOpen(item.id) }
        )
        .semantics(mergeDescendants = true) { contentDescription = description }
}

@Composable
private fun rememberRelativeTime(item: BookmarkItem): String {
    val context = LocalContext.current
    return remember(item.createdAt, context) { TimeFormatter.formatRelativeTime(item.createdAt, context) }
}

/** Grid card (§9 Newest): an S plate, then domain, a 3-line title, and the relative time below it. */
@Composable
internal fun LibraryPlateCard(
    item: BookmarkItem,
    showRssSource: Boolean,
    onOpen: (String) -> Unit,
    onPeek: (BookmarkItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val domain = rememberDomainLabel(item)
    val time = rememberRelativeTime(item)
    Column(
        // No clip: a rounded clip would cut the caption's bottom-left glyph; the plate keeps its own shape.
        modifier.fillMaxWidth()
            .libraryCard(item, "library_card_${item.id}", showRssSource, domain, time, onOpen, onPeek),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        DiscoverPlate(item, MaterialTheme.shapes.medium, Modifier.fillMaxWidth().padding(bottom = 6.dp))
        Text(domain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        SourceTime(time, showRssSource)
    }
}

/** List row (§8.2 XS): a small plate, a 2-line title, and "domain · time". */
@Composable
internal fun LibraryPlateRow(
    item: BookmarkItem,
    showRssSource: Boolean,
    onOpen: (String) -> Unit,
    onPeek: (BookmarkItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val domain = rememberDomainLabel(item)
    val time = rememberRelativeTime(item)
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .libraryCard(item, "library_row_${item.id}", showRssSource, domain, time, onOpen, onPeek)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiscoverPlate(item, MaterialTheme.shapes.medium, Modifier.width(PlateSize.XS.width), compact = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceTime(listOf(domain, time).filter { it.isNotBlank() }.joinToString(" · "), showRssSource)
        }
    }
}

@Composable
private fun SourceTime(text: String, showRssSource: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showRssSource) {
            Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
