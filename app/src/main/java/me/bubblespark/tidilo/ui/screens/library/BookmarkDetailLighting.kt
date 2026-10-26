package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.OgImageManager

/** The bookmark key owns animation state so a new detail never inherits its predecessor's hue. */
@Composable
internal fun rememberDetailAccent(
    bookmarkId: String,
    domain: String?,
    imageKey: String?,
    bitmap: ImageBitmap?,
    showFallback: Boolean
): State<Color> = key(bookmarkId, domain) {
    val context = LocalContext.current
    val iconRevision by androidx.compose.runtime.remember(domain) { DomainIconManager.observeIconUpdates(domain) }
        .collectAsStateWithLifecycle(initialValue = 0L)
    val color by produceState(Color.Transparent, bitmap, imageKey, showFallback, iconRevision) {
        value = when {
            bitmap != null && domain != null -> OgImageManager.getDominantColor(domain, imageKey, bitmap) ?: Color.Transparent
            showFallback && domain != null -> withContext(Dispatchers.IO) {
                DomainIconManager.getDominantColor(context, domain)
            }
            else -> Color.Transparent
        }
    }
    animateColorAsState(color, tween(350, easing = EaseOutCubic), label = "detailAccent")
}

/** Bounds are measured in the same root coordinate space; scrolling only invalidates drawing. */
internal fun Modifier.detailAmbientLight(
    color: State<Color>,
    bannerBounds: State<Rect?>,
    hostOrigin: State<Offset>,
    dark: Boolean,
    showFallback: Boolean
): Modifier = drawBehind {
    val accent = color.value
    val banner = bannerBounds.value
    val center = if (banner != null) banner.center - hostOrigin.value else {
        if (!showFallback) return@drawBehind
        Offset(size.width, 0f)
    }
    val strength = if (showFallback) {
        if (dark) 0.28f else 0.13f
    } else {
        if (dark) 0.16f else 0.07f
    }
    val downwashStrength = if (showFallback) 0.65f else 0.40f
    drawRect(Brush.radialGradient(
        0f to accent.copy(alpha = accent.alpha * strength),
        0.55f to accent.copy(alpha = accent.alpha * strength * 0.45f),
        1f to Color.Transparent,
        center = center, radius = size.width * 1.05f
    ))
    drawRect(Brush.linearGradient(
        0f to accent.copy(alpha = accent.alpha * strength * downwashStrength),
        0.65f to accent.copy(alpha = accent.alpha * strength * 0.18f),
        1f to Color.Transparent,
        start = center, end = center + Offset(0f, size.width * 1.6f)
    ))
}

/** Paint bloom and shadow before image clipping; frame the emissive artwork with a uniform rim. */
internal fun Modifier.detailBannerLight(color: State<Color>): Modifier =
    drawBehind {
        val accent = color.value
        val spread = 24.dp.toPx()
        // Layered rounded contours keep the glow outside the entire perimeter, including corners.
        for (step in 12 downTo 1) {
            val inset = spread * step / 12f
            drawRoundRect(
                color = accent.copy(alpha = accent.alpha * 0.006f * (1f - step / 13f)),
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(16.dp.toPx() + inset)
            )
        }
    }
    .shadow(8.dp, RoundedCornerShape(16.dp), clip = false,
        ambientColor = color.value.copy(alpha = 0.16f), spotColor = color.value.copy(alpha = 0.24f))
    .border(1.dp, color.value.copy(alpha = color.value.alpha * 0.60f), RoundedCornerShape(16.dp))
