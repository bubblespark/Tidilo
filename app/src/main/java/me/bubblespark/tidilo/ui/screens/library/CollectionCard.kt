package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.theme.CollectionColorPalette
import me.bubblespark.tidilo.ui.theme.LocalAppThemeStyle
import me.bubblespark.tidilo.ui.theme.themeSurfaceBlur
import androidx.compose.ui.graphics.luminance
import me.bubblespark.tidilo.ui.theme.themeCardSurface
import me.bubblespark.tidilo.data.db.CollectionWithCount
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.R

enum class CollectionPresetIcon(val id: String, val icon: ImageVector) {
    FOLDER("folder", Icons.Default.Folder),
    SCHEDULE("schedule", Icons.Default.Schedule),
    STAR("star", Icons.Default.Star),
    WORK("work", Icons.Default.Work),
    CODE("code", Icons.Default.Code),
    LIGHTBULB("lightbulb", Icons.Default.Lightbulb),
    PALETTE("palette", Icons.Default.Palette),
    FAVORITE("favorite", Icons.Default.Favorite);

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String?): CollectionPresetIcon =
            byId[id?.trim()?.lowercase(java.util.Locale.ROOT)] ?: FOLDER
    }
}

@Immutable
data class CollectionGroupItem(
    val id: String,
    val title: String,
    val description: String,
    val presetIconId: String? = CollectionPresetIcon.FOLDER.id,
    val customSymbol: String? = null, // 1 Emoji or 1 character (takes precedence when non-blank)
    val colorIndex: Int = 0,
    val count: Int = 0
) {
    val accentColor: Color
        get() = CollectionColorPalette.getOrElse(colorIndex) { CollectionColorPalette.first() }
}

/**
 * Horizontal 1-column Collection Card:
 * - Top-left: Heading title, followed by a 2-line description below it, and item count pill at bottom-left.
 * - Top-right: Specified icon or Emoji / 1-character symbol with subtle background watermark glow.
 * - Long-press supported for quick editing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionCardVisual(
    item: CollectionGroupItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val themeStyle = LocalAppThemeStyle.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardShape = remember { RoundedCornerShape(22.dp) }
    val effectiveShape = themeStyle.cardStyle.shape ?: cardShape
    val stackOffset = 6.dp
    val cardHeight = (132 * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val dominantColor = item.accentColor
    val gradient = remember(dominantColor) {
        Brush.linearGradient(
            colors = listOf(dominantColor.copy(alpha = 0.30f), dominantColor.copy(alpha = 0.10f), Color.Transparent),
            start = Offset(900f, 0f), end = Offset(150f, 380f)
        )
    }
    val symbol = item.customSymbol?.takeIf { it.isNotBlank() }
    val presetIcon = remember(item.presetIconId) { CollectionPresetIcon.fromId(item.presetIconId).icon }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight + 12.dp)
    ) {
        // Deepest background stacked card (Layer 2, top-right peek)
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

        // Intermediate background stacked card (Layer 1, middle peek)
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

        // Front interactive main horizontal card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = stackOffset * 2)
                .height(cardHeight)
                .align(Alignment.BottomStart)
                .themeCardSurface(themeStyle.cardStyle, themeStyle.surfacePalette, isDark, cardShape, MaterialTheme.colorScheme.surfaceContainerHigh)
                .clip(effectiveShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick?.let { callback ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            callback()
                        }
                    }
                ),
            shape = effectiveShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = themeStyle.cardAlpha),
            border = BorderStroke(1.dp, dominantColor.copy(alpha = 0.45f)),
            shadowElevation = 2.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Background surface blur + gradient wash layer behind content
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .themeSurfaceBlur(themeStyle.cardBlurRadius, effectiveShape)
                        .background(gradient)
                )
                // Large background watermark in top-right shoulder
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-6).dp)
                        .size(108.dp)
                        .alpha(0.18f),
                    contentAlignment = Alignment.Center
                ) {
                    if (symbol != null) {
                        Text(
                            text = symbol,
                            fontSize = 76.sp,
                            fontWeight = FontWeight.Bold,
                            color = dominantColor,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Icon(
                            imageVector = presetIcon,
                            contentDescription = null,
                            tint = dominantColor,
                            modifier = Modifier.size(92.dp)
                        )
                    }
                }

                // Foreground layout: Title + 2-line Description + Count badge
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 60.dp)
                    ) {
                        // Top-left: Heading Title
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 17.sp,
                                lineHeight = 22.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Below title: 2-line Description
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Bottom-left: Bookmark count pill badge
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = dominantColor.copy(alpha = 0.20f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentColor = MaterialTheme.colorScheme.onSurface
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
                                text = pluralStringResource(R.plurals.collection_card_item_count, item.count, item.count),
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

/**
 * Dummy Card placed at the bottom of the Collection list with a large '+' icon
 * to trigger creating a new Collection.
 */
@Composable
fun AddCollectionDummyCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 12.dp)
            .height(124.dp)
            .semantics { role = Role.Button }
            .drawWithCache {
                val stroke = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
                val radius = CornerRadius(22.dp.toPx())
                onDrawBehind {
                    drawRoundRect(color = outlineColor, style = stroke, cornerRadius = radius)
                }
            },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = primaryColor.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.35f)),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.collection_create_title),
                        tint = primaryColor,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}


internal fun CollectionWithCount.cardItem() = CollectionGroupItem(collection.id, collection.title,
    collection.description, collection.presetIconId, collection.customSymbol, collection.colorIndex, count)

@Composable
fun CollectionCard(item: CollectionWithCount, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    CollectionCardVisual(item.cardItem(), onClick, modifier, onLongClick)
}
