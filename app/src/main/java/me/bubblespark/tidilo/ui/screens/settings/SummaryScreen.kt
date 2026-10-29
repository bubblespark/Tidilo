package me.bubblespark.tidilo.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.CacheStorageSnapshot
import me.bubblespark.tidilo.data.CollectionCounts
import me.bubblespark.tidilo.data.formatCacheBytes
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

@Composable
fun SummaryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SummaryViewModel = viewModel()
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val zone by viewModel.zone.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }

    SummaryContent(
        counts = counts,
        cache = cache,
        onNavigateBack = onNavigateBack,
        onRetryCounts = viewModel::retryCounts,
        onRefreshCache = viewModel::refreshCache,
        onClearCache = viewModel::clearCache,
        zone = zone
    )
}

@Composable
internal fun SummaryContent(
    counts: SummaryLoad<CollectionCounts>,
    cache: SummaryLoad<CacheStorageSnapshot>,
    onNavigateBack: () -> Unit,
    onRetryCounts: () -> Unit = {},
    onRefreshCache: () -> Unit = {},
    onClearCache: ((() -> Unit) -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val context = LocalContext.current
    var showClearCacheConfirmDialog by remember { mutableStateOf(false) }

    TidiloScreenScaffold(
        hideHeaderOnScroll = false,
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.summary_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // Hero Card: Total Bookmarks (Primary)
            // ==========================================
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bookmarks,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(18.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        when (counts) {
                            SummaryLoad.Loading -> {
                                Text(
                                    text = "…",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            SummaryLoad.Error -> {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            is SummaryLoad.Ready -> {
                                val total = counts.value.active + counts.value.trash
                                Text(
                                    text = total.toString(),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.summary_total_bookmarks),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = stringResource(R.string.summary_local_storage_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // ==========================================
            // Card 1: Collection Status (Active & Trash)
            // ==========================================
            SummarySectionCard(
                title = stringResource(R.string.summary_collection_status),
                icon = Icons.Default.FolderShared
            ) {
                when (counts) {
                    SummaryLoad.Loading -> SummaryLoading()
                    SummaryLoad.Error -> SummaryError(onRetryCounts)
                    is SummaryLoad.Ready -> {
                        val active = counts.value.active
                        val trash = counts.value.trash
                        val maxTrash = BookmarkRepository.MAX_TRASH_CAPACITY

                        // Active bookmarks row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.summary_active_bookmarks),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = stringResource(R.string.summary_items_count, active),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        // Trash row
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (trash > 80) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                tint = if (trash > 80) MaterialTheme.colorScheme.onErrorContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = stringResource(R.string.summary_trash),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.summary_trash_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.summary_trash_capacity, trash, maxTrash),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (trash > 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (trash.toFloat() / maxTrash).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = if (trash > 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            // ==========================================
            // Card 2: Cache Storage & Maintenance
            // ==========================================
            SummarySectionCard(
                title = stringResource(R.string.summary_storage),
                icon = Icons.Default.Storage
            ) {
                Crossfade(
                    targetState = cache,
                    label = "cacheStorageTransition"
                ) { targetCache ->
                    when (targetCache) {
                        SummaryLoad.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(R.string.summary_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        SummaryLoad.Error -> SummaryError(onRefreshCache)
                        is SummaryLoad.Ready -> {
                            val snapshot = targetCache.value

                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Prominent total display
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = stringResource(R.string.summary_total),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        text = formatCacheBytes(snapshot.totalBytes),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                // Storage segment bar
                                StorageBar(
                                    imageBytes = snapshot.ogBytes,
                                    iconBytes = snapshot.iconBytes,
                                    otherBytes = snapshot.otherBytes
                                )

                                // Breakdown items
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    // Row 1: Page images
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.summary_images),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = formatCacheBytes(snapshot.ogBytes),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.summary_files, snapshot.ogFiles),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    // Row 2: Domain icons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.summary_icons),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = formatCacheBytes(snapshot.iconBytes),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.summary_files, snapshot.iconFiles),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }

                                    // Row 3: Other cache
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.summary_other),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = formatCacheBytes(snapshot.otherBytes),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                // Action buttons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = onRefreshCache,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.summary_refresh))
                                    }

                                    OutlinedButton(
                                        onClick = { showClearCacheConfirmDialog = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.summary_clear_cache_button))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // Privacy & Local Storage Note
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.summary_storage_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showClearCacheConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.summary_clear_cache_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.summary_clear_cache_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirmDialog = false
                        onClearCache?.invoke {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_clear_image_cache_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_clear),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SummarySectionCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun StorageBar(
    imageBytes: Long,
    iconBytes: Long,
    otherBytes: Long,
    modifier: Modifier = Modifier
) {
    val total = (imageBytes + iconBytes + otherBytes).coerceAtLeast(1L)
    val imageFrac = (imageBytes.toFloat() / total).coerceIn(0f, 1f)
    val iconFrac = (iconBytes.toFloat() / total).coerceIn(0f, 1f)
    val otherFrac = (otherBytes.toFloat() / total).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        if (imageFrac > 0.001f) {
            Box(
                modifier = Modifier
                    .weight(imageFrac.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (iconFrac > 0.001f) {
            Box(
                modifier = Modifier
                    .weight(iconFrac.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
        if (otherFrac > 0.001f) {
            Box(
                modifier = Modifier
                    .weight(otherFrac.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
    }
}

@Composable
private fun SummaryLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SummaryError(onRetry: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.summary_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.summary_retry))
        }
    }
}
