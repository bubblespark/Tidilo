package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.OgImageManager

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

/**
 * Reactively loads and caches the og:image for a bookmark, coordinating in-memory hits,
 * background disk/network fetching, and exact image update notifications.
 */
@Composable
fun rememberOgImage(
    url: String,
    domain: String?,
    ogImageUrl: String?,
    ogFilename: String?
): State<OgImageState> {
    val context = LocalContext.current
    val cacheGeneration by OgImageManager.cacheGenerationState.collectAsStateWithLifecycle()

    return key(context, url, domain, ogFilename, cacheGeneration) {
        val cached = if (domain != null && ogFilename != null) OgImageManager.getCachedOgImageBitmap(domain, ogFilename) else null
        produceState(initialValue = initialOgImageState(cached, ogFilename)) {
            if (domain != null && ogFilename != null) {
                coroutineScope {
                    launch {
                        OgImageManager.observeExactImageUpdates(ogFilename).collect {
                            OgImageManager.getCachedOgImageBitmap(domain, ogFilename)?.let { value = resolvedOgImageState(it) }
                        }
                    }
                    if (cached == null) {
                        value = resolvedOgImageState(OgImageManager.loadOrFetchOgImage(context, url, domain, ogImageUrl))
                    }
                }
            }
        }
    }
}
