package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.parser.PreparedArticle
import me.bubblespark.tidilo.parser.RssEntry
import me.bubblespark.tidilo.parser.RssFeedParser
import me.bubblespark.tidilo.parser.StandardClassification
import me.bubblespark.tidilo.parser.WebPageParser
import org.junit.Assert.*
import org.junit.Test

class RssIngestionStoreTest {
    @Test fun lateRssCompletionAndFailurePreserveTheTaskClaimedByManualRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        val repositoryJob = SupervisorJob()
        val manualAcquisitionStarted = CompletableDeferred<Unit>()
        val finishManualAcquisition = CompletableDeferred<Unit>()
        try {
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            val url = "https://example.com/manual-retry"
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            assertTrue(store.stage(feed, listOf(RssEntry("key", url, "Article", "", "", null, null, emptyList())), 100))
            val tasks = db.importTaskDao()
            val rssTask = tasks.getAllTasks().single()
            store.failArticle(feed, rssTask, java.io.IOException("Offline"), true)
            val repository = ImportRepository(
                taskDao = tasks, bookmarkDao = db.bookmarkDao(), database = db,
                repositoryScope = CoroutineScope(coroutineContext + repositoryJob),
                articleAcquirer = {
                    manualAcquisitionStarted.complete(Unit)
                    finishManualAcquisition.await()
                    WebPageParser.parseHtml(it, "<html><head><title>Manual retry</title></head><body>Article</body></html>")
                }
            )
            withTimeout(5000) {
                val retry = async { repository.retryManualImport(rssTask.id, url) }
                manualAcquisitionStarted.await()
                val claimed = checkNotNull(tasks.getTaskById(rssTask.id))
                assertNull(claimed.rssFeedId)
                assertEquals("PROCESSING", claimed.status)
                assertFalse(store.complete(feed, rssTask, article(url)))
                store.failArticle(feed, rssTask, java.io.IOException("Late RSS failure"), true)
                assertEquals(claimed, tasks.getTaskById(rssTask.id))
                assertNull(db.bookmarkDao().getBookmarkById(rssTask.id))
                finishManualAcquisition.complete(Unit)
                assertEquals(ImportOutcome.BookmarkSaved(rssTask.id, url), retry.await())
                assertNull(tasks.getTaskById(rssTask.id))
                val saved = checkNotNull(db.bookmarkDao().getBookmarkById(rssTask.id))
                assertNull(saved.rssFeedId)
                assertEquals(rssTask.title, saved.title)
            }
        } finally {
            repositoryJob.cancelAndJoin()
            db.close()
        }
    }

    @Test fun directRawCategoriesCleanOnceAndResolveBeforeLimit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity("existing", "Existing", "https://example.com/existing", "", entities = listOf("GitHub"), createdAt = 1, updatedAt = 1))
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", mode = "DIRECT")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            val rawCategories = (List(12) { " #git-hub " } + listOf("##C", "C", "New"))
                .joinToString("") { "<category>$it</category>" }
            val entry = RssFeedParser.parse("<rss><channel><item><link>https://example.com/post</link>$rawCategories</item></channel></rss>", feed.url).entries.single()
            assertTrue(store.stage(feed, listOf(entry), 100))
            val task = db.importTaskDao().getAllTasks().single()
            assertTrue(store.complete(feed, task, article(task.url),
                classification = StandardClassification(subcategory = Subcategory.FOOD)))
            assertEquals(listOf("GitHub", "#C", "C", "New"), dao.getBookmarkById(task.id)?.entities)
            val tag = dao.getBookmarkById(task.id)?.toBookmarkItem()?.tag
            assertNull(tag?.category)
            assertEquals("Food", tag?.subcategory)
            db.openHelper.readableDatabase.query("SELECT value FROM bookmark_entities WHERE bookmarkId = ? ORDER BY position", arrayOf(task.id)).use { cursor ->
                val values = mutableListOf<String>()
                while (cursor.moveToNext()) values += cursor.getString(0)
                assertEquals(listOf("GitHub", "#C", "C", "New"), values)
            }
        } finally { db.close() }
    }

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
            val store = RssIngestionStore(db)
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
