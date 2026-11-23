package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
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
 * A 1.91:1 plate (§8.2). An og:image near 1.91:1 fills it exactly; any other ratio is fitted on a mat of
 * [matColor] (or the domain tone) and never cropped. Without an image it becomes a [DiscoverPoster].
 * Purely visual: semantics belong to the enclosing card.
 */
@Composable
internal fun DiscoverPlate(
    item: BookmarkItem,
    shape: Shape,
    modifier: Modifier = Modifier,
    matColor: Color = Color.Unspecified
) {
    val source = LocalCacheVisualSource.current
    val domain = remember(item.domain, item.url, source) { item.domain ?: source.extractDomain(item.url) }
    val ogState by rememberOgImage(item.url, domain, item.ogImageUrl, item.ogImageFilename, source = source)
    val domainVisual by rememberDomainVisual(domain, item.url, ogState.shouldShowFallback, source = source)
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    val bitmap = ogState.bitmap
    Box(
        modifier = modifier
            .aspectRatio(PLATE_RATIO)
            .clip(shape)
            .background(placeholder)
            .clearAndSetSemantics {}
    ) {
        when {
            bitmap != null -> {
                val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                if (fillsPlate(aspect)) {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                } else {
                    val mat = if (matColor.isSpecified()) matColor else domainTone(domainVisual.accentColor, placeholder)
                    Box(Modifier.fillMaxSize().background(mat))
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            ogState.shouldShowFallback -> DiscoverPoster(item, domainVisual.accentColor, Modifier.fillMaxSize())
        }
    }
}

private fun Color.isSpecified() = this != Color.Unspecified && this != Color.Transparent

private fun domainTone(accent: Color, base: Color): Color =
    if (accent == Color.Unspecified) base else accent.copy(alpha = 0.35f).compositeOver(base)

/**
 * Typographic poster for a bookmark without og:image (§8.2): the domain color as a gradient, white or ink
 * text at ≥4.5:1, and one of three layouts chosen by id. Under 150dp only the domain initial is shown.
 */
@Composable
internal fun DiscoverPoster(item: BookmarkItem, accent: Color, modifier: Modifier = Modifier) {
    val base = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    val deep = Color.Black.copy(alpha = 0.35f).compositeOver(base)
    val ink = remember(base) { posterInk(deep) }
    val label = rememberDomainLabel(item)
    val initial = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    BoxWithConstraints(modifier.background(Brush.linearGradient(listOf(base, deep)))) {
        val textRegion = Modifier.background(Color.Black.copy(alpha = ink.textRegionDarkening))
        if (maxWidth < PosterInitialOnlyWidth) {
            Text(initial, color = ink.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val spineWidth = maxWidth * 0.18f
        val initialSize = (maxHeight.value * 0.9f).sp
        when (posterVariant(item.id)) {
            0 -> {
                Text(initial, color = ink.text.copy(alpha = 0.16f), fontSize = initialSize,
                    fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp))
                PosterTitle(item.title, ink.text, Modifier.align(Alignment.TopStart).then(textRegion).padding(14.dp))
            }
            1 -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(spineWidth).background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center) {
                    Text(initial, color = ink.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                PosterTitle(item.title, ink.text, Modifier.weight(1f).then(textRegion).padding(14.dp))
            }
            else -> Column(Modifier.fillMaxSize().then(textRegion).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label.uppercase(), color = ink.text, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.text.copy(alpha = 0.6f)))
                Spacer(Modifier.height(2.dp))
                PosterTitle(item.title, ink.text, Modifier)
            }
        }
    }
}

@Composable
private fun PosterTitle(title: String, color: Color, modifier: Modifier) {
    Text(title, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Fixed-width plate for carousels and rows. */
@Composable
internal fun SizedPlate(item: BookmarkItem, width: Dp, shape: Shape, modifier: Modifier = Modifier, matColor: Color = Color.Unspecified) {
    DiscoverPlate(item, shape, modifier.width(width), matColor)
}
