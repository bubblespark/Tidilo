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
)

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
        produceState(initialValue = OgImageState(cached, cached != null || ogFilename == null)) {
            if (domain != null && ogFilename != null) {
                coroutineScope {
                    launch {
                        OgImageManager.observeExactImageUpdates(ogFilename).collect {
                            OgImageManager.getCachedOgImageBitmap(domain, ogFilename)?.let { value = OgImageState(it, true) }
                        }
                    }
                    if (cached == null) {
                        value = OgImageState(OgImageManager.loadOrFetchOgImage(context, url, domain, ogImageUrl), true)
                    }
                }
            }
        }
    }
}
