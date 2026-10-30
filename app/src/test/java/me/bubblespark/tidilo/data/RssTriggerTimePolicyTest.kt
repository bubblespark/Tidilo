package me.bubblespark.tidilo.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import me.bubblespark.tidilo.data.rss.decodeRssTriggerTimes
import me.bubblespark.tidilo.data.rss.encodeRssTriggerTimes
import me.bubblespark.tidilo.data.rss.replaceRssTriggerTime
import me.bubblespark.tidilo.data.rss.rssTriggerBoundary
import org.junit.Assert.*
import org.junit.Test

class RssTriggerTimePolicyTest {
    private fun times(vararg values: String) = values.map(LocalTime::parse)

    @Test fun codecRoundTripsThreeTimes() {
        assertEquals("0,480,1110", encodeRssTriggerTimes(times("18:30", "00:00", "08:00")))
        assertEquals(times("00:00", "08:00", "18:30"), decodeRssTriggerTimes("0,480,1110"))
        assertEquals("", encodeRssTriggerTimes(emptyList()))
    }

    @Test fun decodeDropsInvalidValuesWithoutLegacyFallback() {
        assertEquals(emptyList<LocalTime>(), decodeRssTriggerTimes(null))
        assertEquals(emptyList<LocalTime>(), decodeRssTriggerTimes(""))
        assertEquals(times("08:00", "18:30"), decodeRssTriggerTimes("bad,-1,480,1440,480,1110"))
        assertEquals(times("00:00", "00:01", "08:00"), decodeRssTriggerTimes("1439,1110,480,1,0"))
    }

    @Test fun writesRejectInvalidLists() {
        listOf(times("00:00", "01:00", "02:00", "03:00"), times("08:00", "08:00"),
            times("08:00:01"), times("08:00:00.001")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { encodeRssTriggerTimes(invalid) }
        }
    }

    @Test fun replacementRejectsDuplicateFourthAndMissingTarget() {
        val initial = times("08:00", "18:30")
        assertNull(replaceRssTriggerTime(initial, null, LocalTime.of(8, 0)))
        assertNull(replaceRssTriggerTime(initial, LocalTime.of(18, 30), LocalTime.of(8, 0)))
        assertNull(replaceRssTriggerTime(initial, LocalTime.NOON, LocalTime.of(7, 0)))
        assertEquals(times("07:00", "08:00"), replaceRssTriggerTime(initial, LocalTime.of(18, 30), LocalTime.of(7, 0)))
        assertEquals(initial, replaceRssTriggerTime(initial, LocalTime.of(8, 0), LocalTime.of(8, 0)))
        assertEquals(times("08:00", "12:00", "18:30"), replaceRssTriggerTime(initial, null, LocalTime.NOON))
        assertNull(replaceRssTriggerTime(times("08:00", "12:00", "18:30"), null, LocalTime.of(23, 0)))
    }

    @Test fun boundariesHandleMidnightAndDst() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(Instant.parse("2026-10-30T15:00:00Z"), rssTriggerBoundary(
            Instant.parse("2026-10-30T15:01:00Z"), LocalTime.MIDNIGHT, tokyo))
        assertEquals(Instant.parse("2026-10-29T23:00:00Z"), rssTriggerBoundary(
            Instant.parse("2026-10-30T15:01:00Z"), LocalTime.of(8, 0), tokyo))
        val newYork = ZoneId.of("America/New_York")
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), rssTriggerBoundary(
            Instant.parse("2026-03-08T08:00:00Z"), LocalTime.of(2, 30), newYork))
        assertEquals(Instant.parse("2026-03-07T07:30:00Z"), rssTriggerBoundary(
            Instant.parse("2026-03-08T07:00:00Z"), LocalTime.of(2, 30), newYork))
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), rssTriggerBoundary(
            Instant.parse("2026-11-01T06:45:00Z"), LocalTime.of(1, 30), newYork))
    }
}
