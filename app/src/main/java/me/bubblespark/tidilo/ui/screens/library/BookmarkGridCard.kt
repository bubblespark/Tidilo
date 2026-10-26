package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.ui.util.TimeFormatter
import me.bubblespark.tidilo.ui.components.rememberDomainIcon
import me.bubblespark.tidilo.ui.components.rememberOgImage

/**
 * Modern 2-column Grid bookmark card with 1.91:1 og:image header,
 * fixed height container, muted domain name, 3-line fixed title matching List style (titleMedium SemiBold),
 * relative time, and long-press summary peek.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkGridCard(
    bookmark: BookmarkItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val domain = bookmark.domain
    val domainDisplayName = remember(domain) {
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }

    val relativeTime = remember(bookmark.createdAt, context) {
        TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
    }

    val ogFilename = bookmark.ogImageFilename

    val iconRefreshSignal by remember(domain) {
        DomainIconManager.observeIconUpdates(domain)
    }.collectAsStateWithLifecycle(initialValue = 0L)

    // Track completion explicitly: a null bitmap alone cannot distinguish pending from failed.
    val ogState by rememberOgImage(bookmark.url, domain, bookmark.ogImageUrl, ogFilename)
    val ogBitmap = ogState.bitmap
    val domainIconBitmap by rememberDomainIcon(domain, bookmark.url, ogState.shouldShowFallback)

    val primaryColor = MaterialTheme.colorScheme.primary
    val domainAccentColor = remember(domain, primaryColor, iconRefreshSignal) {
        domain?.let {
            DomainIconManager.getCachedDominantColor(it)
                ?: DomainIconManager.generateDomainAccentColor(it)
        } ?: primaryColor
    }

    val hasSummary = bookmark.description.isNotBlank()
    val cardStyle = rememberBookmarkCardStyle(MaterialTheme.colorScheme.surfaceContainer)
    val cardShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .bookmarkCardLighting(cardStyle, cardShape)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (hasSummary && onLongClick != null) {
                    { onLongClick() }
                } else null
            ),
        shape = RoundedCornerShape(16.dp),
        color = cardStyle.containerColor,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().bookmarkCardSurfaceWash(cardStyle)) {
            // 1. Top Image Area (Aspect Ratio 1.91:1)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.91f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        // A bundled link icon is always available, including offline.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
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

            // 2. Bottom Information Area (Tightly fitted, matching List style typography)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ❶ Domain Name (Softer contrast)
                Text(
                    text = domainDisplayName.ifBlank { " " },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ❷ Bookmark Title (titleSmall SemiBold)
                Text(
                    text = bookmark.title.ifBlank { bookmark.url },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 3,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f))

                // ❸ Created Date (Relative Time, snapped to bottom-left)
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
    }
}
