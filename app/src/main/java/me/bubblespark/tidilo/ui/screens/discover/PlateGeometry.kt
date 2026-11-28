package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** og:image design ratio (1200 × 630). Every Discover image surface keeps it (§2.6). */
const val PLATE_RATIO = 1.91f

/** Plate sizes from design spec §8.2 (phone, 412dp wide). [XL] is full bleed. */
enum class PlateSize(val width: Dp) {
    XL(412.dp),
    L(380.dp),
    L_POSTCARD(324.dp),
    M(280.dp),
    S(200.dp),
    S_PAIR(182.dp),
    XS(112.dp);

    val height: Dp get() = width / PLATE_RATIO
}

/** Width of a plate in a window of [availableWidth]: XL fills it, the rest keep their size within 16dp margins. */
internal fun plateWidth(size: PlateSize, availableWidth: Dp): Dp =
    if (size == PlateSize.XL) availableWidth else min(size.width.value, (availableWidth - 32.dp).value.coerceAtLeast(0f)).dp

/** Images within 5% of 1.91 fill the plate exactly; others are fitted on a mat and never cropped. */
internal fun fillsPlate(imageAspect: Float): Boolean = abs(imageAspect - PLATE_RATIO) / PLATE_RATIO <= 0.05f

/** Under this width a poster shows only the domain mark. */
internal val PosterInitialOnlyWidth = 150.dp

/** A domain icon is used on a poster tile only when its short side needs at most 2× upscaling (§8.2). */
internal fun posterIconSharp(iconWidthPx: Int, iconHeightPx: Int, tilePx: Float): Boolean =
    min(iconWidthPx, iconHeightPx) * 2 >= tilePx

/** Text treatment on a poster: [text] color and how much black to lay over the text region. */
internal data class PosterInk(val text: Color, val textRegionDarkening: Float)

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

/**
 * White or near-black text for a poster painted with [stops] (a gradient): whichever keeps the higher
 * contrast against its worst stop. When neither reaches [minContrast] everywhere, white is kept and the
 * text region is darkened until every stop does (§10).
 */
internal fun posterInk(stops: List<Color>, minContrast: Float = 4.5f): PosterInk {
    require(stops.isNotEmpty())
    val white = Color.White
    val ink = Color(0xFF111111)
    fun worst(text: Color, darkening: Float = 0f) =
        stops.minOf { contrastRatio(text, Color.Black.copy(alpha = darkening).compositeOver(it)) }
    val best = if (worst(white) >= worst(ink)) white else ink
    if (worst(best) >= minContrast) return PosterInk(best, 0f)
    var darkening = 0f
    while (darkening < 1f) {
        darkening = (darkening + 0.05f).coerceAtMost(1f)
        if (worst(white, darkening) >= minContrast) break
    }
    return PosterInk(white, darkening)
}

/** Poster layout variant (§8.2) from the bookmark id. */
internal fun posterVariant(id: String): Int = abs(id.hashCode() % 3)
