package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.data.DomainIconManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    alpha: Float = 0.14f
) {
    if (domain.isNullOrBlank()) return

    val context = LocalContext.current
    val iconRefreshSignal by remember(domain) {
        DomainIconManager.observeIconUpdates(domain)
    }.collectAsStateWithLifecycle(initialValue = "")

    val iconBitmap by produceState<ImageBitmap?>(
        initialValue = DomainIconManager.getCachedIconBitmap(domain),
        key1 = domain,
        key2 = iconRefreshSignal
    ) {
        value = DomainIconManager.getCachedIconBitmap(domain)
        val cached = DomainIconManager.getCachedIconBitmap(domain)
        if (cached != null) {
            value = cached
        } else {
            value = withContext(Dispatchers.IO) {
                DomainIconManager.loadIconFromDisk(context, domain)
            }
        }
    }

    val bitmap = iconBitmap ?: return

    val saturationFilter = remember(saturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp)),
        contentScale = ContentScale.Fit,
        alpha = alpha,
        colorFilter = saturationFilter
    )
}
