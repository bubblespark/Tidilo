package me.bubblespark.tidilo.data.discover

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverCodesTest {
    @Test fun codesAreStableAndAppendOnly() {
        assertEquals((1..9).toList(), DiscoverShelf.entries.map { it.code })
        assertEquals((1..6).toList(), DiscoverReasonCode.entries.map { it.code })
        assertEquals((1..3).toList(), TopicAxis.entries.map { it.code })
        DiscoverShelf.entries.forEach { assertEquals(it, DiscoverShelf.fromCode(it.code)) }
        assertNull(DiscoverReasonCode.fromCode(99))
    }

    @Test fun dayRollsOverAtFourInTheMorning() {
        val nov23 = LocalDate.of(2026, 11, 23).toEpochDay()
        assertEquals(nov23 - 1, discoverDay(at(2026, 11, 23, 3) + 59 * 60_000, TOKYO))
        assertEquals(nov23, discoverDay(at(2026, 11, 23, 4), TOKYO))
        assertEquals(at(2026, 11, 23, 4), discoverDayStart(nov23, TOKYO))
    }

    @Test fun weekStartsOnMonday() {
        val sunday = LocalDate.of(2026, 11, 29).toEpochDay()
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), weekStartDay(sunday))
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), weekStartDay(LocalDate.of(2026, 11, 23).toEpochDay()))
    }
}
