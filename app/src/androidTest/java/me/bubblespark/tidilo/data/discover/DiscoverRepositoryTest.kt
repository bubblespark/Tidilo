package me.bubblespark.tidilo.data.discover

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef
import me.bubblespark.tidilo.data.db.DiscoverSnapshotMetaEntity.Companion.SCOPE_DAILY
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscoverRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Tokyo")
    private val day = 86_400_000L
    private val start = LocalDateTime.of(2026, 11, 23, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private var clock = start
    private val visible = MutableStateFlow(true)
    private val ticks = MutableStateFlow(0)
    private lateinit var db: AppDatabase
    private lateinit var repo: DiscoverRepository

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = DiscoverRepository(db, nowMillis = { clock }, zone = { zone }, privateVisible = visible, random = Random(1),
            clockTicks = ticks.map { })
        db.bookmarkDao().insertAll((0 until 60).map { i ->
            bookmark("b$i", createdAt = start - (40 + i) * day, entities = listOf("Topic${i % 7}", "Shared${i % 3}"),
                isPrivate = i == 0)
        })
    }

    @After fun tearDown() = db.close()

    private fun bookmark(id: String, createdAt: Long, entities: List<String> = emptyList(), isPrivate: Boolean = false,
                         rssFeedId: String? = null, isLiked: Boolean = false) =
        BookmarkEntity(id, id, "https://fixture.invalid/$id", "", entities = entities, createdAt = createdAt, updatedAt = createdAt,
            isPrivate = isPrivate, isLiked = isLiked, ogImageUrl = "https://fixture.invalid/$id.png", rssFeedId = rssFeedId)

    private val today get() = discoverDay(clock, zone)
    private suspend fun rows() = db.discoverDao().observeSnapshot(today).first().map { Triple(it.shelf, it.position, it.bookmarkId) }

    @Test fun concurrentEnsureTodayComputesOnce() = runBlocking {
        listOf(async { repo.ensureToday() }, async { repo.ensureToday() }).awaitAll()
        val meta = db.discoverDao().meta(SCOPE_DAILY, today)!!
        assertEquals(0, meta.dealCount)
        clock += 60_000
        repo.ensureToday()
        assertEquals(meta.computedAt, db.discoverDao().meta(SCOPE_DAILY, today)!!.computedAt)
        assertTrue(rows().any { it.first == DiscoverShelf.HERO.code })
    }

    @Test fun reDealIsSerializedWithEnsureToday() = runBlocking {
        listOf(async { repo.ensureToday() }, async { repo.reDeal() }).awaitAll()
        assertEquals(1, db.discoverDao().meta(SCOPE_DAILY, today)!!.dealCount)
        repo.reDeal()
        assertEquals(2, db.discoverDao().meta(SCOPE_DAILY, today)!!.dealCount)
    }

    @Test fun trashedHeroDisappearsWithoutRecompute() = runBlocking {
        val hero = repo.observeSnapshot().first().hero!!.item.id
        db.bookmarkDao().deleteById(hero)
        assertNull(repo.observeSnapshot().first { it.hero?.item?.id != hero }.hero)
    }

    @Test fun visibilityToggleRecomputesWithTheSameSeed() = runBlocking {
        repo.ensureToday()
        val first = rows()
        visible.value = false
        repo.ensureToday()
        assertTrue(rows().none { it.third == "b0" })
        assertTrue(repo.observeSnapshot().first().let { snapshot ->
            snapshot.onThisDay.none { it.item.id == "b0" } && snapshot.sleepingGems.none { it.item.id == "b0" }
        })
        visible.value = true
        repo.ensureToday()
        assertEquals(first, rows())
    }

    @Test fun newDayStartsAtFour() = runBlocking {
        repo.ensureToday()
        val firstDay = today
        clock = LocalDateTime.of(2026, 11, 24, 3, 59).atZone(zone).toInstant().toEpochMilli()
        repo.ensureToday()
        assertEquals(firstDay, today)
        clock = LocalDateTime.of(2026, 11, 24, 4, 0).atZone(zone).toInstant().toEpochMilli()
        repo.ensureToday()
        assertNotNull(db.discoverDao().meta(SCOPE_DAILY, firstDay + 1))
    }

    @Test fun snoozeAndNeverAgainHideAndUndoRestores() = runBlocking {
        val hero = repo.observeSnapshot().first().hero!!.item.id
        val touched = db.bookmarkDao().getBookmarkById(hero)!!.lastInteractedAt
        repo.snooze(hero, EngagementSurface.UNKNOWN)
        assertNull(repo.observeSnapshot().first { it.hero == null }.hero)
        assertEquals(touched, db.bookmarkDao().getBookmarkById(hero)!!.lastInteractedAt)
        assertTrue(hero in db.discoverDao().activeSuppressedIds(clock + 29 * day))
        assertTrue(hero !in db.discoverDao().activeSuppressedIds(clock + 31 * day))
        repo.unsuppress(hero)
        assertEquals(hero, repo.observeSnapshot().first { it.hero != null }.hero!!.item.id)
        repo.neverAgain(hero, EngagementSurface.UNKNOWN)
        assertTrue(hero in db.discoverDao().activeSuppressedIds(clock + 3_650 * day))
        assertEquals(listOf(EngagementType.SNOOZE.code, EngagementType.NEVER_AGAIN.code),
            db.engagementDao().eventsFor(hero).map { it.type })
    }

    @Test fun mutingAnEntityDropsItsItemsAndMapBubble() = runBlocking {
        val before = repo.observeSnapshot().first()
        val key = before.map.first().skeletonKey
        repo.muteTopic(TopicAxis.ENTITY, key)
        val after = repo.observeSnapshot().first { snapshot -> snapshot.map.none { it.skeletonKey == key } }
        assertTrue(after.map.isNotEmpty())
        repo.unmuteTopic(TopicAxis.ENTITY, key)
        assertTrue(repo.observeSnapshot().first { snapshot -> snapshot.map.any { it.skeletonKey == key } }.map.isNotEmpty())
    }

    @Test fun impressionsAreRecordedOncePerDiscoverDay() = runBlocking {
        repo.recordImpression("b1", EngagementSurface.UNKNOWN)
        repo.recordImpression("b1", EngagementSurface.UNKNOWN)
        assertEquals(1, db.engagementDao().eventsFor("b1").size)
        clock += day
        repo.recordImpression("b1", EngagementSurface.UNKNOWN)
        assertEquals(2, db.engagementDao().eventsFor("b1").size)
        assertNull(db.bookmarkDao().getBookmarkById("b1")!!.lastInteractedAt)
    }

    @Test fun freshRssLabelsOnlyUnprotectedItemsNearExpiry() = runBlocking {
        db.bookmarkDao().insertAll(listOf(
            bookmark("expiring", createdAt = clock - 7 * day, rssFeedId = "feed"),
            bookmark("fresh", createdAt = clock - 1 * day, rssFeedId = "feed"),
            bookmark("liked", createdAt = clock - 7 * day - 3_600_000, rssFeedId = "feed", isLiked = true),
            bookmark("collected", createdAt = clock - 8 * day, rssFeedId = "feed"),
            bookmark("overdue", createdAt = clock - 10 * day, rssFeedId = "feed"),
            bookmark("collectedOld", createdAt = clock - 12 * day, rssFeedId = "feed")
        ))
        db.collectionDao().insert(CollectionEntity("c", "C", "", "folder", null, 0, clock, clock))
        db.collectionDao().insertMembership(BookmarkCollectionCrossRef("collected", "c"))
        db.collectionDao().insertMembership(BookmarkCollectionCrossRef("collectedOld", "c"))
        val picks = repo.observeFreshRss().first().associate { it.item.id to it.reason }
        // "overdue" is exactly at the 10-day cutoff and unprotected; "collectedOld" never expires.
        assertEquals(listOf("fresh", "expiring", "liked", "collected", "collectedOld"), repo.observeFreshRss().first().map { it.item.id })
        assertNull(picks["collectedOld"])
        assertEquals(DiscoverReason.RssExpiring(3), picks["expiring"])
        assertNull(picks["fresh"])
        assertNull(picks["liked"])
        assertNull(picks["collected"])
    }

    @Test fun justSavedIsManualAndRecent() = runBlocking {
        db.bookmarkDao().insertAll(listOf(
            bookmark("new", createdAt = clock - 1 * day),
            bookmark("rss", createdAt = clock - 1 * day, rssFeedId = "feed"),
            bookmark("older", createdAt = clock - 3 * day)
        ))
        assertEquals(listOf("new"), repo.observeJustSaved().first().map { it.id })
    }

    @Test fun deckDrawsVisibleUnflickedCards() = runBlocking {
        repo.recordDeckFlick("b5", EngagementSurface.UNKNOWN)
        visible.value = false
        val deck = repo.dealDeck()
        assertEquals(30, deck.size)
        assertTrue(deck.none { it.id == "b5" || it.id == "b0" })
    }

    @Test fun resetClearsHistoryAndRecomputes() = runBlocking {
        repo.ensureToday()
        repo.reDeal()
        repo.snooze("b3", EngagementSurface.UNKNOWN)
        repo.muteTopic(TopicAxis.DOMAIN, "fixture.invalid")
        repo.resetHistory()
        val dao = db.discoverDao()
        assertTrue(db.engagementDao().eventsFor("b3").isEmpty())
        assertTrue(dao.activeSuppressedIds(clock).isEmpty())
        assertTrue(dao.mutes().isEmpty())
        assertEquals(0, dao.meta(SCOPE_DAILY, today)!!.dealCount)
        assertTrue(rows().isNotEmpty())
    }

    @Test fun activeSnapshotCollectorMovesToTheNewDayAtFour(): Unit = runBlocking {
        val latestDay = MutableStateFlow<Long?>(null)
        val job = launch { repo.observeSnapshot().collect { latestDay.value = it.day } }
        try {
            val firstDay = withTimeout(5_000) { latestDay.first { it != null }!! }
            clock = LocalDateTime.of(2026, 11, 24, 4, 0).atZone(zone).toInstant().toEpochMilli()
            ticks.value++
            withTimeout(5_000) { latestDay.first { it == firstDay + 1 } }
            assertNotNull(db.discoverDao().meta(SCOPE_DAILY, firstDay + 1))
        } finally {
            job.cancel()
        }
    }

    @Test fun activeLiveShelvesFollowTheClock(): Unit = runBlocking {
        db.bookmarkDao().insertAll(listOf(
            bookmark("new", createdAt = clock - 47 * 3_600_000L),
            bookmark("article", createdAt = clock - 6 * day, rssFeedId = "feed")
        ))
        val justSaved = MutableStateFlow<List<String>>(emptyList())
        val rss = MutableStateFlow<List<DiscoverPick>>(emptyList())
        val jobs = listOf(
            launch { repo.observeJustSaved().collect { items -> justSaved.value = items.map { it.id } } },
            launch { repo.observeFreshRss().collect { rss.value = it } }
        )
        try {
            withTimeout(5_000) {
                combine(justSaved, rss) { saved, feed -> saved to feed }
                    .first { (saved, feed) -> saved == listOf("new") && feed.size == 1 }
            }
            assertNull(rss.value.single().reason)
            clock += 26 * 3_600_000L // new item is 73 h old; the article has 2 d 22 h left
            ticks.value++
            withTimeout(5_000) {
                combine(justSaved, rss) { saved, feed -> saved to feed }
                    .first { (saved, feed) -> saved.isEmpty() && feed.singleOrNull()?.reason == DiscoverReason.RssExpiring(3) }
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
