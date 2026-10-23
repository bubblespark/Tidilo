package me.bubblespark.tidilo.data.rss

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import me.bubblespark.tidilo.data.db.ImportTaskEntity

internal fun selectRssPending(existing: List<ImportTaskEntity>, fresh: List<ImportTaskEntity>): List<ImportTaskEntity> =
    (existing + fresh).distinctBy { it.url }.sortedWith(
        compareByDescending<ImportTaskEntity> { it.rssPublishedAt ?: it.createdAt }
            .thenByDescending { it.createdAt }.thenBy { it.id }
    ).take(5)

internal fun nextRssFetchAt(now: Instant, time: LocalTime, zone: ZoneId): Instant {
    val date = now.atZone(zone).toLocalDate()
    val candidate = date.atTime(time).atZone(zone).toInstant()
    return if (candidate > now) candidate else date.plusDays(1).atTime(time).atZone(zone).toInstant()
}
