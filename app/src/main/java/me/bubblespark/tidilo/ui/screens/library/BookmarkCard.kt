package me.bubblespark.tidilo.ui.screens.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.ui.components.DomainWatermark
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory

/**
 * Bookmark item card (supports keyword highlighting and tag icon matching).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkItemCard(
    bookmark: BookmarkItem,
    wordKeywords: List<String> = emptyList(),
    tagKeywords: List<String> = emptyList(),
    currentLanguage: AppLanguage,
    onClick: () -> Unit = {}
) {
    // Localized category and subcategory labels
    val localizedCategory = remember(bookmark.tag.category, currentLanguage) {
        Category.toLocalized(bookmark.tag.category, currentLanguage.isJapanese)
    }
    val localizedSubcategory = remember(bookmark.tag.subcategory, currentLanguage) {
        Subcategory.toLocalized(bookmark.tag.subcategory, currentLanguage.isJapanese)
    }

    // Tag match evaluation (category and subcategory)
    val isCategoryMatched = remember(tagKeywords, bookmark.tag.category, localizedCategory) {
        if (tagKeywords.isEmpty()) false
        else tagKeywords.any {
            it.equals(bookmark.tag.category, ignoreCase = true) ||
                    it.equals(localizedCategory, ignoreCase = true)
        }
    }
    val isSubcategoryMatched = remember(tagKeywords, bookmark.tag.subcategory, localizedSubcategory) {
        if (tagKeywords.isEmpty()) false
        else tagKeywords.any {
            it.equals(bookmark.tag.subcategory, ignoreCase = true) ||
                    it.equals(localizedSubcategory, ignoreCase = true)
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // M3 Expressive Large Rounding
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        val domain = remember(bookmark.url) { DomainIconManager.extractDomain(bookmark.url) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
        ) {
            // Background watermark layer: domain icon (lightly displayed clipped in top-right)
            DomainWatermark(
                domain = domain,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 36.dp, y = (-12).dp)
            )

            Column(
                modifier = Modifier.padding(20.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // In default state without query keywords, render plain String directly to avoid AnnotatedString overhead
                if (wordKeywords.isEmpty()) {
                    Text(
                        text = bookmark.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val highlightedTitle = rememberHighlightedText(
                        text = bookmark.title,
                        keywords = wordKeywords
                    )
                    Text(
                        text = highlightedTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                val context = LocalContext.current
                IconButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(bookmark.url)).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            Toast.makeText(
                                context,
                                if (currentLanguage.isJapanese) "ブラウザを開けませんでした" else "Could not open browser",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInBrowser,
                        contentDescription = "Open in browser",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (wordKeywords.isEmpty()) {
                Text(
                    text = bookmark.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                val highlightedUrl = rememberHighlightedText(
                    text = bookmark.url,
                    keywords = wordKeywords
                )
                Text(
                    text = highlightedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (wordKeywords.isEmpty()) {
                Text(
                    text = bookmark.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val highlightedDescription = rememberHighlightedText(
                    text = bookmark.description,
                    keywords = wordKeywords
                )
                Text(
                    text = highlightedDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tag layout: [category] > [subcategory] newline [entities] (spaced uniformly with 8.dp)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Line 1: [category] > [subcategory]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactTagBadge(
                        icon = Icons.Default.Folder,
                        text = localizedCategory,
                        isMatched = isCategoryMatched,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        matchedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        matchedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        text = ">",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline
                    )

                    CompactTagBadge(
                        icon = Icons.AutoMirrored.Filled.Label,
                        text = localizedSubcategory,
                        isMatched = isSubcategoryMatched,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        matchedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        matchedContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )

                    if (bookmark.isNsfw) {
                        CompactTagBadge(
                            icon = null,
                            text = "NSFW",
                            isMatched = false,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            matchedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            matchedContentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Line 2: [entities] (leading '#' removed as icon is present; 8.dp uniform vertical spacing)
                if (bookmark.tag.entities.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        bookmark.tag.entities.forEach { entity ->
                            val cleanEntity = entity.removePrefix("#")
                            val isEntityMatched = if (tagKeywords.isEmpty()) false else tagKeywords.any { it.equals(cleanEntity, ignoreCase = true) }
                            CompactTagBadge(
                                icon = Icons.Default.Tag,
                                text = cleanEntity,
                                isMatched = isEntityMatched,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                matchedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                matchedContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * Lightweight tag badge optimized for list item rendering.
 */
@Composable
fun CompactTagBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    text: String,
    isMatched: Boolean,
    containerColor: Color,
    contentColor: Color,
    matchedContainerColor: Color,
    matchedContentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(26.dp)
            .background(
                color = if (isMatched) matchedContainerColor else containerColor,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isMatched) matchedContentColor else contentColor.copy(alpha = 0.75f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isMatched) FontWeight.Bold else FontWeight.Medium,
                color = if (isMatched) matchedContentColor else contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Helper providing background and text color highlight for substring matches.
 */
@Composable
fun rememberHighlightedText(
    text: String,
    keywords: List<String>,
    highlightColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
    highlightTextColor: Color = MaterialTheme.colorScheme.primary
): AnnotatedString {
    return remember(text, keywords, highlightColor, highlightTextColor) {
        if (keywords.isEmpty() || text.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                keywords.forEach { keyword ->
                    if (keyword.isNotBlank()) {
                        var startIndex = 0
                        while (startIndex < text.length) {
                            val index = text.indexOf(keyword, startIndex, ignoreCase = true)
                            if (index < 0) break
                            val endIndex = index + keyword.length
                            addStyle(
                                style = SpanStyle(
                                    background = highlightColor,
                                    color = highlightTextColor,
                                    fontWeight = FontWeight.Bold
                                ),
                                start = index,
                                end = endIndex
                            )
                            startIndex = endIndex
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lightweight skeleton placeholder displayed during initial loading.
 */
@Composable
fun SkeletonBookmarkList(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(3) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(18.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    ) {}
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(14.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                    ) {}
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(14.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                    ) {}
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier
                                .width(70.dp)
                                .height(26.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        ) {}
                        Surface(
                            modifier = Modifier
                                .width(90.dp)
                                .height(26.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        ) {}
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(140.dp))
    }
}

/**
 * Placeholder displayed when search results are empty.
 */
@Composable
fun EmptySearchResult(query: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No bookmarks found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No results matching: \"$query\"\nTry searching with keywords or #Tags (e.g., #AI, #Model)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
