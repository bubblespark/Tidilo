package me.bubblespark.tidilo.ui.screens.library

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.ui.components.DomainWatermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val iconVersion by DomainIconManager.iconUpdateVersion.collectAsStateWithLifecycle()

    // Dynamically retrieve or compute the domain's dominant brand color (calibrated for dark-mode visibility)
    val dominantColor by produceState(
        initialValue = DomainIconManager.getCachedDominantColor(item.domain)
            ?: DomainIconManager.generateDomainAccentColor(item.domain),
        key1 = item.domain,
        key2 = iconVersion
    ) {
        val cached = DomainIconManager.getCachedDominantColor(item.domain)
        if (cached != null) {
            value = cached
        } else {
            value = withContext(Dispatchers.IO) {
                DomainIconManager.getDominantColor(context, item.domain)
            }
        }
    }

    val cardShape = remember { RoundedCornerShape(20.dp) }
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
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stackOffset * 2)
                .height(cardHeight)
                .align(Alignment.TopStart),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, dominantColor.copy(alpha = 0.18f)),
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dominantColor.copy(alpha = 0.06f))
            )
        }

        // Intermediate background stacked card (Layer 1, middle)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = stackOffset, end = stackOffset)
                .height(cardHeight)
                .align(Alignment.TopStart)
                .offset(y = stackOffset),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, dominantColor.copy(alpha = 0.28f)),
            shadowElevation = 1.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(dominantColor.copy(alpha = 0.12f))
            )
        }

        // Front interactive main card (bottom-left)
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = stackOffset * 2)
                .height(cardHeight)
                .align(Alignment.BottomStart),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = BorderStroke(1.dp, dominantColor.copy(alpha = 0.42f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
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
            ) {
                // Background watermark layer: domain icon nestled in the glowing top-right shoulder
                DomainWatermark(
                    domain = item.domain,
                    size = 96.dp,
                    alpha = 0.20f,
                    modifier = Modifier
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
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
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
                        shape = RoundedCornerShape(10.dp),
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
