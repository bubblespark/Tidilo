package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.parser.ParsedWebPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportDuplicateRoomTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createFakeParsedPage(url: String, finalUrl: String = url): ParsedWebPage {
        return ParsedWebPage(
            requestedUrl = url,
            finalUrl = finalUrl,
            statusCode = 200,
            statusMessage = "OK",
            contentType = "text/html",
            htmlLang = "en",
            title = "Test Page",
            canonicalUrl = finalUrl,
            metaTags = emptyList(),
            ogpTags = emptyMap(),
            twitterTags = emptyMap(),
            standardMetaTags = emptyMap(),
            jsonLdList = emptyList(),
            h1List = emptyList(),
            h2List = emptyList(),
            readabilityArticle = null,
            jsoupBodyText = "Body text",
            jsoupBodyCharCount = 9,
            fetchLatencyMs = 10L,
            parseLatencyMs = 5L,
            rawHtmlSizeBytes = 100
        )
    }

    private class HookableImportTaskDao(private val delegate: ImportTaskDao) : ImportTaskDao by delegate {
        var onBeforeInsert: (suspend (ImportTaskEntity) -> Unit)? = null

        override suspend fun insertOrUpdate(task: ImportTaskEntity) {
            onBeforeInsert?.invoke(task)
            delegate.insertOrUpdate(task)
        }
    }

    @Test
    fun testConvergingUrls_RoomBackedTransaction_OnlyOneTaskEnqueued() = runBlocking {
        val rssCanonicalUrl = "https://example.com/rss-article"
        val redirectUrl = "https://short.link/rss-redirect"

        // Given: Unanalyzed RSS bookmark in library with canonical URL
        val existing = BookmarkEntity(
            id = "b_rss_room_conv",
            title = "Unanalyzed RSS Room",
            url = rssCanonicalUrl,
            description = "Desc",
            isAiSkipped = true,
            rssFeedId = "feed_room_999",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        database.bookmarkDao().insertRow(existing)

        val redirectInFlightGate = CompletableDeferred<Unit>()
        val allowRedirectGate = CompletableDeferred<Unit>()
        val redirectAboutToEnterCheckGate = CompletableDeferred<Unit>()
        val directImportFinished = CompletableDeferred<Result<ImportTask>>()
        val redirectImportFinished = CompletableDeferred<Result<ImportTask>>()

        val hookableTaskDao = HookableImportTaskDao(database.importTaskDao())

        // Gate the task DAO insertion of the direct RSS promotion:
        // Before committing the direct task into Room inside database.withTransaction, release the competing redirect import.
        // Use a deterministic rendezvous: wait until the redirect import completes article acquisition
        // and arrives immediately before the post-redirect check.
        // If runInDbOrLock is properly atomic via Room withTransaction, the redirect import is serialized
        // and observes the promoted task in queue.
        hookableTaskDao.onBeforeInsert = { task ->
            if (task.url == rssCanonicalUrl) {
                allowRedirectGate.complete(Unit)
                redirectAboutToEnterCheckGate.await()
                yield()
            }
        }

        val repository = ImportRepository(
            context = context,
            database = database,
            taskDao = hookableTaskDao,
            bookmarkDao = database.bookmarkDao(),
            notificationDao = database.importNotificationDao(),
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                if (url == redirectUrl) {
                    redirectInFlightGate.complete(Unit)
                    allowRedirectGate.await()
                    redirectAboutToEnterCheckGate.complete(Unit)
                    createFakeParsedPage(url = redirectUrl, finalUrl = rssCanonicalUrl)
                } else {
                    createFakeParsedPage(url = url)
                }
            }
        )

        // 1. Start import of converging redirect URL: suspends in articleAcquirer
        val redirectCallResult = repository.importUrl(redirectUrl) {
            redirectImportFinished.complete(it)
        }
        assertTrue(redirectCallResult.isSuccess)

        // Wait until redirect import is in-flight inside articleAcquirer
        withTimeout(5000) {
            redirectInFlightGate.await()
        }

        // 2. Start direct canonical URL import:
        //    Evaluates unanalyzed RSS bookmark and enters atomic Room withTransaction promotion block.
        //    hookableTaskDao.onBeforeInsert releases redirect URL while this promotion transaction is active.
        val directCallResult = repository.importUrl(rssCanonicalUrl) {
            directImportFinished.complete(it)
        }
        assertTrue(directCallResult.isSuccess)

        // 3. Await completion of both competing imports
        val directResult = withTimeout(5000) {
            directImportFinished.await()
        }
        assertTrue(directResult.isSuccess)

        val redirectResult = withTimeout(5000) {
            redirectImportFinished.await()
        }
        assertTrue(redirectResult.isFailure)

        // 4. Verify only one task enqueued in Room database
        assertEquals(1, database.importTaskDao().getCount())
        val task = database.importTaskDao().getTaskByUrl(rssCanonicalUrl)
        assertNotNull(task)
        assertEquals("READY", task?.status)

        // 5. Verify redirect import was rejected as duplicate in queue
        val notifications = withTimeout(5000) {
            database.importNotificationDao().observeNotifications().first()
        }
        assertTrue(notifications.any { it.message == "Duplicate URL (Already in Queue)" })
    }
}
