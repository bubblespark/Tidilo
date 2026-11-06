package me.bubblespark.tidilo.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import me.bubblespark.tidilo.data.rss.nextRssFetchAt
import org.junit.Assert.*
import org.junit.Test

class RssPolicyTest {
    @Test fun newSubscriptionsDefaultToThirtySavedItems() {
        assertEquals(30, me.bubblespark.tidilo.data.db.RssFeedEntity("feed", "https://example.com/feed", "Feed").retentionLimit)
    }
    @Test fun midnightScheduleUsesLocalWallClockAcrossDst() {
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), nextRssFetchAt(
            Instant.parse("2026-03-08T05:00:00Z"), LocalTime.MIDNIGHT, ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-10-23T15:00:00Z"), nextRssFetchAt(
            Instant.parse("2026-10-23T14:00:00Z"), LocalTime.MIDNIGHT, ZoneId.of("Asia/Tokyo")))
    }
}
