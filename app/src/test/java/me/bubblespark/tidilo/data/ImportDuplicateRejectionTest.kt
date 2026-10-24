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

        private fun emit() {
            state.value = notifications.sortedWith(compareByDescending<ImportNotificationEntity> { it.createdAt }.thenByDescending { it.id })
        }

        override fun observeNotifications(): Flow<List<ImportNotificationEntity>> = state
        override fun observeUnreadCount(): Flow<Int> = state.map { it.count { item -> !item.isRead } }

        override suspend fun insert(notification: ImportNotificationEntity): Long {
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
}
