package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.db.RssFeedEntity

internal data class FeedIconVisual(val bitmap: ImageBitmap?, val color: Color)

/** Completion updates do not cancel the shared request; eviction changes producer identity. */
@Composable
internal fun rememberFeedIcon(feed: RssFeedEntity): State<FeedIconVisual> {
    val context = LocalContext.current
    val generation by DomainIconManager.cacheGenerationState.collectAsStateWithLifecycle()
    val identity = DomainIconManager.feedIconKey(feed)
    val publisher = DomainIconManager.feedPublisherDomain(feed)
    fun cached(): FeedIconVisual {
        val direct = DomainIconManager.getCachedIconBitmap(identity)
        val bitmap = direct ?: publisher?.let(DomainIconManager::getCachedIconBitmap)
        val colorKey = if (direct != null || publisher == null) identity else publisher
        return FeedIconVisual(bitmap, DomainIconManager.getCachedDominantColor(colorKey)
            ?: DomainIconManager.generateDomainAccentColor(colorKey))
    }
    return key(context, identity, generation) {
        produceState(initialValue = cached()) {
            coroutineScope {
                launch {
                    merge(DomainIconManager.observeIconUpdates(identity), DomainIconManager.observeIconUpdates(publisher))
                        .collect { value = cached() }
                }
                withContext(Dispatchers.IO) { DomainIconManager.loadOrFetchFeedIcon(context, feed) }
                value = cached()
            }
        }
    }
}
