package me.bubblespark.tidilo.ui.screens.settings

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryDateTest {
    @Test fun `midnight scheduling respects daylight saving and local date`() {
        assertEquals(23 * 60 * 60 * 1000L, millisUntilSummaryMidnight(
            Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York")))
        assertEquals(25 * 60 * 60 * 1000L, millisUntilSummaryMidnight(
            Instant.parse("2026-11-01T04:00:00Z"), ZoneId.of("America/New_York")))
        assertEquals(1000L, millisUntilSummaryMidnight(
            Instant.parse("2026-10-23T14:59:59Z"), ZoneId.of("Asia/Tokyo")))
    }
}
