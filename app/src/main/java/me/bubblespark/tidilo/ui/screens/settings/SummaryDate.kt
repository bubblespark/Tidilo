package me.bubblespark.tidilo.ui.screens.settings

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal fun millisUntilSummaryMidnight(now: Instant, zone: ZoneId): Long = Duration.between(
    now, now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
).toMillis().coerceAtLeast(1)
