package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.DomainIconVisual
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.components.OgImageState
import me.bubblespark.tidilo.ui.components.rememberDomainVisual
import me.bubblespark.tidilo.ui.components.rememberOgImage
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem

/** Domain shown on Discover: the display name, never a raw URL (§8.2). */
@Composable
internal fun rememberDomainLabel(item: BookmarkItem): String {
    val source = LocalCacheVisualSource.current
    return remember(item.domain, item.url, source) {
        val domain = item.domain ?: source.extractDomain(item.url)
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }
}

/**
 * A 1.91:1 plate (§8.2). An og:image of any ratio is scaled to fill it and center-cropped (Detail's hero
 * shows the whole image). Without an image it becomes a [DiscoverPoster].
 * Purely visual: semantics belong to the enclosing card.
 */
@Composable
internal fun DiscoverPlate(
    item: BookmarkItem,
    shape: Shape,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val visuals = rememberPlateVisuals(item)
    DiscoverPlateContent(item, visuals.og, visuals.domain, shape, modifier, compact)
}

/** The og:image and domain visuals of a bookmark, observed once and shared by a plate and its mats. */
internal class PlateVisuals(val og: OgImageState, val domain: DomainIconVisual)

@Composable
internal fun rememberPlateVisuals(item: BookmarkItem): PlateVisuals {
    val source = LocalCacheVisualSource.current
    val domain = remember(item.domain, item.url, source) { item.domain ?: source.extractDomain(item.url) }
    val ogState by rememberOgImage(item.url, domain, item.ogImageUrl, item.ogImageFilename, source = source)
    val domainVisual by rememberDomainVisual(domain, item.url, ogState.shouldShowFallback, source = source)
    return PlateVisuals(ogState, domainVisual)
}

/** Stateless plate body for callers that already observe the bookmark's visuals. [compact] posters show only the domain mark. */
@Composable
internal fun DiscoverPlateContent(
    item: BookmarkItem,
    ogState: OgImageState,
    domainVisual: DomainIconVisual,
    shape: Shape,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    val bitmap = ogState.bitmap
    ReportAmbientColor(item.id, domainVisual.accentColor)
    Box(
        modifier = modifier
            .aspectRatio(PLATE_RATIO)
            .clip(shape)
            .background(placeholder)
            .clearAndSetSemantics {}
    ) {
        when {
            bitmap != null ->
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            ogState.shouldShowFallback -> DiscoverPoster(item, domainVisual.accentColor, domainVisual.bitmap, compact, Modifier.fillMaxSize())
        }
    }
}

/**
 * Typographic poster for a bookmark without og:image (§8.2): the domain color as a gradient, white or ink
 * text at ≥4.5:1 against every gradient stop, and one of three layouts chosen by id. The domain [icon] stands
 * in for the initial on a white tile when it is sharp enough at the tile's size. [compact] posters (plates
 * under 150dp) show only that mark.
 */
@Composable
internal fun DiscoverPoster(item: BookmarkItem, accent: Color, icon: ImageBitmap?, compact: Boolean, modifier: Modifier = Modifier) {
    val base = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    val deep = Color.Black.copy(alpha = 0.35f).compositeOver(base)
    val ink = remember(base) { posterInk(listOf(base, deep)) }
    val label = rememberDomainLabel(item)
    val initial = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    val density = LocalDensity.current
    fun sharpIcon(size: Dp): ImageBitmap? =
        icon?.takeIf { posterIconSharp(it.width, it.height, with(density) { size.toPx() }) }
    val textRegion = Modifier.background(Color.Black.copy(alpha = ink.textRegionDarkening))
    // The plate is image-like: its text keeps its size at every font scale (the caption below repeats the
    // title at the user's scale), so a small plate never clips it.
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Box(modifier.background(Brush.linearGradient(listOf(base, deep)))) {
            if (compact) {
                val tile = sharpIcon(CompactTile)
                if (tile != null) {
                    PosterIconTile(tile, CompactTile, Modifier.align(Alignment.Center))
                    return@Box
                }
                // The whole small poster is the text region, so darken it evenly.
                Box(Modifier.fillMaxSize().then(textRegion))
                Text(initial, color = ink.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
                return@Box
            }
            when (posterVariant(item.id)) {
                0 -> {
                    val tile = sharpIcon(CornerTile)
                    if (tile != null) {
                        PosterIconTile(tile, CornerTile, Modifier.align(Alignment.BottomEnd).padding(CornerTileInset))
                        // Keep a 3-line title clear of the tile.
                        PosterTitle(item.title, ink.text, Modifier.align(Alignment.TopStart).padding(end = CornerTile + CornerTileInset)
                            .then(textRegion).padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 8.dp))
                    } else {
                        // The watermark fits its box, so it never grows with the font scale.
                        BasicText(
                            initial,
                            style = MaterialTheme.typography.displayLarge.copy(color = ink.text.copy(alpha = 0.16f), fontWeight = FontWeight.Black),
                            autoSize = TextAutoSize.StepBased(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp).fillMaxHeight(0.9f).aspectRatio(1f)
                        )
                        PosterTitle(item.title, ink.text, Modifier.align(Alignment.TopStart).then(textRegion).padding(14.dp))
                    }
                }
                1 -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.18f).background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center) {
                        val tile = sharpIcon(SpineTile)
                        if (tile != null) PosterIconTile(tile, SpineTile)
                        else Text(initial, color = ink.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    PosterTitle(item.title, ink.text, Modifier.weight(1f).then(textRegion).padding(14.dp))
                }
                else -> Column(Modifier.fillMaxSize().then(textRegion).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sharpIcon(MastheadTile)?.let { PosterIconTile(it, MastheadTile) }
                        Text(label.uppercase(), color = ink.text, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ink.text.copy(alpha = 0.6f)))
                    Spacer(Modifier.height(2.dp))
                    PosterTitle(item.title, ink.text, Modifier.weight(1f))
                }
            }
        }
    }
}

internal const val POSTER_ICON_TAG = "poster_domain_icon"

private val CompactTile = 32.dp
private val CornerTile = 36.dp
private val CornerTileInset = 10.dp
private val SpineTile = 24.dp
private val MastheadTile = 16.dp
private val PosterIconTileShape = RoundedCornerShape(percent = 25)

/** The domain icon on a white tile, so a transparent icon stays visible on a gradient made from its own color. */
@Composable
private fun PosterIconTile(icon: ImageBitmap, size: Dp, modifier: Modifier = Modifier) {
    Image(
        icon,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.testTag(POSTER_ICON_TAG).size(size).clip(PosterIconTileShape).background(Color.White).padding(size / 8)
    )
}

/** Up to three lines that shrink to the space the layout leaves, so the last line is never cut mid-glyph. */
@Composable
private fun PosterTitle(title: String, color: Color, modifier: Modifier) {
    val base = MaterialTheme.typography.titleMedium
    // Line height in em, so it shrinks with the auto-sized font.
    val style = base.copy(color = color, fontWeight = FontWeight.SemiBold, lineHeight = (base.lineHeight.value / base.fontSize.value).em)
    BasicText(title, modifier, style = style, maxLines = 3, overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize))
}
