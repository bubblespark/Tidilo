package me.bubblespark.tidilo.data.rss

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal fun nextRssFetchAt(now: Instant, time: LocalTime, zone: ZoneId): Instant {
    val date = now.atZone(zone).toLocalDate()
    val candidate = date.atTime(time).atZone(zone).toInstant()
    return if (candidate > now) candidate else date.plusDays(1).atTime(time).atZone(zone).toInstant()
}
