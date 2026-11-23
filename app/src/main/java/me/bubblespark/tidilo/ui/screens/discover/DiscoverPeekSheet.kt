package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem

/** The card a long-press opened, with its reason label as shown on the shelf. */
internal data class PeekTarget(val item: BookmarkItem, val reason: String?)

/**
 * Peek sheet (§6): the card's plate, title, domain, summary, and reason, with Like / Not now / Don't show
 * again / Add to collection. The house sheet blurs the backdrop, or falls back to the opaque surface.
 * Every action except Like closes the sheet first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverPeekSheet(
    target: PeekTarget,
    liked: Boolean,
    onToggleLike: () -> Unit,
    onSnooze: () -> Unit,
    onNeverAgain: () -> Unit,
    onAddToCollection: () -> Unit,
    onDismiss: () -> Unit
) {
    val item = target.item
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // One action per sheet: a second tap while hiding does nothing, and a hide cancelled by leaving
    // the screen runs no action.
    fun closeThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
            if (cause != null) return@invokeOnCompletion
            action()
            onDismiss()
        }
    }
    TidiloModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("discover_peek_sheet")) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DiscoverPlate(item, MaterialTheme.shapes.large, Modifier.fillMaxWidth())
            target.reason?.let { ReasonOverline(it) }
            Text(item.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Byline(item)
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggleLike, modifier = Modifier.testTag("discover_peek_like")) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text(stringResource(if (liked) R.string.discover_deck_unlike else R.string.discover_deck_like),
                        modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { closeThen(onSnooze) }, modifier = Modifier.testTag("discover_peek_snooze")) {
                    Text(stringResource(R.string.discover_peek_snooze))
                }
                OutlinedButton(onClick = { closeThen(onNeverAgain) }, modifier = Modifier.testTag("discover_peek_never")) {
                    Text(stringResource(R.string.discover_peek_never))
                }
                OutlinedButton(onClick = { closeThen(onAddToCollection) }, modifier = Modifier.testTag("discover_peek_collection")) {
                    Text(stringResource(R.string.discover_peek_add_to_collection))
                }
            }
        }
    }
}
