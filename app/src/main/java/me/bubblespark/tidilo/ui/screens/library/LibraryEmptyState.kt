package me.bubblespark.tidilo.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R

/** One per-tab empty state of the Library layer (§9, hand-over items 2–5). */
internal enum class LibraryEmpty(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
    val actionIcon: ImageVector
) {
    NEWEST(Icons.Default.Bookmark, R.string.library_empty_title, R.string.library_empty_hint, R.string.library_empty_save_url, Icons.Default.Add),
    DOMAIN(Icons.Default.Language, R.string.library_empty_domain_title, R.string.library_empty_domain_body, R.string.library_empty_save_url, Icons.Default.Add),
    RSS(Icons.Default.RssFeed, R.string.library_empty_rss_title, R.string.library_empty_rss_body, R.string.rss_add_feed, Icons.Default.Add),
    RSS_IDLE(Icons.Default.RssFeed, R.string.library_empty_rss_idle_title, R.string.library_empty_rss_idle_body, R.string.library_open_rss_settings, Icons.Default.Settings),
    COLLECTION(Icons.Default.CollectionsBookmark, R.string.library_empty_collection_title, R.string.library_empty_collection_body, R.string.collection_new_action, Icons.Default.Add)
}

/** RSS is empty either because nothing is subscribed or because no subscribed article is visible (P5-Q2). */
internal fun rssEmpty(subscriptionCount: Int?): LibraryEmpty = if ((subscriptionCount ?: 0) > 0) LibraryEmpty.RSS_IDLE else LibraryEmpty.RSS

@Composable
internal fun LibraryEmptyState(
    empty: LibraryEmpty,
    onAction: () -> Unit,
    contentPadding: PaddingValues,
    leadingContent: @Composable () -> Unit = {}
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
        leadingContent()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp).testTag("library_empty_${empty.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(empty.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text(stringResource(empty.title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(empty.body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
            Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("library_empty_action")) {
                Icon(empty.actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(empty.action))
            }
        }
    }
}
