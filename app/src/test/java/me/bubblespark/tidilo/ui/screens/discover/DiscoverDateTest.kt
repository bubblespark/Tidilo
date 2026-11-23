package me.bubblespark.tidilo.ui.screens.discover

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DiscoverDateTest {

    @Test
    fun millisUntilNextLocalDayComputesRemainingTimeToMidnight() {
        val zone = ZoneId.of("Asia/Tokyo")
        val beforeMidnight = ZonedDateTime.of(2026, 11, 23, 23, 59, 30, 0, zone)
        assertEquals(30_000L, millisUntilNextLocalDay(beforeMidnight))
    }

    @Test
    fun cachedFormatterProducesUpdatedTextWhenDateChanges() {
        val formatter = createDiscoverDateFormatter("EEEE, MMMM d", Locale.ENGLISH)
        val day1 = LocalDate.of(2026, 11, 23).format(formatter)
        val day2 = LocalDate.of(2026, 11, 24).format(formatter)

        assertEquals("Monday, November 23", day1)
        assertEquals("Tuesday, November 24", day2)
        assertNotEquals(day1, day2)
    }
}
