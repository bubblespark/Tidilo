package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner

/**
 * Reusable domain icon watermark component displayed in backgrounds (e.g. BookmarkItemCard).
 *
 * - Size: 195dp (default)
 * - Saturation: 50% (default, setToSaturation(0.50f))
 * - Alpha: 0.14f (default)
 * - Corner radius: 20dp
 */
@Composable
fun DomainWatermark(
    domain: String?,
    modifier: Modifier = Modifier,
    size: Dp = 195.dp,
    saturation: Float = 0.50f,
    alpha: Float = 0.14f,
    pageUrl: String? = null,
    context: Context = LocalContext.current
) {
    if (domain.isNullOrBlank()) return

    val visualSource = LocalCacheVisualSource.current
    val domainVisual by rememberDomainVisual(domain, pageUrl, context = context, source = visualSource)

    DomainWatermark(domainVisual, modifier, size, saturation, alpha)
}

/** Renders a domain watermark from an already-hoisted [DomainIconVisual] to avoid duplicate cache subscriptions. */
@Composable
fun DomainWatermark(
    visual: DomainIconVisual,
    modifier: Modifier = Modifier,
    size: Dp = 195.dp,
    saturation: Float = 0.50f,
    alpha: Float = 0.14f
) {
    IconWatermark(visual.bitmap, modifier, size, saturation, alpha)
}

/** Same image treatment for domain and feed-specific watermarks. */
@Composable
internal fun IconWatermark(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    saturation: Float = 0.50f,
    alpha: Float = 0.20f
) {
    if (bitmap == null) return

    val saturationFilter = remember(saturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(themeCorner(ThemeCorner.CARD)),
        contentScale = ContentScale.Fit,
        alpha = alpha,
        colorFilter = saturationFilter
    )
}
