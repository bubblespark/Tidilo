package me.bubblespark.tidilo.ui.screens.library

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.ui.components.IconWatermark
import me.bubblespark.tidilo.ui.components.rememberFeedIcon

/** Uses exactly the Domain deck presentation with feed-owned metadata and artwork. */
@Composable
internal fun RssFeedCard(
    group: RssFeedGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    val icon by rememberFeedIcon(group.feed, context = context)
    LibraryDeckCard(DomainGroupItem(DomainIconManager.feedPublisherDomain(group.feed).orEmpty(),
        group.feed.title, group.count), icon.color, onClick, modifier) { watermarkModifier ->
        IconWatermark(icon.bitmap, modifier = watermarkModifier)
    }
}
