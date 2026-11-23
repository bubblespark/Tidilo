package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.engagement.engagementCutoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngagementCodesTest {
    private val day = 86_400_000L

    @Test fun codesAreUniqueAndStable() {
        assertEquals((1..11).toList(), EngagementType.entries.map { it.code })
        assertEquals((0..13).toList(), EngagementSurface.entries.map { it.code })
    }

    @Test fun fromCodeRoundTripsAndRejectsUnknown() {
        EngagementType.entries.forEach { assertEquals(it, EngagementType.fromCode(it.code)) }
        EngagementSurface.entries.forEach { assertEquals(it, EngagementSurface.fromCode(it.code)) }
        assertNull(EngagementType.fromCode(99))
        assertNull(EngagementSurface.fromCode(99))
    }

    @Test fun cutoffIsNinetyDays() {
        assertEquals(10 * day, engagementCutoff(100 * day))
    }
}
