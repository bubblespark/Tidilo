package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.AiUsageDay
import me.bubblespark.tidilo.data.AiUsageState
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.CacheStorageSnapshot
import me.bubblespark.tidilo.data.CollectionCounts
import me.bubblespark.tidilo.data.DailyAiUsage
import me.bubblespark.tidilo.data.formatCacheBytes

@Composable
fun SummaryScreen(onNavigateBack: () -> Unit, viewModel: SummaryViewModel = viewModel()) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val zone by viewModel.zone.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onVisible()
        onPauseOrDispose { viewModel.onHidden() }
    }
    SummaryContent(usage, counts, cache, today, onNavigateBack,
        viewModel::retryUsage, viewModel::retryCounts, viewModel::refreshCache, zone)
}

@Composable
internal fun SummaryContent(
    usage: AiUsageState,
    counts: SummaryLoad<CollectionCounts>,
    cache: SummaryLoad<CacheStorageSnapshot>,
    today: LocalDate,
    onNavigateBack: () -> Unit,
    onRetryUsage: () -> Unit,
    onRetryCounts: () -> Unit,
    onRefreshCache: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
            }
            Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
        }
        SummarySection(stringResource(R.string.summary_aicore)) {
            when (usage) {
                AiUsageState.Loading -> SummaryLoading()
                AiUsageState.Error -> SummaryError(onRetryUsage)
                is AiUsageState.Ready -> {
                    if (usage.persistenceFailed) {
                        Text(stringResource(R.string.summary_save_warning), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetryUsage) { Text(stringResource(R.string.summary_retry)) }
                    }
                    val day = usage.snapshot.days[today] ?: DailyAiUsage()
                    SummaryValue(stringResource(R.string.summary_today), day.total.toString())
                    Text(stringResource(R.string.summary_outcomes, day.success, day.failed),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.summary_pending_outcomes, day.cancelled, day.interrupted, day.pending),
                        style = MaterialTheme.typography.bodySmall)
                    SummaryValue(stringResource(R.string.summary_debug), day.debug.toString())
                    Text(stringResource(R.string.summary_last_seven), style = MaterialTheme.typography.titleSmall)
                    UsageBars(usage.snapshot.week(today))
                    Text(stringResource(R.string.summary_recorded_since, usage.snapshot.recordedSince.toString()),
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    val quota = usage.snapshot.lastQuota
                    SummaryValue(stringResource(R.string.summary_quota),
                        quota?.let { stringResource(R.string.summary_calls, it.calls) } ?: "—")
                    if (quota != null) {
                        Text(stringResource(R.string.summary_quota_day, quota.date.toString()),
                            style = MaterialTheme.typography.bodySmall)
                        Text(formatSummaryTime(quota.detectedAt, zone), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.summary_quota_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SummarySection(stringResource(R.string.summary_collection)) {
            when (counts) {
                SummaryLoad.Loading -> SummaryLoading()
                SummaryLoad.Error -> SummaryError(onRetryCounts)
                is SummaryLoad.Ready -> {
                    SummaryValue(stringResource(R.string.summary_bookmarks), counts.value.active.toString())
                    SummaryValue(stringResource(R.string.summary_trash),
                        "${counts.value.trash} / ${BookmarkRepository.MAX_TRASH_CAPACITY}")
                }
            }
        }
        SummarySection(stringResource(R.string.summary_storage)) {
            when (cache) {
                SummaryLoad.Loading -> SummaryLoading()
                SummaryLoad.Error -> SummaryError(onRefreshCache)
                is SummaryLoad.Ready -> {
                    val snapshot = cache.value
                    CacheValue(stringResource(R.string.summary_icons), snapshot.iconFiles, snapshot.iconBytes)
                    CacheValue(stringResource(R.string.summary_images), snapshot.ogFiles, snapshot.ogBytes)
                    SummaryValue(stringResource(R.string.summary_other), formatCacheBytes(snapshot.otherBytes))
                    HorizontalDivider()
                    SummaryValue(stringResource(R.string.summary_total), formatCacheBytes(snapshot.totalBytes))
                    Text(stringResource(R.string.summary_checked, formatSummaryTime(snapshot.checkedAt, zone)),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.summary_physical_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onRefreshCache, enabled = cache !is SummaryLoad.Loading) {
                Text(stringResource(R.string.summary_refresh))
            }
        }
        Text(stringResource(R.string.summary_privacy), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SummarySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CacheValue(label: String, files: Int, bytes: Long) {
    SummaryValue(label, formatCacheBytes(bytes))
    Text(stringResource(R.string.summary_files, files), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SummaryLoading() { Text(stringResource(R.string.summary_loading)) }

@Composable
private fun SummaryError(onRetry: () -> Unit) {
    Text(stringResource(R.string.summary_error), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.summary_retry)) }
}

@Composable
private fun UsageBars(days: List<AiUsageDay>) {
    val maximum = days.maxOfOrNull { it.usage.total }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom) {
        days.forEach { day ->
            val count = if (day.recorded) day.usage.total.toString() else "—"
            val accessible = if (day.recorded) stringResource(R.string.summary_day_calls, day.date.toString(), day.usage.total)
                else stringResource(R.string.summary_day_unrecorded, day.date.toString())
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = accessible },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(count, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Box(Modifier.height(64.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val height = if (!day.recorded || day.usage.total == 0L) 2.dp
                        else (60 * day.usage.total.toDouble() / maximum).toFloat().coerceAtLeast(3f).dp
                    Box(Modifier.width(18.dp).height(height).background(
                        if (day.recorded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(6.dp)))
                }
                Text(day.date.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (days.any { !it.recorded }) Text(stringResource(R.string.summary_unrecorded),
        style = MaterialTheme.typography.bodySmall)
}

private fun formatSummaryTime(instant: Instant, zone: ZoneId): String = DateTimeFormatter
    .ofLocalizedDateTime(FormatStyle.SHORT).format(instant.atZone(zone))
