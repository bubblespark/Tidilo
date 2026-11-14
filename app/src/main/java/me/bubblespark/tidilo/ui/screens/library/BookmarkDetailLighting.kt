package me.bubblespark.tidilo.ui.screens.library

import android.graphics.Bitmap
import me.bubblespark.tidilo.ui.theme.BackgroundDark
import me.bubblespark.tidilo.ui.theme.BackgroundLight
import me.bubblespark.tidilo.ui.theme.ModernDarkColorScheme
import me.bubblespark.tidilo.ui.theme.ModernLightColorScheme
import me.bubblespark.tidilo.ui.theme.OnSurfaceDark
import me.bubblespark.tidilo.ui.theme.OnSurfaceLight
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.components.rememberDomainVisual
import kotlin.math.ceil

typealias DetailHeroPalette = me.bubblespark.tidilo.data.DetailHeroPalette

internal val DETAIL_HERO_MAX_BANNER_HEIGHT = 300.dp

/**
 * Formats the plain-text Classification Breadcrumb displayed above the Bookmark Title:
 * - Category + Subcategory + NSFW -> `"Category > Subcategory - NSFW"`
 * - Category + Subcategory -> `"Category > Subcategory"`
 * - Single Category or Subcategory (+ optional `" - NSFW"`)
 * - NSFW only -> `"NSFW"`
 * - None present -> `null` (omits the row with zero vertical gap)
 */
fun formatClassificationBreadcrumb(
    localizedCategory: String,
    localizedSubcategory: String,
    isNsfw: Boolean
): String? {
    val cat = localizedCategory.trim()
    val sub = localizedSubcategory.trim()
    val hierarchy = when {
        cat.isNotEmpty() && sub.isNotEmpty() -> "$cat > $sub"
        cat.isNotEmpty() -> cat
        sub.isNotEmpty() -> sub
        else -> ""
    }
    return when {
        hierarchy.isNotEmpty() && isNsfw -> "$hierarchy - NSFW"
        hierarchy.isNotEmpty() -> hierarchy
        isNsfw -> "NSFW"
        else -> null
    }
}

/**
 * Samples representative top-edge (~15%), bottom-edge (~15%), and overall dominant accent colors from [bitmap].
 */
internal fun extractDetailHeroPalette(bitmap: Bitmap, domain: String): DetailHeroPalette =
    me.bubblespark.tidilo.data.extractDetailHeroPalette(bitmap, domain)

/**
 * Pure pixel-array extractor for top ~15% edge band, bottom ~15% edge band, and dominant accent color.
 */
fun extractDetailHeroPaletteFromPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    domain: String
): DetailHeroPalette =
    me.bubblespark.tidilo.data.extractDetailHeroPaletteFromPixels(pixels, width, height, domain)

/**
 * Computes the WCAG 2.1 contrast ratio between [foreground] and [background].
 */
