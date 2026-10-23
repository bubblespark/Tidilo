package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.parser.RssEntry
import org.junit.Assert.*
import org.junit.Test

class RssIngestionTest {
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
}
