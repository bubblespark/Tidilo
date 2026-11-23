package me.bubblespark.tidilo.data.discover

import kotlin.random.Random
import me.bubblespark.tidilo.data.discover.engine.CandidateRow
import me.bubblespark.tidilo.data.discover.engine.SnapshotHistory
import me.bubblespark.tidilo.data.discover.engine.SnapshotRow
import me.bubblespark.tidilo.data.discover.engine.computeDailySnapshot
import me.bubblespark.tidilo.data.discover.engine.computeWeeklyMap
import me.bubblespark.tidilo.data.discover.engine.drawDeck
import me.bubblespark.tidilo.data.engagement.EngagementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverEngineTest {
    private val today = discoverDay(NOW, TOKYO)

    /** Items that can never be a hero (no image, no domain) and match no dated or topical rule. */
    private fun fillers(count: Int, prefix: String = "f") = (0 until count).map {
        candidate("$prefix$it", createdAt = NOW - 10 * DAY, hasOgImage = false, domain = null)
    }

    private fun compute(
        candidates: List<CandidateRow>,
        events: List<me.bubblespark.tidilo.data.discover.engine.EventRow> = emptyList(),
        privateVisible: Boolean = true,
        seed: Long = 1,
        configure: me.bubblespark.tidilo.data.discover.engine.DiscoverInput.() -> me.bubblespark.tidilo.data.discover.engine.DiscoverInput = { this }
    ) = computeDailySnapshot(input(candidates, events, configure = configure), today, seed, privateVisible)

    private fun List<SnapshotRow>.on(shelf: DiscoverShelf) = filter { it.shelf == shelf }

    @Test fun heroNeedsFiftyItems() {
        val star = candidate("star", entities = listOf("Kotlin"))
        assertTrue(compute(fillers(48) + star).on(DiscoverShelf.HERO).isEmpty())
        val hero = compute(fillers(49) + star).on(DiscoverShelf.HERO).single()
        assertEquals("star", hero.bookmarkId)
        assertEquals(DiscoverReasonCode.DORMANT_NEVER_OPENED, hero.reasonCode)
        assertEquals("100", hero.reasonArg)
    }

    @Test fun openedHeroExplainsByTopEntity() {
        val star = candidate("star", lastInteractedAt = NOW - 60 * DAY, entities = listOf("Kotlin"))
        val hero = compute(fillers(49) + star, listOf(event("star", EngagementType.DETAIL, NOW - 60 * DAY)))
            .on(DiscoverShelf.HERO).single()
        assertEquals(DiscoverReasonCode.AFFINITY_ENTITY, hero.reasonCode)
        assertEquals("Kotlin", hero.reasonArg)
    }

    @Test fun heroSkipsPrivateSuppressedAndRecentHeroes() {
        val star = candidate("star", entities = listOf("Kotlin"))
        assertTrue(compute(fillers(49) + star.copy(isPrivate = true)).on(DiscoverShelf.HERO).isEmpty())
        assertTrue(compute(fillers(49) + star) { copy(suppressedIds = setOf("star")) }.on(DiscoverShelf.HERO).isEmpty())
        assertTrue(compute(fillers(49) + star) { copy(history = SnapshotHistory(heroIds = setOf("star"))) }.on(DiscoverShelf.HERO).isEmpty())
        assertTrue(compute(fillers(49) + star) { copy(mutes = setOf(TopicAxis.ENTITY to "kotlin")) }.on(DiscoverShelf.HERO).isEmpty())
    }

    @Test fun onThisDayPicksOnePerPeriodInOrder() {
        val rows = compute(listOf(
            candidate("month", createdAt = at(2026, 10, 23)),
            candidate("year", createdAt = at(2025, 11, 23)),
            candidate("half", createdAt = at(2026, 5, 23)),
            candidate("yearNear", createdAt = at(2025, 11, 22)),
            candidate("halfNear", createdAt = at(2026, 5, 22))
        )).on(DiscoverShelf.ON_THIS_DAY)
        assertEquals(listOf("year", "half", "month"), rows.map { it.bookmarkId })
        assertEquals(listOf("ONE_YEAR", "SIX_MONTHS", "ONE_MONTH"), rows.map { it.reasonArg })
    }

    @Test fun oneYearFallsBackToAdjacentDayOnly() {
        val rows = compute(listOf(
            candidate("yearNear", createdAt = at(2025, 11, 24)),
            candidate("halfNear", createdAt = at(2026, 5, 22))
        )).on(DiscoverShelf.ON_THIS_DAY)
        assertEquals(listOf("yearNear"), rows.map { it.bookmarkId })
    }

    @Test fun sleepingGemsAreOldAndNeverOpened() {
        val rows = compute(listOf(
            candidate("young", createdAt = NOW - 29 * DAY),
            candidate("opened", createdAt = NOW - 300 * DAY, lastInteractedAt = NOW - 200 * DAY),
            candidate("peeked", createdAt = NOW - 300 * DAY, lastInteractedAt = NOW - 2 * DAY)
        ) + (0 until 8).map { candidate("gem$it", createdAt = NOW - (40 + it) * DAY) },
            listOf(event("opened", EngagementType.BROWSER_OPEN, NOW - 200 * DAY), event("peeked", EngagementType.PEEK, NOW - 2 * DAY))
        ).on(DiscoverShelf.SLEEPING_GEMS)
        assertEquals(5, rows.size)
        assertTrue(rows.none { it.bookmarkId == "young" || it.bookmarkId == "opened" })
        rows.forEach { assertEquals(DiscoverReasonCode.DORMANT_NEVER_OPENED, it.reasonCode) }
    }

    @Test fun risingCountsManualSurgesOnly() {
        fun saves(prefix: String, entity: String, count: Int, fromDay: Int, rss: Boolean = false) = (0 until count).map {
            candidate("$prefix$it", createdAt = NOW - (fromDay + it) * DAY, entities = listOf(entity), rssFeedId = if (rss) "feed" else null)
        }
        val rows = compute(
            saves("ai", "AI", 3, 1) + saves("steady", "Steady", 3, 1) + saves("steadyBase", "Steady", 30, 15) +
                saves("rss", "Feedy", 5, 1, rss = true) + saves("pair", "Pair", 2, 1)
        ).on(DiscoverShelf.RISING)
        assertEquals(listOf("ai0", "ai1", "ai2"), rows.map { it.bookmarkId })
        rows.forEach {
            assertEquals("ai", it.topicKey)
            assertEquals("3", it.reasonArg)
        }
    }

    @Test fun connectionUsesRareSharedEntityNotShownRecently() {
        // Saved 10 days ago: too young for Sleeping gems, so the pair stays available.
        fun row(id: String, entity: String) = candidate(id, createdAt = NOW - 10 * DAY, entities = listOf(entity))
        val pool = listOf(row("p", "Rare"), row("q", "Rare"), row("solo", "Unique")) + (0 until 6).map { row("c$it", "Common") }
        val rows = compute(pool).on(DiscoverShelf.CONNECTIONS)
        assertEquals(setOf("p", "q"), rows.map { it.bookmarkId }.toSet())
        rows.forEach { assertEquals("Rare", it.reasonArg); assertEquals("rare", it.topicKey) }
        assertTrue(compute(pool) { copy(history = SnapshotHistory(connectionPairs = setOf(setOf("p", "q")))) }
            .on(DiscoverShelf.CONNECTIONS).isEmpty())
    }

    @Test fun shelvesNeverRepeatAnItem() {
        val rows = compute(randomLibrary(300))
        assertTrue(rows.on(DiscoverShelf.HERO).isNotEmpty())
        assertEquals(rows.size, rows.map { it.bookmarkId }.toSet().size)
    }

    @Test fun hiddenPrivateNeverAppears() {
        val library = randomLibrary(300)
        val privateIds = library.filter { it.isPrivate }.map { it.id }.toSet()
        assertTrue(privateIds.isNotEmpty())
        assertTrue(compute(library, privateVisible = false).none { it.bookmarkId in privateIds })
        val privateOnly = library.filter { it.isPrivate }.flatMap { it.entities }.map { it.skeletonKey }.toSet() -
            library.filter { !it.isPrivate }.flatMap { it.entities }.map { it.skeletonKey }.toSet()
        val map = computeWeeklyMap(input(library), privateVisible = false)
        assertTrue(map.none { it.skeletonKey in privateOnly })
    }

    @Test fun sameSeedIsStableAndReDealChangesPicks() {
        val library = randomLibrary(300)
        val events = randomEvents(library)
        assertEquals(compute(library, events, seed = 7), compute(library, events, seed = 7))
        assertNotEquals(compute(library, events, seed = 7), compute(library, events, seed = 8))
    }

    @Test fun mapNeedsThirtyItemsAndSkipsMutedEntities() {
        assertTrue(computeWeeklyMap(input(fillers(29)), true).isEmpty())
        val library = (0 until 40).map { candidate("m$it", entities = listOf("Topic${it % 25}", "Common")) }
        val map = computeWeeklyMap(input(library) { copy(mutes = setOf(TopicAxis.ENTITY to "common")) }, true)
        assertEquals(20, map.size)
        assertTrue(map.none { it.skeletonKey == "common" })
        assertEquals(map.sortedByDescending { it.weight }, map)
    }

    @Test fun deckDrawsThirtyDistinctUnflickedCards() {
        val library = randomLibrary(300)
        val flicked = library.take(50).map { it.id }.toSet()
        val deck = drawDeck(input(library), seed = 3, privateVisible = false, flicked = flicked)
        assertEquals(30, deck.size)
        assertEquals(30, deck.toSet().size)
        assertTrue(deck.none { it in flicked })
        val privateIds = library.filter { it.isPrivate }.map { it.id }.toSet()
        assertTrue(deck.none { it in privateIds })
        assertEquals(setOf("a", "b"), drawDeck(input(listOf(candidate("a"), candidate("b"))), 1, true, emptySet()).toSet())
    }

    @Test fun twoThousandItemsComputeQuickly() {
        val library = randomLibrary(2_000, entityPool = 400)
        val events = randomEvents(library, count = 30_000)
        val data = input(library, events)
        computeDailySnapshot(data, today, 1, true) // warm-up
        val start = System.nanoTime()
        computeDailySnapshot(data, today, 2, true)
        computeWeeklyMap(data, true)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 500)
    }

    private fun randomLibrary(size: Int, entityPool: Int = 40): List<CandidateRow> {
        val random = Random(11)
        return (0 until size).map { i ->
            candidate(
                "b$i",
                createdAt = NOW - random.nextLong(1, 800) * DAY,
                isPrivate = random.nextInt(10) == 0,
                hasOgImage = random.nextBoolean(),
                domain = "d${random.nextInt(20)}.com",
                subcategory = "s${random.nextInt(11)}",
                rssFeedId = if (random.nextInt(3) == 0) "feed" else null,
                entities = (0 until random.nextInt(2, 6)).map { "E${random.nextInt(entityPool)}" }.distinct()
            )
        }
    }

    private fun randomEvents(library: List<CandidateRow>, count: Int = 2_000): List<me.bubblespark.tidilo.data.discover.engine.EventRow> {
        val random = Random(5)
        val types = EngagementType.entries
        return (0 until count).map {
            event(library[random.nextInt(library.size)].id, types[random.nextInt(types.size)], NOW - random.nextLong(0, 90) * DAY)
        }
    }
}
