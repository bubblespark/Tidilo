package me.bubblespark.tidilo.data

import androidx.room.Room
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
    @Test fun disablingSkipsAcquisitionAndStaleIngestionButKeepsPendingTasks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            store.ingest(feed, listOf(entry(1)), 100)
            db.rssDao().putFeed(feed.copy(enabled = false, revision = 1))
            assertTrue(db.rssDao().enabledFeeds().isEmpty())
            assertEquals(1, db.rssDao().activeFeeds().size)
            assertFalse(store.ingest(feed, listOf(entry(2)), 200))
            assertFalse(store.ingest(feed.copy(enabled = false, revision = 1), listOf(entry(2)), 200))
            assertEquals(1, db.rssDao().pending(feed.id).size)
            assertFalse(db.rssDao().hasSeen(feed.id, entry(2).key, entry(2).url))
            val resumed = feed.copy(revision = 2)
            db.rssDao().putFeed(resumed)
            assertEquals(1, db.rssDao().enabledFeeds().size)
            assertTrue(store.ingest(resumed, listOf(entry(2)), 300))
            assertEquals(2, db.rssDao().pending(feed.id).size)
        } finally { db.close() }
    }
    @Test fun wifiConstraintRequiresWifiTransportAndUndoReconcilesFiveTasks() = runBlocking {
        val wifiRequest = me.bubblespark.tidilo.data.rss.RssScheduler.constraints(true).requiredNetworkRequest
        assertNotNull(wifiRequest)
        assertTrue(wifiRequest!!.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(wifiRequest.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertNull(me.bubblespark.tidilo.data.rss.RssScheduler.constraints(false).requiredNetworkRequest)
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            store.ingest(feed, (1..5).map(::entry), 100)
            val old = db.rssDao().pending(feed.id).minBy { it.rssPublishedAt!! }
            db.importTaskDao().deleteById(old.id)
            store.ingest(feed, listOf(entry(6)), 200)
            store.restoreTask(old)
            assertEquals(5, db.rssDao().pending(feed.id).size)
            assertNull(db.importTaskDao().getTaskById(old.id))
            db.rssDao().putFeed(feed.copy(deleted = true, revision = 1))
            store.restoreTask(old)
            assertNull(db.importTaskDao().getTaskById(old.id))
        } finally { db.close() }
    }
    @Test fun claimingDoesNotRunDiscardedOrRemovedFeedWork() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val task = me.bubblespark.tidilo.data.db.ImportTaskEntity("task", entry(1).url, "Title", "WAITING_AI", "", createdAt = 1, rssFeedId = feed.id)
            val tasks = db.importTaskDao()
            tasks.insertOrUpdate(task)
            assertEquals(1, tasks.claimForAi(task.id, "Running"))
            assertEquals("PROCESSING_AI", tasks.getTaskById(task.id)?.status)
            db.rssDao().putFeed(feed.copy(mode = "DIRECT"))
            assertEquals(1, tasks.claimForAi(task.id, "Resume running"))
            tasks.rejectUnavailableRss()
            assertEquals("PROCESSING_AI", tasks.getTaskById(task.id)?.status)
            tasks.updateStatus(task.id, "WAITING_AI", "")
            assertEquals(0, tasks.claimForAi(task.id, "Discarded"))
            tasks.rejectUnavailableRss()
            assertEquals("ERROR", tasks.getTaskById(task.id)?.status)
            tasks.updateStatus(task.id, "WAITING_AI", "Retry removed subscription")
            db.rssDao().putFeed(feed.copy(deleted = true))
            assertEquals(0, tasks.claimForAi(task.id, "Removed"))
            tasks.rejectUnavailableRss()
            assertEquals("ERROR", tasks.getTaskById(task.id)?.status)
            tasks.deleteById(task.id)
            assertEquals(0, tasks.claimForAi(task.id, "Deleted"))
            tasks.insertOrUpdate(task.copy(id = "manual", rssFeedId = null))
            assertEquals(1, tasks.claimForAi("manual", "Manual"))
        } finally { db.close() }
    }

    @Test fun failedRetentionRollsBackReceiptsAndContent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", retentionLimit = 1)
            db.rssDao().putFeed(feed)
            try {
                RssIngestionStore(db).ingest(feed, listOf(entry(1)), 100)
                fail("Invalid retention must roll back")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, db.bookmarkDao().getCount())
            assertFalse(db.rssDao().hasSeen(feed.id, entry(1).key, entry(1).url))
            val repaired = feed.copy(retentionLimit = 10)
            db.rssDao().putFeed(repaired)
            assertTrue(RssIngestionStore(db).ingest(repaired, listOf(entry(1)), 200))
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
            assertTrue(store.ingest(feed, (1..20).map(::entry), 100))
            assertEquals(10, db.bookmarkDao().getCount())
            assertEquals(0, db.importTaskDao().getCount())
            assertNull(db.bookmarkDao().getBookmarkByUrl(entry(1).url))
            assertTrue(db.bookmarkDao().getBookmarkByUrl(entry(20).url)!!.isAiSkipped)
            db.bookmarkDao().deleteById(db.bookmarkDao().getBookmarkByUrl(entry(20).url)!!.id)
            store.ingest(feed, (1..20).map(::entry), 200)
            assertEquals(9, db.bookmarkDao().getCount())
        } finally { db.close() }
    }
    @Test fun concurrentFetchesKeepFiveAndDoNotOverwriteManualBookmarks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("manual", "User title", entry(20).url, "Note", createdAt = 1, updatedAt = 1))
            val store = RssIngestionStore(db)
            coroutineScope { List(3) { async { store.ingest(feed, (1..20).map(::entry), 100) } }.awaitAll() }
            assertEquals(5, db.rssDao().pending(feed.id).size)
            assertEquals(setOf(15L, 16L, 17L, 18L, 19L), db.rssDao().pending(feed.id).map { it.rssPublishedAt }.toSet())
            assertEquals("User title", db.bookmarkDao().getBookmarkByUrl(entry(20).url)!!.title)
            val pending = db.rssDao().pending(feed.id).first()
            db.importTaskDao().updateStatus(pending.id, "WAITING_AI", "Waiting")
            store.ingest(feed, (21..23).map(::entry), 200)
            assertEquals(5, db.rssDao().pending(feed.id).size)
            db.rssDao().putFeed(feed.copy(deleted = true, revision = 1))
            assertFalse(store.ingest(feed, listOf(entry(24)), 300))
            assertEquals(5, db.rssDao().pending(feed.id).size)
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

}
