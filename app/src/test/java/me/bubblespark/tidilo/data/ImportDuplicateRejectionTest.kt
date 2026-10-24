package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.BookmarkDao
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.data.db.ImportNotificationDao
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.parser.ParsedWebPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ImportDuplicateRejectionTest {

    private class TestBookmarkDao : BookmarkDao {
        val bookmarks = mutableMapOf<String, BookmarkEntity>()

        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? =
            bookmarks.values.firstOrNull { it.url == url }

        override suspend fun getBookmarkById(id: String): BookmarkEntity? = bookmarks[id]

        override suspend fun clearRssFeedId(id: String) {
            val existing = bookmarks[id] ?: return
            bookmarks[id] = existing.copy(rssFeedId = null)
        }

        override suspend fun insertRow(bookmark: BookmarkEntity) {
            bookmarks[bookmark.id] = bookmark
        }

        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) {
            bookmarks.forEach { this.bookmarks[it.id] = it }
        }

        override suspend fun deleteByIdOrUrl(id: String, url: String) {
            bookmarks.remove(id)
            bookmarks.values.removeIf { it.url == url }
        }

        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) {}
        override suspend fun deleteOccurrences(id: String) {}
        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) {}
        override fun observeCollectionCounts(): Flow<CollectionCounts> = emptyFlow()
        override fun observeCount(query: SupportSQLiteQuery): Flow<Int> = emptyFlow()
        override suspend fun updateNote(id: String, note: String, updatedAt: Long) {}
        override suspend fun deleteById(id: String) { bookmarks.remove(id) }
        override suspend fun getCount(): Int = bookmarks.size
        override suspend fun deleteAll() { bookmarks.clear() }
        override fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> = emptyFlow()
        override fun observeById(id: String): Flow<BookmarkEntity?> = emptyFlow()
        override fun domainCounts(showNsfw: Boolean, source: Int): Flow<List<DomainCount>> = emptyFlow()
        override suspend fun distinctDomains(): List<String> = emptyList()
        override fun observeDistinctDomains(): Flow<List<String>> = emptyFlow()
        override suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean) {}
    }

    private class TestImportTaskDao : ImportTaskDao {
        val tasks = mutableMapOf<String, ImportTaskEntity>()
        private val tasksFlow = MutableStateFlow<List<ImportTaskEntity>>(emptyList())
        var onBeforeInsert: (suspend (ImportTaskEntity) -> Unit)? = null

        private fun emit() {
            tasksFlow.value = tasks.values.toList()
        }

        override suspend fun getTaskByUrl(url: String): ImportTaskEntity? =
            tasks.values.firstOrNull { it.url == url }

        override suspend fun getTaskById(id: String): ImportTaskEntity? = tasks[id]

        override suspend fun clearRssFeedId(id: String) {
            val existing = tasks[id] ?: return
            tasks[id] = existing.copy(rssFeedId = null)
            emit()
        }

        override suspend fun insertOrUpdate(task: ImportTaskEntity) {
            onBeforeInsert?.invoke(task)
            tasks[task.id] = task
            emit()
        }

        override suspend fun insertAll(tasks: List<ImportTaskEntity>) {
            tasks.forEach { this.tasks[it.id] = it }
            emit()
        }

        override suspend fun update(task: ImportTaskEntity) {
            tasks[task.id] = task
            emit()
        }

        override suspend fun updateStatus(id: String, status: String, statusText: String, errorMessage: String?) {
            val existing = tasks[id] ?: return
            tasks[id] = existing.copy(status = status, statusText = statusText, errorMessage = errorMessage)
            emit()
        }

        override fun getAllTasksFlow(): Flow<List<ImportTaskEntity>> = tasksFlow
        override suspend fun getAllTasks(): List<ImportTaskEntity> = tasks.values.toList()
        override fun getTasksByStatusesFlow(statuses: List<String>): Flow<List<ImportTaskEntity>> =
            tasksFlow.map { it.filter { task -> task.status in statuses } }
        override suspend fun getTasksByStatuses(statuses: List<String>): List<ImportTaskEntity> =
            tasks.values.filter { it.status in statuses }
        override suspend fun claimForAi(id: String, statusText: String): Int = 0
        override suspend fun rejectUnavailableRss() {}
        override suspend fun deleteById(id: String) {
            tasks.remove(id)
            emit()
        }

        override suspend fun deleteByIds(ids: List<String>) {
            tasks.keys.removeAll(ids.toSet())
            emit()
        }

        override suspend fun deleteByStatus(status: String) {
            tasks.values.removeIf { it.status == status }
            emit()
        }

        override suspend fun getCount(): Int = tasks.size
    }

    private class TestImportNotificationDao : ImportNotificationDao {
        val notifications = CopyOnWriteArrayList<ImportNotificationEntity>()
        private val idGen = java.util.concurrent.atomic.AtomicLong(1)
        private val state = MutableStateFlow<List<ImportNotificationEntity>>(emptyList())
        var onBeforeInsert: (suspend (ImportNotificationEntity) -> Unit)? = null

        private fun emit() {
            state.value = notifications.sortedWith(compareByDescending<ImportNotificationEntity> { it.createdAt }.thenByDescending { it.id })
        }

        override fun observeNotifications(): Flow<List<ImportNotificationEntity>> = state
        override fun observeUnreadCount(): Flow<Int> = state.map { it.count { item -> !item.isRead } }

        override suspend fun insert(notification: ImportNotificationEntity): Long {
            onBeforeInsert?.invoke(notification)
            val withId = if (notification.id == 0L) notification.copy(id = idGen.getAndIncrement()) else notification
            notifications.add(withId)
            emit()
            return withId.id
        }

        override suspend fun markAllAsRead() {
            val updated = notifications.map { it.copy(isRead = true) }
            notifications.clear()
            notifications.addAll(updated)
            emit()
        }

        override suspend fun deleteAll() {
            notifications.clear()
            emit()
        }

        override suspend fun deleteById(id: Long) {
            notifications.removeIf { it.id == id }
            emit()
        }

        override suspend fun updateResolvedUrl(submittedUrl: String, resolvedUrl: String, domain: String) {
            val updated = notifications.map {
                if (it.url == submittedUrl) it.copy(url = resolvedUrl, domain = domain) else it
            }
            notifications.clear()
            notifications.addAll(updated)
            emit()
        }
    }

    @Test
    fun testCaseA_PromoteCompletedRssBookmarkToLibrary() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        // Given: RSS bookmark already in library with AI analyzed tags
        val existing = BookmarkEntity(
            id = "b1",
            title = "RSS Article",
            url = "https://example.com/rss-article",
            description = "Desc",
            category = "Article",
            subcategory = "Tech",
            entities = listOf("AI"),
            createdAt = 1000L,
            updatedAt = 1000L,
            isAiSkipped = false,
            rssFeedId = "feed_123"
        )
        bookmarkDao.insertRow(existing)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User manually imports the same URL
        var callbackResult: Result<ImportTask>? = null
        repository.importUrl("https://example.com/rss-article") { callbackResult = it }

        // Then:
        // 1. rssFeedId in DB is cleared (promoted to library)
        val updated = bookmarkDao.getBookmarkById("b1")
        assertNotNull(updated)
        assertNull(updated?.rssFeedId)

        // 2. Not queued in taskDao
        assertEquals(0, taskDao.getCount())

        // 3. Recorded INFO notification with existingBookmarkId
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        val notif = notifications[0]
        assertEquals("INFO", notif.type)
        assertEquals("Promoted from RSS to Library", notif.message)
        assertEquals("b1", notif.existingBookmarkId)
        assertEquals("example.com", notif.domain)
        assertEquals("https://example.com/rss-article", notif.url)
    }

    @Test
    fun testCaseB_PromoteUnanalyzedRssBookmarkToReadyQueue() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        // Given: RSS bookmark in library but isAiSkipped = true (unanalyzed)
        val existing = BookmarkEntity(
            id = "b2",
            title = "Raw RSS Item",
            url = "https://example.com/raw-rss",
            description = "Desc",
            category = null,
            subcategory = null,
            entities = emptyList(),
            createdAt = 1000L,
            updatedAt = 1000L,
            isAiSkipped = true,
            rssFeedId = "feed_456"
        )
        bookmarkDao.insertRow(existing)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User imports the URL
        repository.importUrl("https://example.com/raw-rss")

        // Then:
        // 1. Queued in taskDao with rssFeedId = null (normal Ready group)
        val queued = taskDao.getTaskByUrl("https://example.com/raw-rss")
        assertNotNull(queued)
        assertNull(queued?.rssFeedId)
        assertEquals("READY", queued?.status)
        assertEquals(false, queued?.articlePrepared)

        // 2. Notification recorded
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        assertEquals("INFO", notifications[0].type)
        assertEquals("Promoted from RSS to Library", notifications[0].message)
        assertEquals("b2", notifications[0].existingBookmarkId)
    }

    @Test
    fun testCaseB_RepeatedImportWhilePendingInQueue_RejectsAsDuplicate() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val existing = BookmarkEntity(
            id = "b2",
            title = "Raw RSS Item",
            url = "https://example.com/raw-rss",
            description = "Desc",
            category = null,
            subcategory = null,
            entities = emptyList(),
            createdAt = 1000L,
            updatedAt = 1000L,
            isAiSkipped = true,
            rssFeedId = "feed_456"
        )
        bookmarkDao.insertRow(existing)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // First import enqueues promotion task
        repository.importUrl("https://example.com/raw-rss")
        assertEquals(1, taskDao.getCount())

        // Test each active queue status: importing again rejects as duplicate
        val activeStatuses = listOf("READY", "WAITING_AI", "PROCESSING_AI", "ERROR")
        for (status in activeStatuses) {
            val task = taskDao.getTaskByUrl("https://example.com/raw-rss")!!
            taskDao.updateStatus(task.id, status, "Test $status", null)

            // Second import while pending in queue
            repository.importUrl("https://example.com/raw-rss")

            // Count of tasks in queue remains 1 (no duplicate task created)
            assertEquals(1, taskDao.getCount())
            val latestNotification = notificationDao.observeNotifications().first().first()
            assertEquals("WARNING", latestNotification.type)
            assertEquals("Duplicate URL (Already in Queue)", latestNotification.message)
        }
    }


    @Test
    fun testCaseB_NotificationRefersToOriginalBookmarkIdWithNotePreserved() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val originalId = "b_user_note_123"
        val userNote = "Critical user research note"
        val originalCreatedAt = 123456789L

        val existing = BookmarkEntity(
            id = originalId,
            title = "RSS Unanalyzed",
            url = "https://example.com/note-test",
            description = "Brief snippet",
            note = userNote,
            createdAt = originalCreatedAt,
            updatedAt = originalCreatedAt,
            isAiSkipped = true,
            rssFeedId = "feed_1"
        )
        bookmarkDao.insertRow(existing)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User imports the URL for promotion
        repository.importUrl("https://example.com/note-test")

        // Verify notification stored the exact original ID
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        val notif = notifications.first()
        assertEquals("INFO", notif.type)
        assertEquals(originalId, notif.existingBookmarkId)

        // Verify looking up bookmark by the notification's existingBookmarkId still returns original note & timestamps
        val bookmark = bookmarkDao.getBookmarkById(notif.existingBookmarkId!!)
        assertNotNull(bookmark)
        assertEquals(userNote, bookmark?.note)
        assertEquals(originalCreatedAt, bookmark?.createdAt)
    }

    @Test
    fun testCaseC_MoveRssReadyTaskToNormalReady() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        // Given: Task currently in Ready - RSS queue
        val rssTask = ImportTaskEntity(
            id = "t1",
            url = "https://example.com/rss-queued",
            title = "Queued RSS",
            status = "READY",
            statusText = "Ready",
            createdAt = 1000L,
            rssFeedId = "feed_789"
        )
        taskDao.insertOrUpdate(rssTask)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User manually imports the URL
        repository.importUrl("https://example.com/rss-queued")

        // Then:
        // 1. rssFeedId cleared in taskDao (moved to top-level Ready)
        val updated = taskDao.getTaskById("t1")
        assertNotNull(updated)
        assertNull(updated?.rssFeedId)

        // 2. INFO notification recorded
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        assertEquals("INFO", notifications[0].type)
        assertEquals("Moved from RSS to Ready", notifications[0].message)
        assertNull(notifications[0].existingBookmarkId)
    }

    @Test
    fun testCaseD_RejectNormalLibraryDuplicate() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        // Given: Normal bookmark already in library (rssFeedId = null)
        val normalBookmark = BookmarkEntity(
            id = "b_normal",
            title = "Existing Web Bookmark",
            url = "https://example.com/existing",
            description = "Desc",
            createdAt = 1000L,
            updatedAt = 1000L,
            rssFeedId = null
        )
        bookmarkDao.insertRow(normalBookmark)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User imports the duplicate URL
        var callbackFailure: Boolean = false
        repository.importUrl("https://example.com/existing") {
            if (it.isFailure) callbackFailure = true
        }

        // Then:
        // 1. Rejected without adding to taskDao
        assertEquals(0, taskDao.getCount())
        assertTrue(callbackFailure)

        // 2. WARNING notification recorded with bookmark ID
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        val notif = notifications[0]
        assertEquals("WARNING", notif.type)
        assertEquals("Duplicate URL (Already in Library)", notif.message)
        assertEquals("b_normal", notif.existingBookmarkId)
    }

    @Test
    fun testCaseE_RejectNormalQueueDuplicate() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        // Given: Task already in normal Ready queue (rssFeedId = null)
        val normalTask = ImportTaskEntity(
            id = "t_normal",
            url = "https://example.com/already-queued",
            title = "Already Queued",
            status = "READY",
            statusText = "Ready",
            createdAt = 1000L,
            rssFeedId = null
        )
        taskDao.insertOrUpdate(normalTask)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User imports the duplicate URL
        var callbackFailure: Boolean = false
        repository.importUrl("https://example.com/already-queued") {
            if (it.isFailure) callbackFailure = true
        }

        // Then:
        // 1. Only original task remains in taskDao
        assertEquals(1, taskDao.getCount())
        assertTrue(callbackFailure)

        // 2. WARNING notification recorded
        val notifications = notificationDao.observeNotifications().first()
        assertEquals(1, notifications.size)
        val notif = notifications[0]
        assertEquals("WARNING", notif.type)
        assertEquals("Duplicate URL (Already in Queue)", notif.message)
        assertNull(notif.existingBookmarkId)
    }
    @Test
    fun testUrlNormalization_FragmentStripping_DetectsDuplicateInLibrary() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val existing = BookmarkEntity(
            id = "b_norm",
            title = "Article without fragment",
            url = "https://example.com/article",
            description = "Desc",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        bookmarkDao.insertRow(existing)

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined)
        )

        // When: User imports the URL with a fragment '#section'
        var failed = false
        repository.importUrl("https://example.com/article#section") {
            if (it.isFailure) failed = true
        }

        // Then: Pre-fetch normalization detects it as duplicate in library
        assertTrue(failed)
        assertEquals(0, taskDao.getCount())
        val notif = notificationDao.observeNotifications().first().first()
        assertEquals("WARNING", notif.type)
        assertEquals("Duplicate URL (Already in Library)", notif.message)
    }

    @Test
    fun testInFlightDuplicate_RejectedImmediately() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blockedScope = CoroutineScope(Dispatchers.IO)
        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = blockedScope,
            articleAcquirer = {
                gate.await()
                throw RuntimeException("Controlled test abortion")
            }
        )

        // First import starts and suspends deterministically in articleAcquirer
        val result1 = repository.importUrl("https://example.com/slow-item")
        assertTrue(result1.isSuccess)

        // Concurrent duplicate import of the exact same in-flight URL
        val result2 = repository.importUrl("https://example.com/slow-item")
        assertTrue(result2.isFailure)

        // Release the first import
        gate.complete(Unit)

        // Verify notification for in-flight queue duplicate
        val notifs = kotlinx.coroutines.withTimeout(3000) {
            notificationDao.observeNotifications().filter { list -> list.any { it.message == "Duplicate URL (Already in Queue)" } }.first()
        }
        assertTrue(notifs.isNotEmpty())
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

    @Test
    fun testConvergingUrls_ToUnanalyzedRssBookmark_OnlyOneTaskEnqueued() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val rssCanonicalUrl = "https://example.com/rss-article"
        val redirectUrl = "https://short.link/rss-redirect"

        // Given: Unanalyzed RSS bookmark in library with canonical URL
        val existing = BookmarkEntity(
            id = "b_rss_conv",
            title = "Unanalyzed RSS",
            url = rssCanonicalUrl,
            description = "Desc",
            isAiSkipped = true,
            rssFeedId = "feed_999",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        bookmarkDao.insertRow(existing)

        val redirectInFlightGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowRedirectToFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val redirectAboutToEnterCheckGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val directImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()
        val redirectImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()

        // Gate the task DAO insertion of the direct RSS promotion:
        // Before the direct promotion can commit its task into the DAO, release the competing redirect import.
        // Use a deterministic rendezvous: wait until the redirect import completes article acquisition
        // and arrives immediately before the post-redirect check.
        // If the early duplicate check and promotion are atomic inside runInDbOrLock,
        // the redirect import cannot interleave and will observe the promoted task in queue.
        // If they are not atomic, the redirect import would pass checkDuplicate and enqueue a competing task first.
        taskDao.onBeforeInsert = { task ->
            if (task.url == rssCanonicalUrl) {
                allowRedirectToFinish.complete(Unit)
                redirectAboutToEnterCheckGate.await()
                kotlinx.coroutines.yield()
            }
        }

        // Acquirer simulates redirectUrl resolving to rssCanonicalUrl while suspended in-flight
        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                if (url == redirectUrl) {
                    redirectInFlightGate.complete(Unit)
                    allowRedirectToFinish.await()
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
        kotlinx.coroutines.withTimeout(5000) {
            redirectInFlightGate.await()
        }

        // 2. Start direct canonical URL import:
        //    Evaluates unanalyzed RSS bookmark and enters atomic promotion block.
        //    taskDao.onBeforeInsert releases redirect URL before committing the direct task.
        val directCallResult = repository.importUrl(rssCanonicalUrl) {
            directImportFinished.complete(it)
        }
        assertTrue(directCallResult.isSuccess)

        // 3. Await completion of both competing imports
        val directResult = kotlinx.coroutines.withTimeout(5000) {
            directImportFinished.await()
        }
        assertTrue(directResult.isSuccess)

        val redirectResult = kotlinx.coroutines.withTimeout(5000) {
            redirectImportFinished.await()
        }
        assertTrue(redirectResult.isFailure)

        // 4. Verify only one task enqueued in total
        assertEquals(1, taskDao.getCount())
        val task = taskDao.getTaskByUrl(rssCanonicalUrl)
        assertNotNull(task)
        assertEquals("READY", task?.status)

        // 5. Verify redirect import was rejected as duplicate in queue
        val notifications = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list ->
                list.any { it.message == "Duplicate URL (Already in Queue)" }
            }.first()
        }
        assertTrue(notifications.isNotEmpty())
    }
    @Test
    fun testInFlightDuplicate_ResolvesUrl_UpdatesNotificationUrl() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val submittedUrl = "https://short.link/redirect-item"
        val resolvedUrl = "https://example.com/canonical-item"

        val acquirerGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val importFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                acquirerGate.await()
                createFakeParsedPage(url = url, finalUrl = resolvedUrl)
            }
        )

        // 1. First import begins and suspends in articleAcquirer
        val result1 = repository.importUrl(submittedUrl) {
            importFinished.complete(it)
        }
        assertTrue(result1.isSuccess)

        // 2. Second import of exact same in-flight URL is rejected immediately
        val result2 = repository.importUrl(submittedUrl)
        assertTrue(result2.isFailure)

        // Verify initial duplicate notification was recorded with submittedUrl
        val initialNotifs = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list -> list.any { it.url == submittedUrl } }.first()
        }
        assertEquals(1, initialNotifs.size)
        assertEquals(submittedUrl, initialNotifs.first().url)

        // 3. Release first import so it resolves to resolvedUrl
        acquirerGate.complete(Unit)

        val firstImportResult = kotlinx.coroutines.withTimeout(5000) {
            importFinished.await()
        }
        assertTrue(firstImportResult.isSuccess)

        // 4. Verify the notification's URL has been updated to the resolved canonical URL
        val updatedNotifs = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list -> list.any { it.url == resolvedUrl } }.first()
        }
        assertEquals(1, updatedNotifs.size)
        assertEquals(resolvedUrl, updatedNotifs.first().url)
        assertEquals("example.com", updatedNotifs.first().domain)
    }

    @Test
    fun testInFlightDuplicate_InsertAfterUpdateRace_StillResolvesNotificationUrl() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val submittedUrl = "https://short.link/redirect-item-race"
        val resolvedUrl = "https://example.com/canonical-item-race"

        val acquirerGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val insertGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val insertStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()

        // Intercept duplicate warning insert: pause it before it commits to simulated DAO
        notificationDao.onBeforeInsert = {
            insertStarted.complete(Unit)
            insertGate.await()
        }

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                acquirerGate.await()
                createFakeParsedPage(url = url, finalUrl = resolvedUrl)
            }
        )

        // 1. First import begins and suspends in articleAcquirer
        val result1 = repository.importUrl(submittedUrl) {
            firstImportFinished.complete(it)
        }
        assertTrue(result1.isSuccess)

        // 2. Second import of exact same in-flight URL is rejected immediately
        val result2 = repository.importUrl(submittedUrl)
        assertTrue(result2.isFailure)

        // Wait until duplicate warning insert starts and is suspended before committing
        kotlinx.coroutines.withTimeout(5000) { insertStarted.await() }

        // 3. First import proceeds to resolve URL and call updateResolvedUrl BEFORE duplicate insert commits
        acquirerGate.complete(Unit)

        val firstResult = kotlinx.coroutines.withTimeout(5000) { firstImportFinished.await() }
        assertTrue(firstResult.isSuccess)

        // 4. Now release the duplicate insert
        insertGate.complete(Unit)

        // 5. Verify the notification's URL has been updated to the resolved canonical URL
        val notifications = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list -> list.any { it.url == resolvedUrl } }.first()
        }
        assertEquals(1, notifications.size)
        assertEquals(resolvedUrl, notifications.first().url)
        assertEquals("example.com", notifications.first().domain)
    }

    @Test
    fun testSecondImportOfSameAlias_ResolvingToDifferentCanonicalUrl_ScopesNotificationCorrectly() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val aliasUrl = "https://short.link/reusable-alias"
        val canonicalUrl1 = "https://example.com/target-one"
        val canonicalUrl2 = "https://example.com/target-two"

        var currentTarget = canonicalUrl1
        val acquirerGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()
        val secondImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                if (url == aliasUrl && currentTarget == canonicalUrl2) {
                    acquirerGate.await()
                }
                createFakeParsedPage(url = url, finalUrl = currentTarget)
            }
        )

        // 1. First import: aliasUrl resolves to canonicalUrl1
        val res1 = repository.importUrl(aliasUrl) {
            firstImportFinished.complete(it)
        }
        assertTrue(res1.isSuccess)
        val firstResult = kotlinx.coroutines.withTimeout(5000) { firstImportFinished.await() }
        assertTrue(firstResult.isSuccess)
        assertEquals(canonicalUrl1, firstResult.getOrNull()?.url)

        // Await cleanup in finally block of first import
        kotlinx.coroutines.withTimeout(5000) {
            while (repository.inFlightResolvedUrls[aliasUrl] != null) {
                kotlinx.coroutines.yield()
            }
        }
        assertNull(repository.inFlightResolvedUrls[aliasUrl])

        // 2. Second import of the same alias URL, now resolving to canonicalUrl2
        currentTarget = canonicalUrl2
        val res2 = repository.importUrl(aliasUrl) {
            secondImportFinished.complete(it)
        }
        assertTrue(res2.isSuccess)

        // 3. Competing duplicate of aliasUrl arrives while second import is in-flight
        val dupResult = repository.importUrl(aliasUrl)
        assertTrue(dupResult.isFailure)

        // Verify duplicate notification was NOT recorded with stale canonicalUrl1
        val notifsAfterDup = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list ->
                list.any { it.message == "Duplicate URL (Already in Queue)" }
            }.first()
        }
        assertEquals(1, notifsAfterDup.size)
        // Must be aliasUrl (not stale canonicalUrl1)
        assertEquals(aliasUrl, notifsAfterDup.first().url)

        // 4. Release second import to resolve to canonicalUrl2
        acquirerGate.complete(Unit)
        val secondResult = kotlinx.coroutines.withTimeout(5000) { secondImportFinished.await() }
        assertTrue(secondResult.isSuccess)
        assertEquals(canonicalUrl2, secondResult.getOrNull()?.url)

        // 5. Verify the notification was updated to canonicalUrl2 (NOT canonicalUrl1)
        val finalNotifs = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list ->
                list.any { it.url == canonicalUrl2 }
            }.first()
        }
        assertEquals(1, finalNotifs.size)
        assertEquals(canonicalUrl2, finalNotifs.first().url)
        assertEquals("example.com", finalNotifs.first().domain)

        // Verify inFlightResolvedUrls is cleaned up after second import completes
        kotlinx.coroutines.withTimeout(5000) {
            while (repository.inFlightResolvedUrls[aliasUrl] != null) {
                kotlinx.coroutines.yield()
            }
        }
        assertNull(repository.inFlightResolvedUrls[aliasUrl])
    }

    @Test
    fun testDuplicateRegistration_PausedBetweenIdentificationAndRegistration_PreventsPrematureFinalization() = runBlocking {
        val bookmarkDao = TestBookmarkDao()
        val taskDao = TestImportTaskDao()
        val notificationDao = TestImportNotificationDao()

        val aliasUrl = "https://short.link/atomic-alias"
        val canonicalUrl = "https://example.com/atomic-target"

        val acquirerGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val identifiedLatch = java.util.concurrent.CountDownLatch(1)
        val allowRegistrationLatch = java.util.concurrent.CountDownLatch(1)
        val firstImportFinished = kotlinx.coroutines.CompletableDeferred<Result<ImportTask>>()

        val repository = ImportRepository(
            context = null,
            taskDao = taskDao,
            bookmarkDao = bookmarkDao,
            notificationDao = notificationDao,
            repositoryScope = CoroutineScope(Dispatchers.IO),
            articleAcquirer = { url ->
                acquirerGate.await()
                createFakeParsedPage(url = url, finalUrl = canonicalUrl)
            }
        )

        // 1. Start first import: aliasUrl
        val res1 = repository.importUrl(aliasUrl) {
            firstImportFinished.complete(it)
        }
        assertTrue(res1.isSuccess)

        // 2. Set test seam that pauses between identifying active import and registering its notification job
        repository.onDuplicateIdentified = { _ ->
            identifiedLatch.countDown()
            allowRegistrationLatch.await()
        }

        // 3. Launch duplicate import on a background dispatcher so it hits the seam and pauses while holding the lock
        val duplicateDeferred = async(Dispatchers.Default) {
            repository.importUrl(aliasUrl)
        }

        // Wait until duplicate import identifies the active import and pauses before registering
        assertTrue(identifiedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // 4. Release first import so it resolves to canonicalUrl and reaches finally
        acquirerGate.complete(Unit)

        // Give first import time to attempt finalization
        // Since duplicate import holds _importingTasks lock across identification and registration,
        // first import CANNOT finalize prematurely or clear inFlightResolvedUrls
        delay(100)

        // Verify that while duplicate is paused holding the lock, first import has not finalized:
        // The mapping for aliasUrl is still present and matches canonicalUrl!
        val inFlightTarget = repository.inFlightResolvedUrls[aliasUrl]
        assertNotNull("Mapping must not be cleared while duplicate registration is in progress", inFlightTarget)
        assertEquals(canonicalUrl, inFlightTarget?.realUrl)

        // 5. Now release registration in the duplicate import
        allowRegistrationLatch.countDown()

        // Wait for duplicate import call to finish (returns Result.failure)
        val duplicateResult = kotlinx.coroutines.withTimeout(5000) { duplicateDeferred.await() }
        assertTrue(duplicateResult.isFailure)

        // First import finishes completely
        val firstResult = kotlinx.coroutines.withTimeout(5000) { firstImportFinished.await() }
        assertTrue(firstResult.isSuccess)

        // 6. Verify the duplicate notification was correctly registered and updated with canonicalUrl
        val finalNotifs = kotlinx.coroutines.withTimeout(5000) {
            notificationDao.observeNotifications().filter { list ->
                list.any { it.url == canonicalUrl }
            }.first()
        }
        assertEquals(1, finalNotifs.size)
        assertEquals(canonicalUrl, finalNotifs.first().url)
        assertEquals("example.com", finalNotifs.first().domain)

        // Reset test seam
        repository.onDuplicateIdentified = null
    }
}