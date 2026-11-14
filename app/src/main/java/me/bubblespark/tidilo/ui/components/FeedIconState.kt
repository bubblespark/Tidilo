package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.RssFeedEntity
import java.util.concurrent.atomic.AtomicBoolean

@Immutable
internal data class FeedIconVisual(val bitmap: ImageBitmap?, val color: Color)

internal fun resolveInitialFeedVisual(
    feed: RssFeedEntity,
    source: CacheVisualSource
): FeedIconVisual = resolveCachedFeedVisual(
    identity = source.feedIconKey(feed),
    publisher = source.feedPublisherDomain(feed),
    source = source
)

private fun resolveCachedFeedVisual(
    identity: String,
    publisher: String?,
    source: CacheVisualSource
): FeedIconVisual {
    val direct = source.getCachedIconBitmap(identity)
    val bitmap = direct ?: publisher?.let(source::getCachedIconBitmap)
    val colorKey = if (direct != null || publisher == null) identity else publisher
    return FeedIconVisual(bitmap, source.resolveInstantDomainAccentColor(colorKey))
}

internal fun observeFeedVisualFlow(
    feed: RssFeedEntity,
    context: Context?,
    source: CacheVisualSource,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): Flow<FeedIconVisual> = channelFlow {
    val identity = source.feedIconKey(feed)
    val publisher = source.feedPublisherDomain(feed)
    val initialDirect = source.getCachedIconBitmap(identity)
    val initialDirectColor = source.getCachedDominantColor(identity)
    val initialPublisherBitmap = publisher?.let(source::getCachedIconBitmap)
    val initialPublisherColor = publisher?.let(source::getCachedDominantColor)
    val fastPathHit = (initialDirect != null && initialDirectColor != null) ||
        (feed.iconUrl.isNullOrBlank() && initialPublisherBitmap != null && initialPublisherColor != null)
    val initialResolved = AtomicBoolean(fastPathHit)
    val baseline = resolveCachedFeedVisual(identity, publisher, source)

    launch(start = CoroutineStart.UNDISPATCHED) {
        merge(
            source.observeIconUpdates(identity),
            source.observeIconUpdates(publisher)
        ).collect {
            val updated = resolveCachedFeedVisual(identity, publisher, source)
            if (initialResolved.get() || (updated.bitmap != null && updated != baseline)) {
                send(updated)
            }
        }
    }

    if (fastPathHit) {
        send(baseline)
    } else {
        withContext(ioDispatcher) { source.loadOrFetchFeedIcon(context, feed) }
        initialResolved.set(true)
        send(resolveCachedFeedVisual(identity, publisher, source))
    }
}.distinctUntilChanged()

/** Completion updates do not cancel the shared request; eviction changes producer identity. */
@Composable
internal fun rememberFeedVisual(
    feed: RssFeedEntity,
    context: Context = LocalContext.current,
    source: CacheVisualSource = LocalCacheVisualSource.current
): State<FeedIconVisual> {
    val generation by source.cacheGenerationState.collectAsStateWithLifecycle()
    val identity = remember(feed, source) { source.feedIconKey(feed) }
    return key(context, identity, generation, source) {
        val initial = remember(feed, generation, source) {
            resolveInitialFeedVisual(feed, source)
        }
        produceState(initialValue = initial) {
            observeFeedVisualFlow(feed, context, source).collect { value = it }
        }
    }
}

/** Compatibility alias for [rememberFeedVisual]. */
@Composable
internal fun rememberFeedIcon(
    feed: RssFeedEntity,
    context: Context = LocalContext.current
): State<FeedIconVisual> = rememberFeedVisual(feed, context)
