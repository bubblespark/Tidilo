package me.bubblespark.tidilo.ui.screens.library

import android.content.Context

import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.IconWatermark
import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.components.rememberDomainVisual
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.themeSurfaceBlur

data class DomainGroupItem(
    val domain: String,
    val displayName: String,
    val count: Int
)

/**
 * Domain-filtered card displayed in 2-column grid in LibraryScreen.
 *
 * Employs a physical stacked-deck presentation with 2 fixed background card layers peeking towards
 * the top-right, illuminated by a calibrated ambient brand-color gradient for high dark-mode contrast.
 */
@Composable
fun DomainCard(
    item: DomainGroupItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current
) {
    val domainVisual by rememberDomainVisual(item.domain, context = context)

    LibraryDeckCard(item, domainVisual.accentColor, onClick, modifier) { watermarkModifier ->
        IconWatermark(
            bitmap = domainVisual.bitmap,
            modifier = watermarkModifier,
            size = 96.dp,
            alpha = 0.20f
        )
    }
}

/** Shared physical deck geometry for domain and RSS feed collections. */
@Composable
internal fun LibraryDeckCard(
    item: DomainGroupItem,
    dominantColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watermark: @Composable (Modifier) -> Unit
) {
    val themeStyle = currentThemeSurfaces().preset
    val effectiveShape = currentThemeSurfaces().resolve(SurfaceRole.DECK_FRONT, SurfaceVariant.COMPACT).shape
    val stackOffset = 7.dp
    val cardHeight = 154.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(168.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        // Deepest background stacked card (Layer 2, top-right most)
        TidiloSurface(
            role = SurfaceRole.DECK_BACK,
            variant = SurfaceVariant.COMPACT,
            accent = dominantColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stackOffset * 2)
                .height(cardHeight)
                .align(Alignment.TopStart)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dominantColor.copy(alpha = 0.06f))
            )
        }

        // Intermediate background stacked card (Layer 1, middle)
        TidiloSurface(
            role = SurfaceRole.DECK_BACK,
            variant = SurfaceVariant.EMPHASIZED,
            accent = dominantColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stackOffset, end = stackOffset)
                .height(cardHeight)
                .align(Alignment.TopStart)
                .offset(y = stackOffset)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dominantColor.copy(alpha = 0.12f))
            )
        }

        // Front interactive main card (bottom-left)
        TidiloSurface(
            role = SurfaceRole.DECK_FRONT,
            variant = SurfaceVariant.COMPACT,
            accent = dominantColor,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = stackOffset * 2)
                .height(cardHeight)
                .align(Alignment.BottomStart)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(effectiveShape)
            ) {
                // Background surface blur + gradient wash layer behind content
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .themeSurfaceBlur(themeStyle.cardBlurRadius, effectiveShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    dominantColor.copy(alpha = 0.32f),
                                    dominantColor.copy(alpha = 0.10f),
                                    Color.Transparent
                                ),
                                start = Offset(450f, 0f),
                                end = Offset(0f, 350f)
                            )
                        )
                )
                // Background watermark layer: domain icon nestled in the glowing top-right shoulder
                watermark(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 16.dp, y = (-6).dp)
                )

                // Foreground content column
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    // Domain display name (header)
                    val titleLength = item.displayName.codePointCount(0, item.displayName.length)
                    Text(
                        text = item.displayName,
                        style = when {
                            titleLength <= 10 -> MaterialTheme.typography.titleMedium
                            titleLength <= 18 -> MaterialTheme.typography.titleSmall.copy(
                                fontSize = 14.5.sp, lineHeight = 19.sp
                            )
                            else -> MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp, lineHeight = 17.sp
                            )
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    // Domain URL (using onSurfaceVariant for high dark-theme legibility)
                    Text(
                        text = item.domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Total bookmark count pill badge
                    Surface(
                        shape = themeCorner(ThemeCorner.CONTROL),
                        color = dominantColor.copy(alpha = 0.20f),
                        contentColor = dominantColor
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.domain_card_item_count, item.count),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
