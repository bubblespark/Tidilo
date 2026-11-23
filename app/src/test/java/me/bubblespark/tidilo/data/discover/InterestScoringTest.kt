package me.bubblespark.tidilo.data.discover

import me.bubblespark.tidilo.data.discover.engine.InterestScoring
import me.bubblespark.tidilo.data.discover.engine.jitter
import me.bubblespark.tidilo.data.engagement.EngagementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestScoringTest {
    private fun scoring(vararg rows: me.bubblespark.tidilo.data.discover.engine.CandidateRow,
                        events: List<me.bubblespark.tidilo.data.discover.engine.EventRow> = emptyList(),
                        mutes: Set<Pair<TopicAxis, String>> = emptySet()) =
        InterestScoring(input(rows.toList(), events) { copy(mutes = mutes) })

    @Test fun weightsFollowTheSpec() {
        // Same save time; the only difference is one event of each type at NOW.
        val types = EngagementType.entries
        val rows = types.map { candidate(it.name, createdAt = NOW, domain = null, entities = listOf(it.name)) } +
            candidate("base", createdAt = NOW, domain = null, entities = listOf("base"))
        val s = InterestScoring(input(rows, types.map { event(it.name, it, NOW) }))
        val entity = s.entityAffinities()
        val max = 1.0 + 4.0 // save + LIKE
        val expected = mapOf(
            EngagementType.PEEK to 2.0, EngagementType.DETAIL to 3.0, EngagementType.BROWSER_OPEN to 4.0,
            EngagementType.NOTE_WRITTEN to 4.0, EngagementType.COLLECTION_ADD to 4.0, EngagementType.LIKE to 5.0,
            EngagementType.UNLIKE to 0.0, EngagementType.IMPRESSION to 1.0, EngagementType.DECK_FLICK to 1.0,
            EngagementType.SNOOZE to 1.0, EngagementType.NEVER_AGAIN to 1.0
        )
        for ((type, sum) in expected) assertEquals(type.name, sum / max, entity.getValue(type.name.lowercase()), 1e-9)
        assertEquals(1.0 / max, entity.getValue("base"), 1e-9)
    }

    @Test fun eventsDecayWithThirtyDayHalfLifeAndExpireAfterNinetyDays() {
        val s = scoring(
            candidate("fresh", createdAt = NOW - 1000 * DAY, domain = null, entities = listOf("fresh")),
            candidate("half", createdAt = NOW - 1000 * DAY, domain = null, entities = listOf("half")),
            candidate("old", createdAt = NOW - 1000 * DAY, domain = null, entities = listOf("old")),
            events = listOf(event("fresh", EngagementType.LIKE, NOW), event("half", EngagementType.LIKE, NOW - 30 * DAY),
                event("old", EngagementType.LIKE, NOW - 91 * DAY))
        ).entityAffinities()
        assertEquals(0.5, s.getValue("half") / s.getValue("fresh"), 1e-3)
        assertTrue(s.getValue("old") < 1e-9)
    }

    @Test fun negativeTopicClampsToZero() {
        val s = scoring(
            candidate("a", createdAt = NOW - 1000 * DAY, entities = listOf("x")),
            candidate("b", createdAt = NOW, entities = listOf("y")),
            events = listOf(event("a", EngagementType.UNLIKE, NOW))
        )
        assertEquals(0.0, s.entityAffinities().getValue("x"), 0.0)
        assertEquals(1.0, s.entityAffinities().getValue("y"), 0.0)
    }

    @Test fun itemAffinityBlendsAxesAndHonorsMutes() {
        val row = candidate("a", createdAt = NOW, domain = "d.com", subcategory = "sub", entities = listOf("x", "y"))
        assertEquals(1.0, scoring(row).affinity("a"), 1e-9)
        val mutedX = scoring(row, mutes = setOf(TopicAxis.ENTITY to "x", TopicAxis.DOMAIN to "d.com"))
        assertEquals(0.5 + 0.3, mutedX.affinity("a"), 1e-9)
        assertEquals("y", mutedX.topEntity("a"))
        assertTrue(mutedX.eligible("a"))
        assertFalse(scoring(row, mutes = setOf(TopicAxis.ENTITY to "x", TopicAxis.ENTITY to "y")).eligible("a"))
        assertTrue(scoring(candidate("none")).eligible("none"))
        assertEquals(0.2, scoring(candidate("none", createdAt = NOW)).affinity("none"), 1e-9)
    }

    @Test fun dormancyGrowsLogarithmicallyAndBoostsNeverOpened() {
        val s = scoring(
            candidate("today", createdAt = NOW),
            candidate("old", createdAt = NOW - 180 * DAY),
            candidate("openedOld", createdAt = NOW - 400 * DAY, lastInteractedAt = NOW - 180 * DAY),
            events = listOf(event("openedOld", EngagementType.DETAIL, NOW - 180 * DAY))
        )
        assertEquals(0.0, s.dormancy("today"), 1e-9)
        assertEquals(1.2, s.dormancy("old"), 1e-9)
        assertEquals(1.0, s.dormancy("openedOld"), 1e-9)
        assertTrue(s.everOpened("openedOld"))
        assertFalse(s.everOpened("old"))
    }

    @Test fun fatigueCountsImpressionsSinceTheLatestOpenWithinFourteenDays() {
        val s = scoring(
            candidate("a"), candidate("b"),
            events = listOf(
                event("a", EngagementType.IMPRESSION, NOW - 3 * DAY), event("a", EngagementType.IMPRESSION, NOW - 2 * DAY),
                event("a", EngagementType.IMPRESSION, NOW - 20 * DAY),
                event("b", EngagementType.IMPRESSION, NOW - 5 * DAY), event("b", EngagementType.BROWSER_OPEN, NOW - 4 * DAY),
                event("b", EngagementType.IMPRESSION, NOW - 1 * DAY)
            )
        )
        assertEquals(0.49, s.fatigue("a"), 1e-9)
        assertEquals(0.7, s.fatigue("b"), 1e-9)
    }

    @Test fun jitterIsDeterministicAndBounded() {
        val ids = (1..200).map { "id$it" }
        for (id in ids) {
            val value = jitter(42, id)
            assertTrue(value in 0.85..1.15)
            assertEquals(value, jitter(42, id), 0.0)
        }
        assertNotEquals(ids.sortedBy { jitter(1, it) }, ids.sortedBy { jitter(2, it) })
    }
}
