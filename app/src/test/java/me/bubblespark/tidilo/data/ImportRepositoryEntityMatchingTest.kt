package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.db.BookmarkDao
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.BookmarkEntitySpelling
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.data.db.ImportNotificationDao
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.parser.ParsedWebPage
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ImportRepositoryEntityMatchingTest {

    private class TestBookmarkDao : BookmarkDao() {
        val bookmarks = mutableMapOf<String, BookmarkEntity>()
        var findCanonicalEntitySpellingsInvocations = 0
        var spellingsOverride: (suspend (Collection<String>) -> Map<String, String>)? = null

        override suspend fun findCanonicalEntitySpellings(keys: Collection<String>): Map<String, String> {
            findCanonicalEntitySpellingsInvocations++
            spellingsOverride?.let { return it(keys) }
            return super.findCanonicalEntitySpellings(keys)
        }

        override suspend fun findSpellingsForKeys(keys: List<String>): List<BookmarkEntitySpelling> {
            val keySet = keys.toSet()
            return bookmarks.values.flatMap { bookmark ->
                bookmark.entities.mapNotNull { entity ->
                    val key = entitySkeletonKey(entity)
                    if (key in keySet) BookmarkEntitySpelling(key, entity) else null
                }
            }
        }

        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? =
            bookmarks.values.firstOrNull { it.url == url }

        override suspend fun getBookmarkById(id: String): BookmarkEntity? = bookmarks[id]

        override suspend fun clearRssFeedId(id: String) {
            val existing = bookmarks[id] ?: return
            bookmarks[id] = existing.copy(rssFeedId = null)
        }

        public override suspend fun insertRow(bookmark: BookmarkEntity) {
            bookmarks[bookmark.id] = bookmark
        }

        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) {
            bookmarks.forEach { this.bookmarks[it.id] = it }
        }

        override suspend fun deleteByIdOrUrl(id: String, url: String) {
            bookmarks.remove(id)
            bookmarks.values.removeIf { it.url == url }
        }

        override suspend fun canonicalEntityValue(skeletonKey: String): String? =
            bookmarks.values.flatMap { it.entities }.firstOrNull { entitySkeletonKey(it) == skeletonKey }

        override suspend fun collectionsForReplacement(id: String, url: String): List<String> = emptyList()
        override suspend fun insertCollectionMembership(row: me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef) = Unit
        override suspend fun toggleLike(id: String, now: Long) = Unit
        override suspend fun recordBrowserOpen(id: String, now: Long) = Unit
        override suspend fun recordDetailView(id: String, now: Long) = Unit
        override suspend fun recordSummaryPeek(id: String, now: Long) = Unit
        override suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<me.bubblespark.tidilo.data.db.BookmarkEntityTags> = emptyList()
        override suspend fun updateEntityRows(rows: List<me.bubblespark.tidilo.data.db.BookmarkEntityTagUpdate>) {}
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
        override suspend fun rejectUnavailablePreparedRss(id: String): Int = 0

        override suspend fun claimForDirectSave(id: String, statusText: String): Int {
            val task = tasks[id] ?: return 0
            if (task.status !in listOf("QUEUED", "PROCESSING")) return 0
            updateStatus(id, "PROCESSING", statusText, null)
            return 1
        }

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

        override suspend fun updateResolvedUrl(submittedUrl: String, resolvedUrl: String, domain: String) {
            val updated = notifications.map {
                if (it.url == submittedUrl) it.copy(url = resolvedUrl, domain = domain) else it
            }
            notifications.clear()
            notifications.addAll(updated)
            emit()
        }
    }

    private fun fakeModel(onClose: () -> Unit = {}): GenerativeModel = java.lang.reflect.Proxy.newProxyInstance(
        GenerativeModel::class.java.classLoader,
        arrayOf(GenerativeModel::class.java)
    ) { _, method, _ ->
        if (method.name == "close") onClose()
        null
    } as GenerativeModel

    private fun createBookmarkEntity(
        id: String = "b1",
        title: String = "Test",
        url: String = "https://example.com/test",
        description: String = "Desc",
        entities: List<String> = emptyList(),
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L,
        rssFeedId: String? = null
    ): BookmarkEntity = BookmarkEntity(
        id = id,
        title = title,
        url = url,
        description = description,
        entities = entities,
        createdAt = createdAt,
        updatedAt = updatedAt,
        rssFeedId = rssFeedId
    )

    private fun createFakeParsedPage(url: String, title: String = "Test Page", description: String? = null): ParsedWebPage {
        return ParsedWebPage(
            requestedUrl = url,
            finalUrl = url,
            statusCode = 200,
            statusMessage = "OK",
            contentType = "text/html",
            htmlLang = "en",
            title = title,
            canonicalUrl = url,
            metaTags = emptyList(),
            ogpTags = emptyMap(),
            twitterTags = emptyMap(),
            standardMetaTags = description?.let { mapOf("description" to it) } ?: emptyMap(),
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
    fun directSaveAttachesMatchingLibraryEntities() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        val notifications = TestImportNotificationDao()

        // Existing bookmark in library with Kotlin and Android entities
        bookmarks.insertRow(
            createBookmarkEntity(
                id = "existing1",
                title = "Android Dev",
                url = "https://example.com/existing",
                entities = listOf("Kotlin", "Android")
            )
        )

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            notificationDao = notifications,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = {
                createFakeParsedPage(it, title = "Learn Kotlin and Android").copy(
                    jsonLdScripts = listOf("""{"@context":"https://schema.org","@type":"TechArticle","url":"$it"}""")
                )
            },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/learn") { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)

        assertEquals(1, savedItems.size)
        assertEquals(listOf("Kotlin", "Android"), savedItems.first().tag.entities)

        val persisted = bookmarks.getBookmarkByUrl("https://example.com/learn")
        assertNotNull(persisted)
        assertEquals(listOf("Kotlin", "Android"), persisted!!.entities)
        assertEquals("Article", persisted.category)
        assertEquals("Tech", persisted.subcategory)
    }

    @Test
    fun noMatchKeepsEmptyTag() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(
            createBookmarkEntity(
                id = "existing1",
                title = "Rust Dev",
                url = "https://example.com/rust",
                entities = listOf("Rust", "Python")
            )
        )

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Learn Kotlin and Android") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/learn") { outcomeDeferred.complete(it) }

        withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()

        assertEquals(1, savedItems.size)
        assertTrue(savedItems.first().tag.entities.isEmpty())
    }

    @Test
    fun truncatesToTenInEncounterOrder() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()

        val tagNames = (1..15).map { "Tag%02d".format(it) }
        bookmarks.insertRow(
            createBookmarkEntity(
                id = "existing1",
                title = "Existing tags",
                url = "https://example.com/tags",
                entities = tagNames
            )
        )

        val title = tagNames.joinToString(" ")
        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = title) },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/tags-test") { outcomeDeferred.complete(it) }

        withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()

        assertEquals(1, savedItems.size)
        val expected = (1..10).map { "Tag%02d".format(it) }
        assertEquals(expected, savedItems.first().tag.entities)
    }

    @Test
    fun descriptionIsUsedWhenTitleHasNoMatch() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(
            createBookmarkEntity(
                id = "existing1",
                title = "Kotlin Library",
                url = "https://example.com/kt",
                entities = listOf("Kotlin")
            )
        )

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Unrelated Title", description = "Article covering Kotlin development") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/desc-test") { outcomeDeferred.complete(it) }

        withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()

        assertEquals(1, savedItems.size)
        assertEquals(listOf("Kotlin"), savedItems.first().tag.entities)
    }

    @Test
    fun daoFailureFallsBackToEmptyTag() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.spellingsOverride = { throw IllegalStateException("Database disk image is malformed") }

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Learn Kotlin") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/dao-failure") { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)
        assertEquals(1, savedItems.size)
        assertTrue(savedItems.first().tag.entities.isEmpty())
    }

    @Test
    fun candidateExtractorFailureFallsBackToEmptyTag() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(createBookmarkEntity(id = "existing", entities = listOf("Kotlin")))

        val throwingSegmenter = object : TextSegmenter {
            override fun segment(text: String): List<String> = throw RuntimeException("Segmentation crashed")
        }

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Learn Kotlin") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(throwingSegmenter)
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/extractor-failure") { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)
        assertEquals(1, savedItems.size)
        assertTrue(savedItems.first().tag.entities.isEmpty())
    }

    @Test
    fun cancellationIsRethrown() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.spellingsOverride = { throw CancellationException("Operation cancelled by user") }

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Learn Kotlin") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        var callbackInvoked = false
        repository.importUrl("https://example.com/cancel-test") { callbackInvoked = true }

        // Cancellation should stop persistence cleanly; no bookmark is saved, no error task created
        assertEquals(0, savedItems.size)
        assertEquals(0, bookmarks.getCount())
        assertEquals(0, tasks.getCount())
        assertTrue(!callbackInvoked)
    }

    @Test
    fun nullDaoReturnsEmptyTag() = runBlocking {
        val tasks = TestImportTaskDao()

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = null,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Learn Kotlin") },
            persistFallback = { savedItems.add(it) },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/null-dao") { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)
        assertEquals(1, savedItems.size)
        assertTrue(savedItems.first().tag.entities.isEmpty())
    }

    @Test
    fun pathCoverage_importBookmarkWithDisable() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(createBookmarkEntity(id = "b1", title = "Compose", url = "https://example.com/c", entities = listOf("Compose")))

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Jetpack Compose Tutorial") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/compose-tutorial") { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)
        assertEquals(1, savedItems.size)
        assertEquals(listOf("Compose"), savedItems.first().tag.entities)
    }

    @Test
    fun pathCoverage_movedFromRssToReadyPromotion() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(createBookmarkEntity(id = "b1", title = "Android", url = "https://example.com/a", entities = listOf("Android")))

        val rssUrl = "https://example.com/rss-item"
        tasks.insertOrUpdate(
            ImportTaskEntity(
                id = "rss-task-1",
                url = rssUrl,
                title = "Android 15 Features",
                status = "QUEUED",
                statusText = "Ready",
                createdAt = 1000L,
                rssFeedId = "feed-99",
                articlePrepared = true
            )
        )

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Android 15 Features") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        val outcomeDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl(rssUrl) { outcomeDeferred.complete(it) }

        val outcome = withTimeout(5000) { outcomeDeferred.await() }.getOrThrow()
        assertTrue(outcome is ImportOutcome.BookmarkSaved)
        assertEquals("rss-task-1", (outcome as ImportOutcome.BookmarkSaved).bookmarkId)
        assertEquals(1, savedItems.size)
        assertEquals(listOf("Android"), savedItems.first().tag.entities)
        assertEquals(0, tasks.getCount())
    }

    @Test
    fun pathCoverage_processDirectTask() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(createBookmarkEntity(id = "b1", title = "Room", url = "https://example.com/r", entities = listOf("Room")))

        val directUrl = "https://example.com/room-guide"
        val task = ImportTask(
            id = "direct-1",
            url = directUrl,
            title = "Room Persistence Guide",
            status = TaskStatus.QUEUED,
            statusText = "Waiting",
            articlePrepared = true
        )
        tasks.insertOrUpdate(ImportTaskEntity.fromImportTask(task))

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        repository.onAppBecameActive()

        withTimeout(5000) {
            while (tasks.getCount() != 0) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(1, savedItems.size)
        assertEquals(listOf("Room"), savedItems.first().tag.entities)
    }

    @Test
    fun matchingRunsOncePerSave() = runBlocking {
        val bookmarks = TestBookmarkDao()
        val tasks = TestImportTaskDao()
        bookmarks.insertRow(createBookmarkEntity(id = "b1", title = "Kotlin", url = "https://example.com/k", entities = listOf("Kotlin")))

        val savedItems = mutableListOf<BookmarkItem>()
        val repository = ImportRepository(
            taskDao = tasks,
            bookmarkDao = bookmarks,
            repositoryScope = CoroutineScope(Dispatchers.Unconfined),
            articleAcquirer = { createFakeParsedPage(it, title = "Kotlin Guide") },
            persistFallback = {
                savedItems.add(it)
                bookmarks.insertRow(BookmarkEntity.fromBookmarkItem(it))
            },
            candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
        )

        // 1. Direct save via DISABLE branch
        val directDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl("https://example.com/direct-save") { directDeferred.complete(it) }

        val directOutcome = withTimeout(5000) { directDeferred.await() }.getOrThrow()
        assertTrue(directOutcome is ImportOutcome.BookmarkSaved)
        val directSaved = directOutcome as ImportOutcome.BookmarkSaved
        assertEquals(directSaved.bookmarkId, savedItems[0].id)
        assertEquals(1, bookmarks.findCanonicalEntitySpellingsInvocations)

        // 2. RSS promotion via MovedFromRssToReady branch
        bookmarks.findCanonicalEntitySpellingsInvocations = 0
        val rssUrl = "https://example.com/rss-promo"
        tasks.insertOrUpdate(
            ImportTaskEntity(
                id = "rss-task-2",
                url = rssUrl,
                title = "Kotlin in Action",
                status = "QUEUED",
                statusText = "Ready",
                createdAt = 1000L,
                rssFeedId = "feed-100",
                articlePrepared = true
            )
        )

        val promoDeferred = CompletableDeferred<Result<ImportOutcome>>()
        repository.importUrl(rssUrl) { promoDeferred.complete(it) }

        val promoOutcome = withTimeout(5000) { promoDeferred.await() }.getOrThrow()
        assertTrue(promoOutcome is ImportOutcome.BookmarkSaved)
        val promoSaved = promoOutcome as ImportOutcome.BookmarkSaved
        assertEquals("rss-task-2", promoSaved.bookmarkId)
        assertEquals(promoSaved.bookmarkId, savedItems[1].id)
        assertEquals(1, bookmarks.findCanonicalEntitySpellingsInvocations)
    }
}