fun detailContrastRatio(foreground: Color, background: Color): Float {
    val fgLum = foreground.luminance()
    val bgLum = background.luminance()
    val lighter = maxOf(fgLum, bgLum)
    val darker = minOf(fgLum, bgLum)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Calibrates a sampled edge color so status bar icons (top edge) and metadata/title text (bottom edge)
 * achieve at least [minContrastRatio] against [foregroundColor] and its gradient composition over [backgroundColor],
 * while preserving the hue and seamless transition of the artwork.
 */
fun calibrateDetailEdgeColor(
    color: Color,
    isDark: Boolean,
    isTopEdge: Boolean,
    foregroundColor: Color = if (isDark) {
        if (isTopEdge) OnSurfaceDark else ModernDarkColorScheme.onSurfaceVariant
    } else {
        if (isTopEdge) OnSurfaceLight else ModernLightColorScheme.onSurfaceVariant
    },
    backgroundColor: Color = if (isDark) BackgroundDark else BackgroundLight,
    minContrastRatio: Float = 4.5f
): Color {
    if (color == Color.Transparent) return Color.Transparent
    val argb = ((color.alpha * 255).toInt() shl 24) or
        ((color.red * 255).toInt() shl 16) or
        ((color.green * 255).toInt() shl 8) or
        (color.blue * 255).toInt()
    val hsv = FloatArray(3)
    MediaColorPalette.colorToHsv(argb, hsv)
    val hue = hsv[0]
    var sat = hsv[1]
    var value = hsv[2]
    val fgLum = foregroundColor.luminance()

    if (isDark) {
        val maxVal = if (isTopEdge) 0.40f else 0.48f
        if (value > maxVal) {
            value = maxVal
        }
        val contrastLimit = ((fgLum + 0.05f) / minContrastRatio - 0.05f).coerceAtLeast(0.01f)
        val maxLum = minOf(if (isTopEdge) 0.12f else 0.08f, contrastLimit)
        var candidate = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        var step = 0
        while (
            (candidate.luminance() > maxLum ||
                detailContrastRatio(foregroundColor, candidate) < minContrastRatio ||
                detailContrastRatio(foregroundColor, candidate.copy(alpha = 0.45f).compositeOver(backgroundColor)) < minContrastRatio) &&
            step < 16
        ) {
            value *= 0.82f
            candidate = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
            step++
        }
        return candidate
    } else {
        if (isTopEdge) {
            val minVal = 0.60f
            if (value < minVal) value = minVal
        } else {
            val minVal = 0.72f
            if (value < minVal) {
                value = minVal
                sat *= 0.55f
            }
        }
        val contrastLimit = ((fgLum + 0.05f) * minContrastRatio - 0.05f).coerceAtMost(0.92f)
        val minLum = maxOf(if (isTopEdge) 0.35f else 0.48f, contrastLimit)
        var candidate = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        var step = 0
        while (
            (candidate.luminance() < minLum ||
                detailContrastRatio(foregroundColor, candidate) < minContrastRatio ||
                detailContrastRatio(foregroundColor, candidate.copy(alpha = 0.45f).compositeOver(backgroundColor)) < minContrastRatio) &&
            step < 16
        ) {
            value = (value + (1f - value) * 0.35f).coerceAtMost(1f)
            sat *= 0.75f
            candidate = Color.hsv(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
            step++
        }
        return candidate
    }
}

/** The bookmark key owns animation state so a new detail never inherits its predecessor's hue. */
@Composable
internal fun rememberDetailHeroPalette(
    bookmarkId: String,
    domain: String?,
    imageKey: String?,
    bitmap: ImageBitmap?,
    showFallback: Boolean
): State<DetailHeroPalette> = key(bookmarkId, domain) {
    val context = LocalContext.current
    val visualSource = LocalCacheVisualSource.current
    val surfaces = currentThemeSurfaces()
    val themeStyle = surfaces.preset
    val isDark = surfaces.isDark
    val colorScheme = MaterialTheme.colorScheme
    val fallbackVisual by rememberDomainVisual(
        domain = if (bitmap == null && showFallback) domain else null,
        allowNetwork = false,
        context = context,
        source = visualSource
    )
    val ogPalette by produceState(DetailHeroPalette.Transparent, bitmap, imageKey, domain, visualSource) {
        value = if (bitmap != null && domain != null) {
            visualSource.getDetailHeroPalette(domain, imageKey, bitmap) ?: DetailHeroPalette.Transparent
        } else {
            DetailHeroPalette.Transparent
        }
    }
    val rawPalette = when {
        bitmap != null && domain != null -> ogPalette
        showFallback && domain != null && fallbackVisual.accentColor != Color.Unspecified -> DetailHeroPalette(
            topEdgeColor = Color.Transparent,
            bottomEdgeColor = Color.Transparent,
            accentColor = fallbackVisual.accentColor
        )
        else -> DetailHeroPalette.Transparent
    }
    val targetAccent = if (rawPalette.accentColor != Color.Transparent) {
        themeStyle.resolveColor(colorScheme, rawPalette.accentColor)
    } else {
        Color.Transparent
    }
    val targetTop = if (rawPalette.topEdgeColor != Color.Transparent) {
        calibrateDetailEdgeColor(
            color = rawPalette.topEdgeColor,
            isDark = isDark,
            isTopEdge = true,
            foregroundColor = colorScheme.onSurface,
            backgroundColor = surfaces.canvasColor
        )
    } else {
        Color.Transparent
    }
    val targetBottom = if (rawPalette.bottomEdgeColor != Color.Transparent) {
        calibrateDetailEdgeColor(
            color = rawPalette.bottomEdgeColor,
            isDark = isDark,
            isTopEdge = false,
            foregroundColor = colorScheme.onSurfaceVariant,
            backgroundColor = surfaces.canvasColor
        )
    } else {
        Color.Transparent
    }
    val animatedAccent by animateColorAsState(targetAccent, tween(350, easing = EaseOutCubic), label = "detailAccent")
    val animatedTop by animateColorAsState(targetTop, tween(350, easing = EaseOutCubic), label = "detailTopEdge")
    val animatedBottom by animateColorAsState(targetBottom, tween(350, easing = EaseOutCubic), label = "detailBottomEdge")
    rememberUpdatedState(
        DetailHeroPalette(
            topEdgeColor = animatedTop,
            bottomEdgeColor = animatedBottom,
            accentColor = animatedAccent
        )
    )
}

/** The bookmark key owns animation state so a new detail never inherits its predecessor's hue. */
@Composable
internal fun rememberDetailAccent(
    bookmarkId: String,
    domain: String?,
    imageKey: String?,
    bitmap: ImageBitmap?,
    showFallback: Boolean
): State<Color> {
    val palette by rememberDetailHeroPalette(bookmarkId, domain, imageKey, bitmap, showFallback)
    return rememberUpdatedState(palette.accentColor)
}

/**
 * Computes the center coordinates of the detail ambient light in the local draw space.
 * Matches layout bounds mathematically without requiring per-frame onGloballyPositioned callbacks.
 */
internal fun calculateAmbientLightCenter(
    bannerAspectRatio: Float?,
    statusBarTopPx: Float,
    containerWidth: Float,
    scrollY: Float,
    maxBannerHeightPx: Float
): Offset {
    return if (bannerAspectRatio != null) {
        val bannerHeight = minOf(containerWidth / bannerAspectRatio, maxBannerHeightPx)
        Offset(containerWidth / 2f, statusBarTopPx + bannerHeight / 2f - scrollY)
    } else {
        Offset(containerWidth, 0f - scrollY)
    }
}

/** Draws ambient light gradients matching hero banner position during scroll in Draw phase. */
internal fun Modifier.detailAmbientLight(
    color: State<Color>,
    dark: Boolean,
    showFallback: Boolean,
    scrollYProvider: () -> Int,
    bannerAspectRatio: Float?,
    statusBarTopPadding: Dp
): Modifier = drawBehind {
    if (bannerAspectRatio == null && !showFallback) return@drawBehind

    val accent = color.value
    val scrollY = scrollYProvider().toFloat()
    val center = calculateAmbientLightCenter(
        bannerAspectRatio = bannerAspectRatio,
        statusBarTopPx = statusBarTopPadding.toPx(),
        containerWidth = size.width,
        scrollY = scrollY,
        maxBannerHeightPx = DETAIL_HERO_MAX_BANNER_HEIGHT.toPx()
    )
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
    .graphicsLayer {
        val accent = color.value
        shadowElevation = 8.dp.toPx()
        shape = RoundedCornerShape(16.dp)
        clip = false
        ambientShadowColor = accent.copy(alpha = 0.16f)
        spotShadowColor = accent.copy(alpha = 0.24f)
    }
    .drawWithCache {
        // Match Modifier.border's pixel-rounded width and keep the entire stroke inside the image.
        val strokeWidth = ceil(1.dp.toPx())
        val halfStroke = strokeWidth / 2f
        val stroke = Stroke(strokeWidth)
        val radius = CornerRadius((16.dp.toPx() - halfStroke).coerceAtLeast(0f))
        onDrawWithContent {
            drawContent()
            val accent = color.value
            drawRoundRect(
                color = accent.copy(alpha = accent.alpha * 0.60f),
                topLeft = Offset(halfStroke, halfStroke),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = radius,
                style = stroke
            )
        }
    }
