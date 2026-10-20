package me.bubblespark.tidilo.ui.util

import android.content.Context
import me.bubblespark.tidilo.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility for formatting bookmark creation/update timestamps into relative time or absolute date.
 *
 * Rules:
 * - < 1 minute: "Just now" / "たった今"
 * - 1 to 59 minutes: "X min ago" / "X分前"
 * - 1 to 23 hours: "X hours ago" / "X時間前"
 * - 1 to 29 days: "X days ago" / "X日前"
 * - 30 days or more: "yyyy/MM/dd" (e.g. 2026/09/20)
 */
object TimeFormatter {

    const val ONE_MINUTE_MS = 60 * 1000L
    const val ONE_HOUR_MS = 60 * ONE_MINUTE_MS
    const val ONE_DAY_MS = 24 * ONE_HOUR_MS
    const val THIRTY_DAYS_MS = 30 * ONE_DAY_MS

    sealed class RelativeTimeResult {
        data object JustNow : RelativeTimeResult()
        data class MinutesAgo(val minutes: Long) : RelativeTimeResult()
        data class HoursAgo(val hours: Long) : RelativeTimeResult()
        data class DaysAgo(val days: Long) : RelativeTimeResult()
        data class FormattedDate(val formatted: String) : RelativeTimeResult()
    }

    /**
     * Calculates the relative time category without Android Context dependency (testable via JVM).
     */
    fun calculateRelativeTime(
        createdAt: Long,
        now: Long = System.currentTimeMillis()
    ): RelativeTimeResult {
        val diffMs = (now - createdAt).coerceAtLeast(0L)

        return when {
            diffMs < ONE_MINUTE_MS -> {
                RelativeTimeResult.JustNow
            }
            diffMs < ONE_HOUR_MS -> {
                val minutes = (diffMs / ONE_MINUTE_MS).coerceAtLeast(1)
                RelativeTimeResult.MinutesAgo(minutes)
            }
            diffMs < ONE_DAY_MS -> {
                val hours = (diffMs / ONE_HOUR_MS).coerceAtLeast(1)
                RelativeTimeResult.HoursAgo(hours)
            }
            diffMs < THIRTY_DAYS_MS -> {
                val days = (diffMs / ONE_DAY_MS).coerceAtLeast(1)
                RelativeTimeResult.DaysAgo(days)
            }
            else -> {
                val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                RelativeTimeResult.FormattedDate(sdf.format(Date(createdAt)))
            }
        }
    }

    /**
     * Formats the timestamp into a localized string using Android Context string resources.
     */
    fun formatRelativeTime(
        createdAt: Long,
        context: Context,
        now: Long = System.currentTimeMillis()
    ): String {
        return when (val result = calculateRelativeTime(createdAt, now)) {
            is RelativeTimeResult.JustNow -> {
                context.getString(R.string.time_just_now)
            }
            is RelativeTimeResult.MinutesAgo -> {
                context.getString(R.string.time_minutes_ago, result.minutes)
            }
            is RelativeTimeResult.HoursAgo -> {
                context.getString(R.string.time_hours_ago, result.hours)
            }
            is RelativeTimeResult.DaysAgo -> {
                context.getString(R.string.time_days_ago, result.days)
            }
            is RelativeTimeResult.FormattedDate -> {
                result.formatted
            }
        }
    }
}
