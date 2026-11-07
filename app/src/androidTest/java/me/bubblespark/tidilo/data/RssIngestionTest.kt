package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.moveBookmarkToTrash
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.parser.RssEntry
import org.junit.Assert.*
import org.junit.Test

class RssIngestionTest {
    @Test fun resubscriptionRequiresCurrentConsentAndReusesIdentityAndReceipts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", retentionLimit = 100)
            val store = RssIngestionStore(db)
            db.rssDao().putFeed(feed)
            ingestPreparedFixture(db, feed, (1..20).map(::entry), 100)
            db.rssDao().putFeed(feed.copy(deleted = true, revision = 1))
            val consent = store.registrationSnapshot(feed.url)!!
            assertEquals(20, consent.savedCount)
            val resumed = feed.copy(retentionLimit = 10, revision = 2)
            try { store.registerFeed(resumed, null); fail("Consent required") }
            catch (required: me.bubblespark.tidilo.data.rss.RssRegistrationConfirmationRequired) {
                assertEquals(consent, required.snapshot)
            }
            assertTrue(db.rssDao().feed(feed.id)!!.deleted)
            assertEquals(20, db.rssDao().savedCount(feed.id))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("extra", "Late save", entry(21).url, "", createdAt = 200, updatedAt = 200, rssFeedId = feed.id, rssPublishedAt = 21))
            try { store.registerFeed(resumed, consent); fail("Changed count requires new consent") }
            catch (required: me.bubblespark.tidilo.data.rss.RssRegistrationConfirmationRequired) {
                assertEquals(21, required.snapshot.savedCount)
            }
            assertTrue(db.rssDao().feed(feed.id)!!.deleted)
            store.registerFeed(resumed, store.registrationSnapshot(feed.url))
            ingestPreparedFixture(db, resumed, emptyList(), 300)
            assertEquals(10, db.rssDao().savedCount(feed.id))
            assertEquals(feed.id, db.rssDao().feedByUrl(feed.url)!!.id)
            assertEquals(1, db.rssDao().activeFeeds().size)
            assertTrue(db.rssDao().hasSeen(feed.id, entry(1).key, entry(1).url))
            assertNull(db.bookmarkDao().getBookmarkByUrl(entry(1).url))
            db.rssDao().putFeed(resumed.copy(deleted = true, revision = 3))
            val unlimited = resumed.copy(retentionLimit = null, revision = 4)
            store.registerFeed(unlimited, store.registrationSnapshot(feed.url))
            ingestPreparedFixture(db, unlimited, emptyList(), 400)
            assertEquals(10, db.rssDao().savedCount(feed.id))
        } finally { db.close() }
    }
    @Test fun disablingSkipsAcquisitionAndStaleIngestionButKeepsSavedBookmarks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            ingestPreparedFixture(db, feed, listOf(entry(1)), 100)
            db.rssDao().putFeed(feed.copy(enabled = false, revision = 1))
            assertTrue(db.rssDao().enabledFeeds().isEmpty())
            assertEquals(1, db.rssDao().activeFeeds().size)
            assertFalse(ingestPreparedFixture(db, feed, listOf(entry(2)), 200))
            assertFalse(ingestPreparedFixture(db, feed.copy(enabled = false, revision = 1), listOf(entry(2)), 200))
            assertEquals(1, db.rssDao().savedCount(feed.id))
            assertFalse(db.rssDao().hasSeen(feed.id, entry(2).key, entry(2).url))
            val resumed = feed.copy(revision = 2)
            db.rssDao().putFeed(resumed)
            assertEquals(1, db.rssDao().enabledFeeds().size)
            assertTrue(ingestPreparedFixture(db, resumed, listOf(entry(2)), 300))
            assertEquals(2, db.rssDao().savedCount(feed.id))
        } finally { db.close() }
    }
    @Test fun wifiConstraintRequiresWifiTransport() = runBlocking {
        val wifiRequest = me.bubblespark.tidilo.data.rss.RssScheduler.constraints(true).requiredNetworkRequest
        assertNotNull(wifiRequest)
        assertTrue(wifiRequest!!.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(wifiRequest.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertNull(me.bubblespark.tidilo.data.rss.RssScheduler.constraints(false).requiredNetworkRequest)
    }

    @Test fun failedRetentionRollsBackReceiptsAndContent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", retentionLimit = 1)
            db.rssDao().putFeed(feed)
            try {
                ingestPreparedFixture(db, feed, listOf(entry(1)), 100)
                fail("Invalid retention must roll back")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.bookmarkDao().getCount())
            assertFalse(db.rssDao().hasSeen(feed.id, entry(1).key, entry(1).url))
            val repaired = feed.copy(retentionLimit = 10)
            db.rssDao().putFeed(repaired)
            assertTrue(ingestPreparedFixture(db, repaired, listOf(entry(1)), 200))
            assertEquals(1, db.bookmarkDao().getCount())
        } finally { db.close() }
    }

    @Test fun sharedImageAliasesStayReferencedAcrossLibraryTrashAndTasks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val image = "https://rss.example/shared.png"
            val bookmark = BookmarkEntity("shared", "Shared", "https://rss.example/post", "", createdAt = 1, updatedAt = 1, ogImageUrl = "/shared.png")
            db.bookmarkDao().insertOrUpdate(bookmark)
            val key = OgImageManager.computeImageKey(image)
            assertTrue(db.rssDao().imageReferenced(key))
            db.moveBookmarkToTrash(bookmark.id, 100, 100)
            assertTrue(db.rssDao().imageReferenced(key))
            db.trashDao().deleteById(bookmark.id)
            assertFalse(db.rssDao().imageReferenced(key))
            val task = me.bubblespark.tidilo.data.db.ImportTaskEntity("task", "https://other.example/post", "Title", "READY", "", createdAt = 1, ogImageUrl = image)
            db.importTaskDao().insertOrUpdate(task)
            assertTrue(db.rssDao().imageReferenced(key))
            db.importTaskDao().deleteById(task.id)
            assertFalse(db.rssDao().imageReferenced(key))
        } finally { db.close() }
    }
    private fun entry(id: Int) = RssEntry("id:$id", "https://rss.example/$id", "Post $id", "Text", "Text", null, id.toLong())
    @Test fun directImportsWithoutQueueAndRetentionPreventsReplay() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", retentionLimit = 10)
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            assertTrue(ingestPreparedFixture(db, feed, (1..20).map(::entry), 100))
            assertEquals(10, db.bookmarkDao().getCount())
            assertEquals(0, db.importTaskDao().getCount())
            assertNull(db.bookmarkDao().getBookmarkByUrl(entry(1).url))
            db.bookmarkDao().deleteById(db.bookmarkDao().getBookmarkByUrl(entry(20).url)!!.id)
            ingestPreparedFixture(db, feed, (1..20).map(::entry), 200)
            assertEquals(9, db.bookmarkDao().getCount())
        } finally { db.close() }
    }
    @Test fun concurrentFetchesSaveOnceAndDoNotOverwriteManualBookmarks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed")
            db.rssDao().putFeed(feed)
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("manual", "User title", entry(20).url, "Note", createdAt = 1, updatedAt = 1))
            coroutineScope { List(3) { async { ingestPreparedFixture(db, feed, (1..20).map(::entry), 100) } }.awaitAll() }
            assertEquals(19, db.rssDao().savedCount(feed.id))
            assertEquals("User title", db.bookmarkDao().getBookmarkByUrl(entry(20).url)!!.title)
            assertEquals(0, db.importTaskDao().getCount())
        } finally { db.close() }
    }

    @Test fun librarySourceScopesDoNotNarrowCombinedSearchOrTagCounts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(BookmarkEntity("manual", "Shared title", "https://same.example/manual", "", "Article", "Tech", createdAt = 1, updatedAt = 1))
            dao.insertOrUpdate(BookmarkEntity("rss", "Shared title", "https://same.example/rss", "", "Article", "Tech", createdAt = 2, updatedAt = 2, rssFeedId = "feed"))
            assertEquals(1, dao.observeCount(me.bubblespark.tidilo.data.db.BookmarkQueries.count("Shared", true, source = BookmarkSource.MANUAL)).first())
            assertEquals(1, dao.observeCount(me.bubblespark.tidilo.data.db.BookmarkQueries.count("Shared", true, source = BookmarkSource.RSS)).first())
            assertEquals(2, dao.observeCount(me.bubblespark.tidilo.data.db.BookmarkQueries.count("Shared", true)).first())
            assertEquals(1, dao.domainCounts(true, BookmarkSource.MANUAL.sqlValue).first().single().count)
            assertEquals(2, dao.tagCounts(me.bubblespark.tidilo.data.db.BookmarkQueries.tags(emptySet(), true, me.bubblespark.tidilo.data.db.TagKind.CATEGORY)).first().single().count)
        } finally { db.close() }
    }

    /** Exercises the store with explicitly prepared fixture content, rather than treating XML as HTML. */
    private suspend fun ingestPreparedFixture(db: AppDatabase, feed: RssFeedEntity,
        entries: List<RssEntry>, now: Long): Boolean = db.withTransaction {
        val store = RssIngestionStore(db)
        if (!store.stage(feed, entries, now)) return@withTransaction false
        for (task in db.importTaskDao().getAllTasks().filter { it.rssFeedId == feed.id && !it.articlePrepared }) {
            val entry = entries.firstOrNull { it.url == task.url } ?: continue
            store.complete(feed, task, me.bubblespark.tidilo.parser.PreparedArticle(entry.url, entry.title,
                "rss.example", entry.description, entry.bodySnippet, entry.imageUrl, false))
        }
        store.enforceRetention(feed.id)
        true
    }

}
