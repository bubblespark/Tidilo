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
import me.bubblespark.tidilo.data.db.BookmarkDao
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportNotificationDao
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.parser.ParsedWebPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var onAfterInsert: (suspend (ImportTaskEntity) -> Unit)? = null

        override suspend fun insertOrUpdate(task: ImportTaskEntity) {
            onBeforeInsert?.invoke(task)
            delegate.insertOrUpdate(task)
            onAfterInsert?.invoke(task)
        }
    }

    private class HookableBookmarkDao(private val delegate: BookmarkDao) : BookmarkDao by delegate {
        var onGetBookmarkByUrl: ((String) -> Unit)? = null

        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? {
            onGetBookmarkByUrl?.invoke(url)
            return delegate.getBookmarkByUrl(url)
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
        val redirectReachedTransactionBoundary = CompletableDeferred<Unit>()
        val redirectReachedDaoLookup = CompletableDeferred<Unit>()
        var directTaskInserted = false
        val directImportFinished = CompletableDeferred<Result<ImportTask>>()
        val redirectImportFinished = CompletableDeferred<Result<ImportTask>>()

        val hookableTaskDao = HookableImportTaskDao(database.importTaskDao())
        val hookableBookmarkDao = HookableBookmarkDao(database.bookmarkDao())

        // Hook bookmarkDao lookup: called when redirect enters checkDuplicate inside runInDbOrLock
        hookableBookmarkDao.onGetBookmarkByUrl = { url ->
            if (url == rssCanonicalUrl && redirectReachedTransactionBoundary.isCompleted) {
                assertTrue("Redirect reached DAO lookup before direct promotion was committed!", directTaskInserted)
                redirectReachedDaoLookup.complete(Unit)
            }
        }

        // Gate direct RSS promotion task insertion:
        // Direct import holds active Room database.withTransaction.
        // It releases the redirect import, confirms redirect reaches transaction boundary,
        // proves redirect is blocked from DAO lookup by the active transaction,
        // and only then commits the direct task.
        hookableTaskDao.onBeforeInsert = { task ->
            if (task.url == rssCanonicalUrl) {
                allowRedirectGate.complete(Unit)
                withTimeout(5000) {
                    redirectReachedTransactionBoundary.await()
                }
                // Yield to allow redirect coroutine to attempt acquiring database.withTransaction
                yield()
                // Prove competing check is blocked by active Room transaction
                assertFalse("Competing check bypassed active Room transaction!", redirectReachedDaoLookup.isCompleted)
            }
        }

        hookableTaskDao.onAfterInsert = { task ->
            if (task.url == rssCanonicalUrl) {
                directTaskInserted = true
            }
        }

        val repository = ImportRepository(
            context = context,
            database = database,
            taskDao = hookableTaskDao,
            bookmarkDao = hookableBookmarkDao,
            notificationDao = database.importNotificationDao(),
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                if (url == redirectUrl) {
                    redirectInFlightGate.complete(Unit)
                    allowRedirectGate.await()
                    createFakeParsedPage(url = redirectUrl, finalUrl = rssCanonicalUrl)
                } else {
                    createFakeParsedPage(url = url)
                }
            }
        )

        // Deterministic test seam: fires immediately before redirect enters runInDbOrLock database transaction
        repository.onEnteringTransaction = { url ->
            if (url == rssCanonicalUrl && allowRedirectGate.isCompleted) {
                redirectReachedTransactionBoundary.complete(Unit)
            }
        }

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
        //    hookableTaskDao.onBeforeInsert performs handshake confirming redirect is blocked.
        val directCallResult = repository.importUrl(rssCanonicalUrl) {
            directImportFinished.complete(it)
        }
        assertTrue(directCallResult.isSuccess)

        // 3. Await completion of both competing imports
        val directResult = withTimeout(5000) {
            directImportFinished.await()
        }
        assertTrue(directResult.isSuccess)

        withTimeout(5000) {
            redirectReachedDaoLookup.await()
        }

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
