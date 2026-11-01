package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.OgImageManager
import kotlin.math.ceil

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
    val initialColor = remember(bookmarkId, domain) {
        if (domain != null) {
            DomainIconManager.getCachedDominantColor(domain) ?: DomainIconManager.generateDomainAccentColor(domain)
        } else {
            Color.Transparent
        }
    }
    val color by produceState(initialColor, bitmap, imageKey, showFallback, iconRevision) {
        value = when {
            bitmap != null && domain != null -> OgImageManager.getDominantColor(domain, imageKey, bitmap) ?: initialColor
            showFallback && domain != null -> withContext(Dispatchers.IO) {
                DomainIconManager.getDominantColor(context, domain)
            }
            else -> initialColor
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
        center = center,
        radius = size.height * 0.95f
    ))
    drawRect(Brush.linearGradient(
        0f to accent.copy(alpha = accent.alpha * strength * downwashStrength),
        0.55f to Color.Transparent,
        start = center,
        end = Offset(center.x, size.height * 0.90f)
    ))
}

/**
 * Bounds are measured in the same coordinate space so the wash aligns perfectly with artwork.
 */
internal fun Modifier.detailBannerLight(
    color: State<Color>
): Modifier = drawWithCache {
    val cornerRadius = CornerRadius(16.dp.toPx())
    val strokeWidth = 1.dp.toPx()
    val halfStroke = strokeWidth / 2f
    val accent = color.value
    val glowColor = accent.copy(alpha = accent.alpha * 0.20f)
    val outlineColor = accent.copy(alpha = accent.alpha * 0.35f)
    val glowBrush = Brush.radialGradient(
        0f to glowColor,
        0.75f to glowColor.copy(alpha = glowColor.alpha * 0.35f),
        1f to Color.Transparent,
        center = Offset(size.width * 0.5f, size.height * 0.2f),
        radius = size.width * 0.85f
    )
    val edgeHighlight = Brush.linearGradient(
        0f to outlineColor,
        0.4f to outlineColor.copy(alpha = outlineColor.alpha * 0.3f),
        1f to Color.Transparent,
        start = Offset(0f, 0f),
        end = Offset(0f, size.height)
    )

    onDrawBehind {
        drawRoundRect(
            brush = glowBrush,
            cornerRadius = cornerRadius
        )
        drawRoundRect(
            brush = edgeHighlight,
            topLeft = Offset(halfStroke, halfStroke),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(cornerRadius.x - halfStroke, cornerRadius.y - halfStroke),
            style = Stroke(width = strokeWidth)
        )
    }
}
