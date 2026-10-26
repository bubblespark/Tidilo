package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.parser.PreparedArticle
import me.bubblespark.tidilo.parser.RssEntry
import me.bubblespark.tidilo.parser.RssFeedParser
import org.junit.Assert.*
import org.junit.Test

class RssIngestionStoreTest {
    @Test fun directFeedCategoriesRemainSearchableAfterTaskCompletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            db.rssDao().putFeed(feed)
            val entry = RssFeedParser.parse("""<rss><channel><item><link>https://example.com/post</link>
                <category>  #Kotlin  </category><category>Android</category></item></channel></rss>""", feed.url).entries.single()
            val store = RssIngestionStore(db)
            assertTrue(store.stage(feed, listOf(entry), 100))
            val task = db.importTaskDao().getAllTasks().single()
            assertEquals(listOf("Kotlin", "Android"), task.assignedTags)
            assertTrue(store.complete(feed, task, article(task.url)))
            val bookmark = db.bookmarkDao().getBookmarkByUrl(task.url)!!
            assertEquals(listOf("Kotlin", "Android"), bookmark.entities)
            assertEquals(listOf("Kotlin", "Android"), bookmark.toBookmarkItem().tag.entities)
            assertNull(bookmark.toBookmarkItem().tag.category)
            assertNull(bookmark.toBookmarkItem().tag.subcategory)
            assertTrue(bookmark.isAiSkipped)
            assertNull(db.importTaskDao().getTaskById(task.id))
            assertEquals(1, db.bookmarkDao().observeCount(BookmarkQueries.count("#Kotlin", true)).first())
            assertEquals(1, db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.ENTITY))
                .first().single { it.value == "Kotlin" }.count)
        } finally { db.close() }
    }

    @Test fun directRetryAfterAiModeChangeRetainsStagedCategories() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        var mode = AiAnalysisMode.AICORE
        try {
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db) { mode }
            assertTrue(store.stage(feed, listOf(RssEntry("key", "https://example.com/post", "Post", "", "", null, null,
                listOf("Kotlin"))), 100))
            val task = db.importTaskDao().getAllTasks().single()
            mode = AiAnalysisMode.DISABLE
            store.failArticle(feed, task, java.io.IOException("Offline"), true)
            val retry = db.importTaskDao().getTaskById(task.id)!!
            assertEquals("DIRECT", retry.intendedMode)
            assertEquals(listOf("Kotlin"), retry.assignedTags)
            mode = AiAnalysisMode.AICORE
            assertTrue(store.complete(feed, retry, article(retry.url)))
            assertEquals(listOf("Kotlin"), db.bookmarkDao().getBookmarkByUrl(retry.url)!!.entities)
        } finally { db.close() }
    }

    private fun article(url: String) = PreparedArticle(url, "HTML title", "Publisher", "Description", "Body", null, false)
}
