package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Unified domain artwork and calibrated accent color resolved from a single subscription. */
@Immutable
data class DomainIconVisual(
    val bitmap: ImageBitmap?,
    val accentColor: Color
)

internal fun resolveInitialDomainVisual(
    domain: String?,
    source: CacheVisualSource
): DomainIconVisual {
    if (domain.isNullOrBlank()) {
        return DomainIconVisual(bitmap = null, accentColor = Color.Unspecified)
    }
    return DomainIconVisual(
        bitmap = source.getCachedIconBitmap(domain),
        accentColor = source.resolveInstantDomainAccentColor(domain)
    )
}

internal fun observeDomainVisualFlow(
    domain: String?,
    pageUrl: String?,
    allowNetwork: Boolean,
    context: Context?,
    source: CacheVisualSource,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): Flow<DomainIconVisual> = channelFlow {
    if (domain.isNullOrBlank()) return@channelFlow

    val initialBitmap = source.getCachedIconBitmap(domain)
    val initialColor = source.getCachedDominantColor(domain)
    val stateMutex = Mutex()
    var initialResolved = initialBitmap != null && initialColor != null
    var observerDelivered = false
    var retainedBitmap: ImageBitmap? = initialBitmap

    launch(start = CoroutineStart.UNDISPATCHED) {
        source.observeIconUpdates(domain).collect {
            stateMutex.withLock {
                val cachedBitmap = source.getCachedIconBitmap(domain)
                val cachedColor = source.getCachedDominantColor(domain)
                if (!initialResolved) {
                    if (cachedBitmap != null && cachedColor != null) {
                        initialResolved = true
                        observerDelivered = true
                        retainedBitmap = cachedBitmap
                        send(DomainIconVisual(cachedBitmap, cachedColor))
                    }
                    return@withLock
                }
                val updatedBitmap = cachedBitmap
                    ?: withContext(ioDispatcher) { source.loadIconFromDisk(context, domain) }
                    ?: retainedBitmap
                val updatedColor = cachedColor
                    ?: withContext(ioDispatcher) { source.getDominantColor(context, domain) }
                observerDelivered = true
                retainedBitmap = updatedBitmap
                send(DomainIconVisual(updatedBitmap, updatedColor))
            }
        }
    }

    if (initialBitmap != null && initialColor != null) {
        stateMutex.withLock {
            if (!observerDelivered) {
                send(DomainIconVisual(initialBitmap, initialColor))
            }
        }
    } else {
        val bitmap = initialBitmap ?: withContext(ioDispatcher) {
            if (allowNetwork) source.loadOrFetchIcon(context, domain, pageUrl)
            else source.loadIconFromDisk(context, domain)
        }
        val color = source.getCachedDominantColor(domain) ?: withContext(ioDispatcher) {
            source.getDominantColor(context, domain)
        }
        stateMutex.withLock {
            if (observerDelivered) return@withLock
            val latestBitmap = source.getCachedIconBitmap(domain) ?: bitmap
            val latestColor = source.getCachedDominantColor(domain) ?: if (latestBitmap != null && latestBitmap !== bitmap) {
                withContext(ioDispatcher) { source.getDominantColor(context, domain) }
            } else {
                color
            }
            retainedBitmap = latestBitmap
            initialResolved = true
            send(DomainIconVisual(latestBitmap, latestColor))
        }
    }
}.distinctUntilChanged()

/** Keeps completion updates and accent color resolution under a single subscription per domain. */
@Composable
fun rememberDomainVisual(
    domain: String?,
    pageUrl: String? = null,
    allowNetwork: Boolean = true,
    context: Context = LocalContext.current,
    source: CacheVisualSource = LocalCacheVisualSource.current
): State<DomainIconVisual> {
    val generation by source.cacheGenerationState.collectAsStateWithLifecycle()
    return key(context, domain, pageUrl, generation, allowNetwork, source) {
        val initial = remember(domain, generation, source) {
            resolveInitialDomainVisual(domain, source)
        }
        produceState(initialValue = initial) {
            observeDomainVisualFlow(domain, pageUrl, allowNetwork, context, source).collect { value = it }
        }
    }
}

/** Compatibility wrapper returning only the domain icon bitmap from [rememberDomainVisual]. */
@Composable
internal fun rememberDomainIcon(
    domain: String?,
    pageUrl: String? = null,
    allowNetwork: Boolean = true,
    context: Context = LocalContext.current
): State<ImageBitmap?> {
    val visualState = rememberDomainVisual(domain, pageUrl, allowNetwork, context)
    return remember(visualState) { derivedStateOf { visualState.value.bitmap } }
}
