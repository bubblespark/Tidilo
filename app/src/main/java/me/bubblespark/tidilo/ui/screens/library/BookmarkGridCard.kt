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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.ui.util.TimeFormatter

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

    val domain = remember(bookmark.url) { DomainIconManager.extractDomain(bookmark.url) }
    val domainDisplayName = remember(domain) {
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }

    val relativeTime = remember(bookmark.createdAt, context) {
        TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
    }

    // Asynchronously load cached og:image bitmap from database
    val ogBitmap by produceState<ImageBitmap?>(
        initialValue = domain?.let { OgImageManager.getCachedOgImageBitmap(it) },
        key1 = domain
    ) {
        if (domain != null) {
            value = OgImageManager.loadOgImageFromDb(context, domain)
        }
    }

    // Asynchronously load cached domain icon bitmap
    val domainIconBitmap by produceState<ImageBitmap?>(
        initialValue = domain?.let { DomainIconManager.getCachedIconBitmap(it) },
        key1 = domain
    ) {
        if (domain != null) {
            val cached = DomainIconManager.getCachedIconBitmap(domain)
            value = cached ?: withContext(Dispatchers.IO) {
                DomainIconManager.loadIconFromDisk(context, domain)
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val domainAccentColor = remember(domain, primaryColor) {
        domain?.let {
            DomainIconManager.getCachedDominantColor(it)
                ?: DomainIconManager.generateDomainAccentColor(it)
        } ?: primaryColor
    }

    val hasSummary = bookmark.description.isNotBlank()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (hasSummary && onLongClick != null) {
                    { onLongClick() }
                } else null
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                        // First letter fallback
                        val initial = domainDisplayName.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(domainAccentColor.copy(alpha = 0.30f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = domainAccentColor
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
