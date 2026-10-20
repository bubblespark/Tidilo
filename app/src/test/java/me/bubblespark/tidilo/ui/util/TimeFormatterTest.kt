package me.bubblespark.tidilo.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatterTest {

    private val baseNow = 1792500000000L // arbitrary fixed timestamp

    @Test
    fun testJustNowBoundary() {
        // exactly now
        val res0 = TimeFormatter.calculateRelativeTime(createdAt = baseNow, now = baseNow)
        assertTrue(res0 is TimeFormatter.RelativeTimeResult.JustNow)

        // 30 seconds ago
        val res30s = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 30_000L, now = baseNow)
        assertTrue(res30s is TimeFormatter.RelativeTimeResult.JustNow)

        // 59 seconds ago
        val res59s = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 59_999L, now = baseNow)
        assertTrue(res59s is TimeFormatter.RelativeTimeResult.JustNow)
    }

    @Test
    fun testMinutesAgoBoundary() {
        // exactly 1 minute ago
        val res1m = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 60_000L, now = baseNow)
        assertTrue(res1m is TimeFormatter.RelativeTimeResult.MinutesAgo)
        assertEquals(1L, (res1m as TimeFormatter.RelativeTimeResult.MinutesAgo).minutes)

        // 4 minutes ago (user sample request)
        val res4m = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 4 * 60_000L, now = baseNow)
        assertTrue(res4m is TimeFormatter.RelativeTimeResult.MinutesAgo)
        assertEquals(4L, (res4m as TimeFormatter.RelativeTimeResult.MinutesAgo).minutes)

        // 59 minutes ago
        val res59m = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 59 * 60_000L, now = baseNow)
        assertTrue(res59m is TimeFormatter.RelativeTimeResult.MinutesAgo)
        assertEquals(59L, (res59m as TimeFormatter.RelativeTimeResult.MinutesAgo).minutes)
    }

    @Test
    fun testHoursAgoBoundary() {
        // exactly 1 hour ago
        val res1h = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 60 * 60_000L, now = baseNow)
        assertTrue(res1h is TimeFormatter.RelativeTimeResult.HoursAgo)
        assertEquals(1L, (res1h as TimeFormatter.RelativeTimeResult.HoursAgo).hours)

        // 23 hours ago
        val res23h = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 23 * 3600_000L, now = baseNow)
        assertTrue(res23h is TimeFormatter.RelativeTimeResult.HoursAgo)
        assertEquals(23L, (res23h as TimeFormatter.RelativeTimeResult.HoursAgo).hours)
    }

    @Test
    fun testDaysAgoBoundary() {
        // exactly 1 day ago
        val res1d = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 24 * 3600_000L, now = baseNow)
        assertTrue(res1d is TimeFormatter.RelativeTimeResult.DaysAgo)
        assertEquals(1L, (res1d as TimeFormatter.RelativeTimeResult.DaysAgo).days)

        // 29 days ago
        val res29d = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 29 * 86400_000L, now = baseNow)
        assertTrue(res29d is TimeFormatter.RelativeTimeResult.DaysAgo)
        assertEquals(29L, (res29d as TimeFormatter.RelativeTimeResult.DaysAgo).days)
    }

    @Test
    fun testThirtyDaysOrMoreFormattedDate() {
        // 30 days ago
        val res30d = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 30 * 86400_000L, now = baseNow)
        assertTrue(res30d is TimeFormatter.RelativeTimeResult.FormattedDate)
        val formatted = (res30d as TimeFormatter.RelativeTimeResult.FormattedDate).formatted
        assertTrue(formatted.matches(Regex("\\d{4}/\\d{2}/\\d{2}")))

        // 100 days ago
        val res100d = TimeFormatter.calculateRelativeTime(createdAt = baseNow - 100 * 86400_000L, now = baseNow)
        assertTrue(res100d is TimeFormatter.RelativeTimeResult.FormattedDate)
    }
}
