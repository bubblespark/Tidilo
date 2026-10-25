package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.parser.PreparedArticle
import me.bubblespark.tidilo.parser.RssEntry
import org.junit.Assert.*
import org.junit.Test

class AiAnalysisRssTest {
    @Test fun disabledAiFeedImportsMoreThanFiveAndRestoresConfiguredModeForFutureAcquisition() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        var mode = AiAnalysisMode.DISABLE
        val store = RssIngestionStore(db) { mode }
        val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
        try {
            db.rssDao().putFeed(feed)
            val entries = (1..8).map { RssEntry("key$it", "https://rss.example/$it", "Title $it", "", "", null, it.toLong()) }
            assertTrue(store.stage(feed, entries, 100))
            val tasks = db.importTaskDao().getAllTasks()
            assertEquals(8, tasks.size)
            assertTrue(tasks.all { it.intendedMode == "DIRECT" })
            // A failed fallback acquisition retains Direct intent when AI is re-enabled.
            store.failArticle(feed, tasks.first(), java.io.IOException("Offline"), true)
            mode = AiAnalysisMode.AICORE
            for (task in db.importTaskDao().getAllTasks()) {
                assertTrue(store.complete(feed, task, article(task.url)))
                val saved = db.bookmarkDao().getBookmarkByUrl(task.url)!!
                assertTrue(saved.isAiSkipped)
                assertEquals("HTML Description", saved.description)
                assertNull(saved.category)
                assertNull(saved.subcategory)
                assertTrue(saved.entities.isEmpty())
                assertEquals(feed.id, saved.rssFeedId)
                assertTrue(db.rssDao().hasSeen(feed.id, task.rssItemKey!!, task.url))
            }
            assertTrue(db.importTaskDao().getAllTasks().isEmpty())
            assertEquals("AI", db.rssDao().feed(feed.id)!!.mode)
            val next = RssEntry("next", "https://rss.example/next", "Next", "", "", null, 20)
            assertTrue(store.stage(feed, listOf(next), 200))
            val queued = db.importTaskDao().getAllTasks().single()
            assertEquals("AI", queued.intendedMode)
            assertTrue(store.complete(feed, queued, article(queued.url)))
            assertEquals("READY", db.importTaskDao().getTaskById(queued.id)!!.status)
        } finally { db.close() }
    }

    @Test fun disablingDuringAcquisitionBypassesAiAndAllowsEmptyDescription() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        var mode = AiAnalysisMode.AICORE
        val store = RssIngestionStore(db) { mode }
        val feed = RssFeedEntity("feed", "https://rss.example/feed", "Feed", mode = "AI")
        try {
            db.rssDao().putFeed(feed)
            store.stage(feed, listOf(RssEntry("one", "https://rss.example/one", "Title", "", "", null, 1)), 100)
            val task = db.importTaskDao().getAllTasks().single()
            mode = AiAnalysisMode.DISABLE
            assertTrue(store.complete(feed, task, article(task.url).copy(description = "", bodySnippet = "")))
            assertEquals("", db.bookmarkDao().getBookmarkByUrl(task.url)!!.description)
            assertTrue(db.importTaskDao().getAllTasks().isEmpty())
        } finally { db.close() }
    }

    private fun article(url: String) = PreparedArticle(url, "HTML Title", "Publisher", "HTML Description", "Body", null, false)
}
