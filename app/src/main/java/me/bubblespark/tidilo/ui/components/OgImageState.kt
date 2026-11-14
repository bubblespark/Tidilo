package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DEFAULT_OG_ASPECT_RATIO = 1.91f

/**
 * Calculates the aspect ratio of an OG image safely, falling back to 1.91f if height is invalid.
 */
fun calculateOgAspectRatio(width: Int, height: Int): Float {
    return if (height > 0) width.toFloat() / height.toFloat() else DEFAULT_OG_ASPECT_RATIO
}

@Immutable
data class OgImageState(
    val bitmap: ImageBitmap?,
    val resolved: Boolean
) {
    /**
     * True when OG image resolution has completed and no image is available,
     * indicating that a fallback (e.g. domain watermark or domain icon) should be shown.
     */
    val shouldShowFallback: Boolean
        get() = bitmap == null && resolved

    /**
     * Explicit alias for [shouldShowFallback] in the context of domain watermark display.
     */
    val shouldShowDomainWatermarkFallback: Boolean
        get() = shouldShowFallback
}

/**
 * Determines the initial [OgImageState] before asynchronous loading begins.
 */
fun initialOgImageState(cached: ImageBitmap?, ogFilename: String?): OgImageState =
    OgImageState(bitmap = cached, resolved = cached != null || ogFilename == null)

/**
 * Creates an [OgImageState] for a completed load or update.
 */
fun resolvedOgImageState(fetched: ImageBitmap?): OgImageState =
    OgImageState(bitmap = fetched, resolved = true)

internal fun observeOgImageStateFlow(
    url: String,
    domain: String?,
    ogImageUrl: String?,
    ogFilename: String?,
    context: Context?,
    source: CacheVisualSource,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): Flow<OgImageState> = channelFlow {
    if (domain == null || ogFilename == null) {
        send(initialOgImageState(null, ogFilename))
        return@channelFlow
    }

    val updates = Channel<Unit>(Channel.CONFLATED)
    // Subscribe before the initial fetch so eviction cannot be missed during loading.
    launch(start = CoroutineStart.UNDISPATCHED) {
        source.observeExactImageUpdates(ogFilename).collect { updates.send(Unit) }
    }
    updates.trySend(Unit)
    updates.consumeAsFlow().collectLatest {
        val available = source.getCachedOgImageBitmap(domain, ogFilename)
        if (available != null) {
            send(resolvedOgImageState(available))
        } else {
            send(initialOgImageState(null, ogFilename))
            val fetched = withContext(ioDispatcher) {
                source.loadOrFetchOgImage(context, url, domain, ogImageUrl)
            }
            send(resolvedOgImageState(fetched))
        }
    }
}.distinctUntilChanged()

/**
 * Reactively loads and caches the og:image for a bookmark, coordinating in-memory hits,
 * background disk/network fetching, and exact image update notifications.
 */
@Composable
fun rememberOgImage(
    url: String,
    domain: String?,
    ogImageUrl: String?,
    ogFilename: String?,
    context: Context = LocalContext.current,
    source: CacheVisualSource = LocalCacheVisualSource.current
): State<OgImageState> {
    val cacheGeneration by source.cacheGenerationState.collectAsStateWithLifecycle()

    return key(context, url, domain, ogFilename, cacheGeneration, source) {
        val cached = remember(domain, ogFilename, cacheGeneration, source) {
            if (domain != null && ogFilename != null) source.getCachedOgImageBitmap(domain, ogFilename) else null
        }
        produceState(initialValue = initialOgImageState(cached, ogFilename)) {
            observeOgImageStateFlow(url, domain, ogImageUrl, ogFilename, context, source).collect { value = it }
        }
    }
}
