package me.bubblespark.tidilo.data.rss

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal fun decodeRssTriggerTimes(encoded: String?): List<LocalTime> = encoded.orEmpty().split(',')
    .mapNotNull { it.toIntOrNull()?.takeIf { minute -> minute in 0..1439 } }
    .distinct().sorted().take(3).map { LocalTime.of(it / 60, it % 60) }

internal fun encodeRssTriggerTimes(times: List<LocalTime>): String {
    require(times.size <= 3 && times.distinct().size == times.size)
    require(times.all { it.second == 0 && it.nano == 0 })
    return times.sorted().joinToString(",") { (it.hour * 60 + it.minute).toString() }
}

internal fun replaceRssTriggerTime(times: List<LocalTime>, original: LocalTime?, replacement: LocalTime): List<LocalTime>? {
    if (replacement.second != 0 || replacement.nano != 0) return null
    if (original != null && original !in times) return null
    val remaining = if (original == null) times else times - original
    if (replacement in remaining || remaining.size >= 3) return null
    return (remaining + replacement).sorted()
}

internal fun rssTriggerBoundary(now: Instant, time: LocalTime, zone: ZoneId): Instant {
    val date = now.atZone(zone).toLocalDate()
    val today = date.atTime(time).atZone(zone).toInstant()
    return if (today <= now) today else date.minusDays(1).atTime(time).atZone(zone).toInstant()
}
