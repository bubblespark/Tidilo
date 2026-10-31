package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.BookmarkEntityTags
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.DomainCount
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportDuplicateRoomTest {
    @Test fun disabledRssSkipRemovesTaskOnlyAfterNestedTransactionRollsBack() = runBlocking {
        val job = kotlinx.coroutines.SupervisorJob()
        val feed = me.bubblespark.tidilo.data.db.RssFeedEntity("skip-feed", "https://example.com/feed", "Feed", mode = "AI")
        database.rssDao().putFeed(feed)
        val task = ImportTask(id = "skip-task", url = "https://example.com/skipped-rss", title = "RSS",
            rssFeedId = feed.id, articlePrepared = true, status = TaskStatus.READY, statusText = "Ready")
        database.importTaskDao().insertOrUpdate(ImportTaskEntity.fromImportTask(task))
        var attempted = false
        try {
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = database.bookmarkDao(),
                database = database, repositoryScope = CoroutineScope(coroutineContext + job), isAppInForeground = { true },
                aiAnalysisMode = { me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE },
                openModel = { error("Disabled RSS must not initialize AICore") }, persistFallback = { bookmark ->
                    attempted = true
                    database.withTransaction {
                        database.bookmarkDao().insertOrUpdate(BookmarkEntity.fromBookmarkItem(bookmark))
                        throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
                    }
                })
            repository.promoteTaskToProcessing(task.id)
            withTimeout(5000) {
                while (!attempted || database.importTaskDao().getTaskById(task.id)?.status in listOf("READY", "WAITING_AI", "PROCESSING_AI")) delay(10)
            }
            assertNull(database.importTaskDao().getTaskById(task.id))
            assertNull(database.bookmarkDao().getBookmarkByUrl(task.url))
        } finally { job.cancel(); job.join() }
    }

    @Test fun completedRssPromotionReportsBookmarkSavedAfterCommitWithoutReanalysis() = runBlocking {
        val job = kotlinx.coroutines.SupervisorJob()
        val outcome = CompletableDeferred<Result<ImportOutcome>>()
        val finished = CompletableDeferred<Unit>()
        var callbackInTransaction = true
        val original = BookmarkEntity("completed", "Analyzed RSS", "https://example.com/completed", "AI Description",
            category = "Article", subcategory = "Tech", entities = listOf("Kotlin"), note = "User note",
            rssFeedId = "feed", createdAt = 7, updatedAt = 9)
        database.bookmarkDao().insertOrUpdate(original)
        try {
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = database.bookmarkDao(),
                database = database, repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { error("Completed promotion must not acquire HTML") },
                openModel = { error("Completed promotion must not initialize AICore") })
            repository.onEnteringFinalization = { finished.complete(Unit) }
            repository.importUrl(original.url) {
                callbackInTransaction = database.inTransaction()
                outcome.complete(it)
            }
            withTimeout(5000) { finished.await() }
            assertEquals(ImportOutcome.BookmarkSaved(original.id, original.url), outcome.await().getOrThrow())
            assertFalse(callbackInTransaction)
            assertEquals(0, database.importTaskDao().getCount())
            assertEquals(original.copy(rssFeedId = null), database.bookmarkDao().getBookmarkByUrl(original.url))
        } finally { job.cancel(); job.join() }
    }

    @Test fun disabledRssPromotionReportsSuccessOnlyAfterCommit() = kotlinx.coroutines.runBlocking {
        val job = kotlinx.coroutines.SupervisorJob()
        val outcome = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Unit>()
        val url = "https://example.com/promoted-direct"
        database.importTaskDao().insertOrUpdate(ImportTaskEntity.fromImportTask(ImportTask(id = "promoted",
            url = url, title = "Prepared", description = "Description", articlePrepared = true,
            rssFeedId = "feed", status = TaskStatus.READY, statusText = "Ready")))
        try {
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = database.bookmarkDao(),
                database = database, repositoryScope = CoroutineScope(coroutineContext + job),
                aiAnalysisMode = { me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE },
                persistFallback = { bookmark -> database.withTransaction { database.bookmarkDao().insertOrUpdate(BookmarkEntity.fromBookmarkItem(bookmark)) } })
            repository.onEnteringFinalization = { finished.complete(Unit) }
            repository.importUrl(url) { outcome.complete(it.isSuccess && !database.inTransaction()) }
            withTimeout(5000) { finished.await() }
            assertTrue("Success must be reported after the promotion transaction commits", outcome.await())
            assertEquals(0, database.importTaskDao().getCount())
        } finally { job.cancel(); job.join() }
    }

    @Test fun disabledReadyNestedSaveFailureRemainsAnErrorInsteadOfRepeating() = kotlinx.coroutines.runBlocking {
        val job = kotlinx.coroutines.SupervisorJob()
        val attempted = CompletableDeferred<Unit>()
        database.importTaskDao().insertOrUpdate(ImportTaskEntity.fromImportTask(ImportTask(id = "ready-failure",
            url = "https://example.com/ready-failure", title = "Prepared", articlePrepared = true,
            status = TaskStatus.READY, statusText = "Ready")))
        try {
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = database.bookmarkDao(),
                database = database, repositoryScope = CoroutineScope(coroutineContext + job), isAppInForeground = { true },
                aiAnalysisMode = { me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE },
                openModel = { error("AICore must not open") }, persistFallback = {
                    attempted.complete(Unit)
                    database.withTransaction { throw android.database.sqlite.SQLiteFullException("Simulated disk full") }
                })
            repository.promoteTaskToProcessing("ready-failure")
            withTimeout(5000) { attempted.await() }
            withTimeout(2000) { while (database.importTaskDao().getTaskById("ready-failure")?.status != "ERROR") delay(10) }
            assertEquals("Save failed", database.importTaskDao().getTaskById("ready-failure")!!.statusText)
        } finally { job.cancel(); job.join() }
    }

    @Test fun disabledNestedSaveFailureLeavesDurableErrorTask() = kotlinx.coroutines.runBlocking {
        val job = kotlinx.coroutines.SupervisorJob()
        val finished = CompletableDeferred<Unit>()
        val callback = CompletableDeferred<Result<ImportOutcome>>()
        try {
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = database.bookmarkDao(),
                database = database, repositoryScope = CoroutineScope(coroutineContext + job),
                aiAnalysisMode = { me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE },
                articleAcquirer = { createFakeParsedPage(it) }, persistFallback = {
                    database.withTransaction { throw android.database.sqlite.SQLiteFullException("Simulated disk full") }
                })
            repository.onEnteringFinalization = { finished.complete(Unit) }
            repository.importUrl("https://example.com/nested-failure") { callback.complete(it) }
            withTimeout(5000) { finished.await() }
            assertTrue(callback.await().isFailure)
            val error = database.importTaskDao().getAllTasks().single()
            assertEquals("ERROR", error.status)
            assertEquals("Save failed", error.statusText)
            assertTrue(error.articlePrepared)
        } finally { job.cancel(); job.join() }
    }

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

    private class HookableBookmarkDao(private val delegate: BookmarkDao) : BookmarkDao() {
        var onGetBookmarkByUrl: ((String) -> Unit)? = null
        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? {
            onGetBookmarkByUrl?.invoke(url)
            return delegate.getBookmarkByUrl(url)
        }
        override fun observeCollectionCounts(): Flow<me.bubblespark.tidilo.data.CollectionCounts> = delegate.observeCollectionCounts()
        override fun observeCount(query: SupportSQLiteQuery): Flow<Int> = delegate.observeCount(query)
        override suspend fun getBookmarkById(id: String): BookmarkEntity? = delegate.getBookmarkById(id)
        override suspend fun clearRssFeedId(id: String) = delegate.clearRssFeedId(id)
        override suspend fun insertRow(bookmark: BookmarkEntity) = error("Unused primitive")
        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) = error("Unused primitive")
        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) = error("Unused primitive")
        override suspend fun deleteOccurrences(id: String) = error("Unused primitive")
        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) = error("Unused primitive")
        override suspend fun canonicalEntityValue(skeletonKey: String): String? = delegate.canonicalEntityValue(skeletonKey)
        override suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<BookmarkEntityTags> = error("Unused primitive")
        override suspend fun updateEntityRows(rows: List<me.bubblespark.tidilo.data.db.BookmarkEntityTagUpdate>) = error("Unused primitive")
        override suspend fun updateNote(id: String, note: String, updatedAt: Long) = delegate.updateNote(id, note, updatedAt)
        override suspend fun deleteById(id: String) = delegate.deleteById(id)
        override suspend fun deleteByIdOrUrl(id: String, url: String) = delegate.deleteByIdOrUrl(id, url)
        override suspend fun getCount(): Int = delegate.getCount()
        override suspend fun deleteAll() = delegate.deleteAll()
        override fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = delegate.timeline(showNsfw)
        override fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = delegate.byDomain(domain, showNsfw)
        override fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity> = delegate.search(query)
        override fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> = delegate.tagCounts(query)
        override fun observeById(id: String): Flow<BookmarkEntity?> = delegate.observeById(id)
        override fun domainCounts(showNsfw: Boolean, source: Int): Flow<List<DomainCount>> = delegate.domainCounts(showNsfw, source)
        override suspend fun distinctDomains(): List<String> = delegate.distinctDomains()
        override fun observeDistinctDomains(): Flow<List<String>> = delegate.observeDistinctDomains()
        override suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean) = delegate.updateDomainNsfw(domain, isNsfw)
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
        database.bookmarkDao().insertOrUpdate(existing)

        val redirectInFlightGate = CompletableDeferred<Unit>()
        val allowRedirectGate = CompletableDeferred<Unit>()
        val redirectReachedTransactionBoundary = CompletableDeferred<Unit>()
        val redirectReachedDaoLookup = CompletableDeferred<Unit>()
        var directTaskInserted = false
        val directImportFinished = CompletableDeferred<Result<ImportOutcome>>()
        val redirectImportFinished = CompletableDeferred<Result<ImportOutcome>>()

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
            aiAnalysisMode = { me.bubblespark.tidilo.config.AiAnalysisMode.AICORE },
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
