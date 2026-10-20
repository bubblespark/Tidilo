package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.ui.util.TimeFormatter

/**
 * Modern 2-column Grid bookmark card with 1.91:1 og:image header,
 * category hierarchy, up to 3-line title, relative time, and long-press summary peek.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarkGridCard(
    bookmark: BookmarkItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var isSummaryPeekVisible by remember { mutableStateOf(false) }

    val domain = remember(bookmark.url) { DomainIconManager.extractDomain(bookmark.url) }
    val domainDisplayName = remember(domain) {
        domain?.let { DomainPolicyManager.getDisplayName(it) } ?: domain ?: ""
    }

    // Category & Subcategory localization
    val localizedCategory = remember(bookmark.tag.category, context) {
        bookmark.tag.category?.takeIf { it != Tag.DEFAULT_TAG }?.let { Category.toLocalized(it, context) } ?: ""
    }
    val localizedSubcategory = remember(bookmark.tag.subcategory, context) {
        bookmark.tag.subcategory?.takeIf { it != Tag.DEFAULT_TAG }?.let { Subcategory.toLocalized(it, context) } ?: ""
    }

    val categoryHierarchy = remember(localizedCategory, localizedSubcategory) {
        when {
            localizedCategory.isNotBlank() && localizedSubcategory.isNotBlank() ->
                "$localizedCategory > $localizedSubcategory"
            localizedCategory.isNotBlank() -> localizedCategory
            localizedSubcategory.isNotBlank() -> localizedSubcategory
            else -> ""
        }
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    isSummaryPeekVisible = true
                }
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
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

                // 2. Bottom Information Area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    // ❶ Domain Name
                    if (domainDisplayName.isNotBlank()) {
                        Text(
                            text = domainDisplayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // ❷ Category > Subcategory
                    if (categoryHierarchy.isNotBlank()) {
                        Text(
                            text = categoryHierarchy,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // ❸ Bookmark Title (up to 3 lines)
                    Text(
                        text = bookmark.title.ifBlank { bookmark.url },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // ❹ Created Date (Relative Time)
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }

            // Summary Peek Overlay (Revealed on Long Press)
            AnimatedVisibility(
                visible = isSummaryPeekVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.matchParentSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .combinedClickable(
                            onClick = { isSummaryPeekVisible = false },
                            onLongClick = { isSummaryPeekVisible = false }
                        )
                        .padding(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.detail_section_summary),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = { isSummaryPeekVisible = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        val summaryText = bookmark.description.ifBlank {
                            stringResource(R.string.ai_review_no_summary)
                        }

                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
