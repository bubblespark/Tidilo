package me.bubblespark.tidilo.ui.screens.library

import android.content.Context

import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.components.rememberDomainVisual
import me.bubblespark.tidilo.ui.components.rememberOgImage
import me.bubblespark.tidilo.ui.util.TimeFormatter

/**
 * Grid bookmark card with a shared media frame, a three-line title, relative time,
 * and long-press summary peek.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookmarkGridCard(
    bookmark: BookmarkItem,
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onToggleLike: () -> Unit = {},
    layout: BookmarkGridLayout,
    showRssSource: Boolean = false
) {
    val textMetrics = layout.text
    val likeClearance = if (layout.mediaHeight < BookmarkGridLikeTarget) {
        BookmarkGridLikeTarget - BookmarkGridInfoSidePadding
    } else 0.dp
    val visualSource = LocalCacheVisualSource.current
    val domain = remember(bookmark.domain, bookmark.url, visualSource) {
        bookmark.domain ?: visualSource.extractDomain(bookmark.url)
    }
    val domainDisplayName = remember(domain) {
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }

    val relativeTime = remember(bookmark.createdAt, context) {
        TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
    }

    val ogFilename = bookmark.ogImageFilename

    // Track completion explicitly: a null bitmap alone cannot distinguish pending from failed.
    val ogState by rememberOgImage(bookmark.url, domain, bookmark.ogImageUrl, ogFilename, context = context, source = visualSource)
    val ogBitmap = ogState.bitmap
    val domainVisual by rememberDomainVisual(domain, bookmark.url, ogState.shouldShowFallback, context = context, source = visualSource)
    val domainIconBitmap = domainVisual.bitmap

    val primaryColor = MaterialTheme.colorScheme.primary
    val domainAccentColor = if (domainVisual.accentColor != Color.Unspecified) {
        domainVisual.accentColor
    } else {
        primaryColor
    }

    val hasSummary = bookmark.description.isNotBlank()
    val effectiveShape = currentThemeSurfaces().resolve(SurfaceRole.BOOKMARK_CARD, SurfaceVariant.COMPACT).shape
    val topImageShape = if (effectiveShape is RoundedCornerShape) {
        effectiveShape.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
        )
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    }

    TidiloSurface(
        onClick = onClick,
        onLongClick = if (hasSummary) onLongClick else null,
        onLongClickLabel = if (hasSummary && onLongClick != null) stringResource(R.string.library_summary_peek_open) else null,
        role = SurfaceRole.BOOKMARK_CARD,
        variant = SurfaceVariant.COMPACT,
        accent = LocalCardAccentColor.current,
        modifier = modifier
            .fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
            // 1. Shared image and fallback frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.mediaHeight)
                    .clip(topImageShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                domainAccentColor.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val currentOg = ogBitmap
                if (currentOg != null) {
                    Image(
                        bitmap = currentOg,
                        contentDescription = bookmark.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Fallback domain icon placeholder
                    val currentIcon = domainIconBitmap
                    if (currentIcon != null) {
                        Image(
                            bitmap = currentIcon,
                            contentDescription = domainDisplayName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(themeCorner(ThemeCorner.SMALL))
                        )
                    } else {
                        // A bundled link icon is always available, including offline.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(themeCorner(ThemeCorner.SMALL))
                                .background(domainAccentColor.copy(alpha = 0.30f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                tint = domainAccentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 2. Text slots reserve space at the active font scale.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = BookmarkGridInfoSidePadding,
                        end = BookmarkGridInfoSidePadding,
                        top = BookmarkGridInfoTopPadding,
                        bottom = BookmarkGridInfoBottomPadding
                    )
            ) {
                // ❶ Domain Name (Softer contrast); the RSS cue keeps its width and the domain ellipsizes.
                Row(
                    modifier = Modifier.padding(end = likeClearance).heightIn(min = textMetrics.labelHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = domainDisplayName.ifBlank { " " },
                        modifier = Modifier.weight(1f),
                        style = textMetrics.labelStyle,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showRssSource) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.RssFeed,
                            contentDescription = stringResource(R.string.library_rss_source_description),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(textMetrics.labelHeight)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(BookmarkGridInfoGap))

                // ❷ Bookmark Title (titleSmall SemiBold)
                Text(
                    text = bookmark.title.ifBlank { bookmark.url },
                    modifier = Modifier.heightIn(min = textMetrics.titleHeight),
                    style = textMetrics.titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(BookmarkGridInfoGap))

                // ❸ Created Date (Relative Time, snapped to bottom-left)
                Text(
                    text = relativeTime,
                    modifier = Modifier.heightIn(min = textMetrics.labelHeight),
                    style = textMetrics.labelStyle,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            }
            Box(
                modifier = Modifier.align(Alignment.TopEnd).size(BookmarkGridLikeTarget).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onToggleLike
                ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.offset(x = 4.dp, y = (-4).dp).size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BookmarkLikeIndicator(bookmark.isLiked)
                }
            }
        }
    }
}
