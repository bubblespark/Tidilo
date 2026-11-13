package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.BookmarkEntitySpelling
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.bookmarkSearchKey
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRepositoryTest {
    @Test fun notificationDeleteWaitsForStorageCompletion() = runBlocking {
        val dao = FakeImportTaskDao()
        dao.insertOrUpdate(waitingEntity("error").copy(status = "ERROR"))
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : ImportTaskDao by dao {
            override suspend fun deleteById(id: String) { began.complete(Unit); release.await(); dao.deleteById(id) }
        }
        val job = SupervisorJob()
        try {
            val repository = ImportRepository(taskDao = delayed, repositoryScope = CoroutineScope(coroutineContext + job))
            withTimeout(3000) { while (repository.errorTasks.value.isEmpty()) delay(10) }
            val pending = kotlinx.coroutines.CoroutineScope(coroutineContext).async { repository.deleteErrorTaskAndAwait("error") }
            began.await()
            assertFalse("Delete must remain in flight until Room finishes", pending.isCompleted)
            release.complete(Unit)
            pending.await()
            assertNull(dao.getTaskById("error"))
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun notificationRetryPropagatesStorageFailureToItsCaller() = runBlocking {
        val dao = FakeImportTaskDao()
        dao.insertOrUpdate(waitingEntity("error").copy(status = "ERROR"))
        val failing = object : ImportTaskDao by dao {
            override suspend fun updateStatus(id: String, status: String, statusText: String, errorMessage: String?) {
                throw java.io.IOException("storage unavailable")
            }
        }
        val job = SupervisorJob()
        try {
            val repository = ImportRepository(taskDao = failing, repositoryScope = CoroutineScope(coroutineContext + job +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }))
            val error = runCatching { repository.retryErrorTaskAndAwait("error") }.exceptionOrNull()
            assertEquals("storage unavailable", error?.message)
            assertEquals("ERROR", dao.getTaskById("error")?.status)
        } finally { job.cancelAndJoin() }
    }

    @Test fun foregroundStartupDirectlySavesQueuedAndInterruptedTasks() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        try {
            dao.insertAll(listOf(
                waitingEntity("ready").copy(status = "QUEUED", articlePrepared = true),
                waitingEntity("waiting").copy(status = "QUEUED", articlePrepared = true),
                waitingEntity("interrupted").copy(status = "PROCESSING", articlePrepared = true),
                waitingEntity("error").copy(status = "ERROR")
            ))
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job)
            )
            repository.onAppBecameActive()
            withTimeout(3000) { while (dao.getCount() > 1) delay(10) }
            assertEquals(setOf("ready", "waiting", "interrupted"), bookmarkDao.savedItems.map { it.id }.toSet())
            assertEquals("ERROR", dao.getTaskById("error")?.status)
        } finally { job.cancelAndJoin() }
    }

    @Test fun newImportSavesDirectlyWithAiEnabled() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        val completed = CompletableDeferred<Result<ImportOutcome>>()
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { directFixture(it) }
            )
            repository.importUrl("https://direct.example/article") { completed.complete(it) }
            val outcome = withTimeout(5000) { completed.await() }.getOrThrow()
            assertTrue("Expected a saved bookmark, got $outcome", outcome is ImportOutcome.BookmarkSaved)
            assertEquals("Parsed Description", bookmarkDao.savedItems.single().description)
            assertEquals(0, dao.getCount())
        } finally { job.cancelAndJoin() }
    }

    @Test fun invalidImportReportsOneDetailedOsError() = runBlocking {
        val job = SupervisorJob()
        val reported = mutableListOf<Throwable>()
        try {
            val repository = ImportRepository(taskDao = FakeImportTaskDao(),
                repositoryScope = CoroutineScope(coroutineContext + job), reportImportError = { reported.add(it) })
            assertTrue(repository.importUrl("not a URL").isFailure)
            assertEquals(listOf("Invalid HTTP/HTTPS URL"), reported.map { it.message })
        } finally { job.cancelAndJoin() }
    }

    @Test fun disabledRssSkipDeletesTaskWithoutErrorAndContinuesBatch() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        dao.insertAll(listOf(waitingEntity("skipped").copy(status = "QUEUED", articlePrepared = true, rssFeedId = "feed"),
            waitingEntity("following").copy(status = "QUEUED", articlePrepared = true, rssFeedId = "feed")))
        val rssAdapter = object : ImportRssAdapter {
            override suspend fun resumeAcquisitions() = Unit
            override suspend fun retryAcquisition(taskId: String) = Unit
            override suspend fun restoreTask(entity: ImportTaskEntity) = Unit
            override suspend fun validateRssWrite(url: String, bookmarkId: String, feedId: String, isNsfw: Boolean) {
                if (bookmarkId == "skipped") throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
            }
            override suspend fun recordRecoveredDirectSave(taskId: String) = Unit
        }
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                rssAdapter = rssAdapter
            )
            repository.onAppBecameActive()
            withTimeout(5000) {
                while (dao.getTasksByStatuses(listOf("QUEUED", "PROCESSING")).isNotEmpty() || bookmarkDao.savedItems.isEmpty()) delay(5)
            }
            assertNull(dao.getTaskById("skipped"))
            assertEquals(0, dao.getCount())
            assertEquals(listOf("following"), bookmarkDao.savedItems.map { it.id })
        } finally { job.cancelAndJoin() }
    }

    @Test fun disabledNewImportSavesDescriptionWithoutInsertingTask() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        val completed = CompletableDeferred<Unit>()
        val old = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { directFixture(it) }
            )
            repository.importUrl("https://direct.example/article") { completed.complete(Unit) }
            withTimeout(5000) { completed.await() }
            assertEquals(0, dao.getCount())
            assertEquals(0, dao.insertCount)
            val bookmark = bookmarkDao.savedItems.single()
            assertEquals("Parsed Description", bookmark.description)
            assertEquals("Parsed Title", bookmark.title)
            assertEquals(Tag.createEmpty(), bookmark.tag)
            assertEquals("https://direct.example/og.png", bookmark.ogImageUrl)
        } finally {
            me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(old)
            job.cancelAndJoin()
        }
    }

    @Test fun disabledReadyBatchNeverOpensModelAndPreservesRssMetadata() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        dao.insertAll(listOf(waitingEntity("normal").copy(status = "QUEUED", articlePrepared = true, description = "Normal"),
            waitingEntity("rss").copy(status = "QUEUED", articlePrepared = true, description = "RSS", rssFeedId = "feed", createdAt = 7)))
        val old = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job)
            )
            delay(10)
            assertEquals(2, dao.getCount())
            repository.onAppBecameActive()
            withTimeout(5000) { while (dao.getCount() > 0) delay(5) }
            assertEquals(2, bookmarkDao.savedItems.size)
            assertTrue(bookmarkDao.savedItems.all { it.tag == Tag.createEmpty() })
            assertEquals(7L, bookmarkDao.savedItems.single { it.rssFeedId != null }.createdAt)
        } finally {
            me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(old)
            job.cancelAndJoin()
        }
    }

    private fun directFixture(url: String) = me.bubblespark.tidilo.parser.ParsedWebPage(
        requestedUrl = url, finalUrl = url, statusCode = 200, statusMessage = "OK", contentType = "text/html",
        htmlLang = "en", title = "Parsed Title", canonicalUrl = url, metaTags = emptyList(),
        ogpTags = mapOf("og:description" to "Parsed Description", "og:image" to "/og.png"),
        twitterTags = emptyMap(), standardMetaTags = emptyMap(), jsonLdList = emptyList(), h1List = emptyList(),
        h2List = emptyList(), readabilityArticle = null, jsoupBodyText = "Body is not the Description",
        jsoupBodyCharCount = 27, fetchLatencyMs = 0, parseLatencyMs = 0, rawHtmlSizeBytes = 100)

    @Test fun disabledAcquisitionFailuresRemainRetryableAndSaveAfterRecovery() = runBlocking {
        for (failure in listOf("offline", "404", "503", "parse")) {
            val job = SupervisorJob()
            val dao = FakeImportTaskDao()
            val bookmarkDao = FakeBookmarkDao()
            var failed = true
            val done = CompletableDeferred<Boolean>()
            try {
                val repository = ImportRepository(
                    taskDao = dao,
                    bookmarkDao = bookmarkDao,
                    repositoryScope = CoroutineScope(coroutineContext + job),
                    articleAcquirer = {
                        if (failed) when (failure) {
                            "offline" -> throw java.io.IOException("Offline")
                            "parse" -> throw IllegalArgumentException("Parse failed")
                            else -> directFixture(it).copy(statusCode = failure.toInt())
                        } else directFixture(it)
                    }
                )
                repository.importUrl("https://direct.example/$failure") { done.complete(it.isFailure) }
                assertTrue(withTimeout(5000) { done.await() })
                val task = dao.getAllTasks().single()
                assertEquals("ERROR", task.status)
                assertEquals("Article acquisition failed", task.statusText)
                failed = false
                repository.retryErrorTask(task.id)
                withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
                assertEquals("Parsed Description", bookmarkDao.savedItems.single().description)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun disabledSaveFailureKeepsPreparedTaskUntilDurableRetryCompletes() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        var failSave = true
        var fetches = 0
        val saveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Boolean>()
        bookmarkDao.onInsertReplacingUrl = {
            if (failSave) error("Disk full")
            saveStarted.complete(Unit)
            release.await()
        }
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                transactionRunner = CountingRollbackTransactionRunner(bookmarkDao, dao),
                articleAcquirer = { fetches++; directFixture(it) }
            )
            repository.importUrl("https://direct.example/storage") { done.complete(it.isFailure) }
            assertTrue(withTimeout(5000) { done.await() })
            val task = dao.getAllTasks().single()
            assertEquals("Save failed", task.statusText)
            assertTrue(task.articlePrepared)
            failSave = false
            repository.retryErrorTask(task.id)
            withTimeout(5000) { saveStarted.await() }
            assertNotNull(dao.getTaskById(task.id))
            assertTrue(bookmarkDao.savedItems.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
            assertEquals(1, fetches)
            assertEquals("Parsed Description", bookmarkDao.savedItems.single().description)
        } finally { job.cancelAndJoin() }
    }

    @Test fun disabledRestartResumesInterruptedProcessingWithoutOpeningModel() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        dao.insertOrUpdate(waitingEntity("interrupted").copy(status = "PROCESSING", articlePrepared = true,
            description = "Persisted Description"))
        try {
            val repository = ImportRepository(
                taskDao = dao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job)
            )
            repository.onAppBecameActive()
            withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
            assertEquals("Persisted Description", bookmarkDao.savedItems.single().description)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun legacyRssBodyRequiresArticlePreparse() {
        val rss = ImportTask(id = "rss", url = "https://example.com/rss", title = "Feed title",
            status = TaskStatus.QUEUED, statusText = "", rssFeedId = "feed")
        assertTrue(ImportRepository.needsArticlePreparse(rss))
        assertTrue(ImportRepository.needsArticlePreparse(rss.copy(rssFeedId = null)))
    }

    @Test
    fun testRemoveAndRestoreErrorTaskLogic() {
        val error1 = ImportTask(id = "e1", url = "https://example.com/err1", title = "Err 1", status = TaskStatus.ERROR, statusText = "Failed", errorMessage = "404")
        val error2 = ImportTask(id = "e2", url = "https://example.com/err2", title = "Err 2", status = TaskStatus.ERROR, statusText = "Failed", errorMessage = "500")

        val list = mutableListOf(error1, error2)

        // Delete error1
        val index = list.indexOfFirst { it.id == "e1" }
        assertEquals(0, index)
        val removed = list.removeAt(index)
        assertEquals("Err 1", removed.title)
        assertEquals(listOf(error2), list)

        // Restore error1
        list.add(index.coerceIn(0, list.size), removed)
        assertEquals(listOf(error1, error2), list)
    }

    @Test
    fun testPrecomputedImportTaskMetadataFields() {
        val item = ImportTask(
            id = "preparsed-1",
            url = "https://example.com/article",
            title = "Example Article Title",
            status = TaskStatus.QUEUED,
            statusText = "Ready for AI Analysis",
            isNsfw = false,
            siteName = "Example Blog",
            description = "A concise article description",
            bodySnippet = "This is the extracted body text snippet used for instant AI tagging."
        )

        assertEquals("preparsed-1", item.id)
        assertEquals("https://example.com/article", item.url)
        assertEquals("Example Article Title", item.title)
        assertEquals("Example Blog", item.siteName)
        assertEquals("A concise article description", item.description)
        assertEquals("This is the extracted body text snippet used for instant AI tagging.", item.bodySnippet)
        assertEquals(false, item.isNsfw)

        // Verify prompt construction with precomputed snippets
        val promptJa = me.bubblespark.tidilo.service.GeminiTaggingService.buildPromptWithSnippets(
            url = item.url,
            siteName = item.siteName,
            title = item.title,
            description = item.description,
            bodySnippet = item.bodySnippet,
            language = me.bubblespark.tidilo.config.AppLanguage.JAPANESE
        )

        assertTrue(promptJa.contains("URL: https://example.com/article"))
        assertTrue(promptJa.contains("Site: Example Blog"))
        assertTrue(promptJa.contains("Title: Example Article Title"))
        assertTrue(promptJa.contains("Description: A concise article description"))
        assertTrue(promptJa.contains("Content: This is the extracted body text snippet used for instant AI tagging."))
        assertTrue(promptJa.contains("Language: Japanese."))
    }

    @Test
    fun testMapInferenceExceptionTransformsToAiCoreExecutionException() {
        val genAiBusy = com.google.mlkit.genai.common.GenAiException(
            "System busy",
            null,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY
        )
        val mappedBusy = me.bubblespark.tidilo.service.GeminiTaggingService.mapInferenceException(genAiBusy)
        assertEquals(com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY, mappedBusy.errorCode)
        assertTrue(mappedBusy.message?.contains("AICore Rate Limit Exceeded") == true)

        val genAiQuota = com.google.mlkit.genai.common.GenAiException(
            "Quota limit hit",
            null,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
        )
        val mappedQuota = me.bubblespark.tidilo.service.GeminiTaggingService.mapInferenceException(genAiQuota)
        assertEquals(com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED, mappedQuota.errorCode)
        assertTrue(mappedQuota.message?.contains("battery use quota exceeded") == true)

        val genAiBlocked = com.google.mlkit.genai.common.GenAiException(
            "Background not allowed",
            null,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
        )
        val mappedBlocked = me.bubblespark.tidilo.service.GeminiTaggingService.mapInferenceException(genAiBlocked)
        assertEquals(com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED, mappedBlocked.errorCode)
        assertTrue(mappedBlocked.message?.contains("background use blocked") == true)
    }

    @Test
    fun testFallbackBookmarkCreationContract() {
        val target = ImportTask(
            id = "import-test-1",
            url = "https://example.com/fallback-target",
            title = "Target Web Page",
            status = TaskStatus.PROCESSING,
            statusText = "Processing",
            isNsfw = true,
            siteName = "ExampleSite",
            description = "Parsed HTML description text",
            bodySnippet = "Extracted body snippet"
        )

        val fallbackBookmark = BookmarkItem(
            id = java.util.UUID.randomUUID().toString(),
            title = target.title,
            url = target.url,
            description = target.description,
            tag = Tag.createEmpty(),
            isNsfw = target.isNsfw,
        )

        assertEquals("Target Web Page", fallbackBookmark.title)
        assertEquals("https://example.com/fallback-target", fallbackBookmark.url)
        assertEquals("Parsed HTML description text", fallbackBookmark.description)
        assertNull(fallbackBookmark.tag.category)
        assertNull(fallbackBookmark.tag.subcategory)
        assertTrue(fallbackBookmark.tag.entities.isEmpty())
        assertTrue(fallbackBookmark.isNsfw)
    }

    @Test
    fun testImportTaskEntityMapping() {
        val task = ImportTask(
            id = "task-entity-test",
            url = "https://example.com/entity",
            title = "Entity Test",
            status = TaskStatus.QUEUED,
            statusText = "Ready for AI",
            errorMessage = null,
            assignedTags = listOf("tag1", "tag2"),
            createdAt = 123456789L,
            isNsfw = false,
            siteName = "Site",
            description = "Desc",
            bodySnippet = "Body"
        )

        val entity = ImportTaskEntity.fromImportTask(task)
        assertEquals("task-entity-test", entity.id)
        assertEquals("https://example.com/entity", entity.url)
        assertEquals(TaskStatus.QUEUED.name, entity.status)
        assertEquals(listOf("tag1", "tag2"), entity.assignedTags)

        val reconstructed = entity.toImportTask()
        assertEquals(task.id, reconstructed.id)
        assertEquals(task.url, reconstructed.url)
        assertEquals(task.title, reconstructed.title)
        assertEquals(task.status, reconstructed.status)
        assertEquals(task.statusText, reconstructed.statusText)
        assertEquals(task.assignedTags, reconstructed.assignedTags)
        assertEquals(task.createdAt, reconstructed.createdAt)
        assertEquals(task.isNsfw, reconstructed.isNsfw)
        assertEquals(task.siteName, reconstructed.siteName)
        assertEquals(task.description, reconstructed.description)
        assertEquals(task.bodySnippet, reconstructed.bodySnippet)
    }

    private fun waitingEntity(id: String) = ImportTaskEntity.fromImportTask(
        ImportTask(id = id, url = "https://example.com/$id", title = id, status = TaskStatus.QUEUED, statusText = "")
    )

    private class FakeImportTaskDao : ImportTaskDao {
        var insertCount = 0
        val tasksFlow = MutableStateFlow<List<ImportTaskEntity>>(emptyList())
        var unavailableIds = emptySet<String>()
        var beforeClaim: () -> Unit = {}

        override fun getAllTasksFlow(): Flow<List<ImportTaskEntity>> = tasksFlow

        override suspend fun getAllTasks(): List<ImportTaskEntity> = tasksFlow.value

        override fun getTasksByStatusesFlow(statuses: List<String>): Flow<List<ImportTaskEntity>> =
            tasksFlow.map { list -> list.filter { it.status in statuses } }

        override suspend fun getTasksByStatuses(statuses: List<String>): List<ImportTaskEntity> =
            tasksFlow.value.filter { it.status in statuses }

        override suspend fun getTaskById(id: String): ImportTaskEntity? =
            tasksFlow.value.find { it.id == id }

        override suspend fun getTaskByUrl(url: String): ImportTaskEntity? =
            tasksFlow.value.find { it.url == url }

        override suspend fun clearRssFeedId(id: String) {
            val task = getTaskById(id) ?: return
            insertOrUpdate(task.copy(rssFeedId = null))
        }

        override suspend fun insertOrUpdate(task: ImportTaskEntity) {
            insertCount++
            val current = tasksFlow.value.toMutableList()
            current.removeAll { it.id == task.id }
            current.add(task)
            tasksFlow.value = current
        }

        override suspend fun insertAll(tasks: List<ImportTaskEntity>) {
            val current = tasksFlow.value.toMutableList()
            val newIds = tasks.map { it.id }.toSet()
            current.removeAll { it.id in newIds }
            current.addAll(tasks)
            tasksFlow.value = current
        }

        override suspend fun update(task: ImportTaskEntity) = insertOrUpdate(task)

        override suspend fun updateStatus(id: String, status: String, statusText: String, errorMessage: String?) {
            val existing = getTaskById(id) ?: return
            insertOrUpdate(existing.copy(status = status, statusText = statusText, errorMessage = errorMessage))
        }

        override suspend fun rejectUnavailablePreparedRss(id: String): Int = 0

        override suspend fun claimForDirectSave(id: String, statusText: String): Int {
            beforeClaim()
            delay(1)
            if (id in unavailableIds) return 0
            val task = getTaskById(id) ?: return 0
            if (task.status !in listOf("QUEUED", "PROCESSING")) return 0
            updateStatus(id, "PROCESSING", statusText, null)
            return 1
        }


        override suspend fun deleteById(id: String) {
            tasksFlow.value = tasksFlow.value.filter { it.id != id }
        }

        override suspend fun deleteByIds(ids: List<String>) {
            tasksFlow.value = tasksFlow.value.filter { it.id !in ids }
        }

        override suspend fun deleteByStatus(status: String) {
            tasksFlow.value = tasksFlow.value.filter { it.status != status }
        }

        override suspend fun getCount(): Int = tasksFlow.value.size
    }

    @Test
    fun testImportingTasksTrackingLifecycle() = runBlocking {
        val fakeDao = FakeImportTaskDao()
        val repository = ImportRepository(context = null, taskDao = fakeDao)

        // 1. Initially importingTasks is empty
        assertEquals(0, repository.importingTasks.value.size)

        // 2. Start importing a URL
        val result = repository.importUrl("https://example.com/test", title = "Test Article")
        assertTrue(result.isSuccess)

        // 3. Immediately after calling importUrl, importingTasks tracks the task
        // (Wait for background coroutine to complete)
        delay(1200)

        // After import finishes, importingTasks should be cleaned up
        assertEquals(0, repository.importingTasks.value.size)
        // And taskDao should contain the task
        val tasks = fakeDao.getAllTasks()
        assertTrue(tasks.any { it.url.contains("example.com") })
    }

    @Test
    fun processDirectTaskInsertsBookmarkWithOccurrencesAndDeletesTaskInSingleFlatTransaction() = runBlocking {
        val job = SupervisorJob()
        val taskDao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        bookmarkDao.insertOrUpdate(
            BookmarkEntity(
                id = "seed",
                title = "Seed",
                url = "https://example.com/seed",
                description = "",
                entities = listOf("Kotlin"),
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        taskDao.insertOrUpdate(
            ImportTaskEntity.fromImportTask(
                ImportTask(
                    id = "direct-atomic",
                    url = "https://example.com/kotlin-coroutines",
                    title = "Kotlin Coroutines Guide",
                    status = TaskStatus.QUEUED,
                    statusText = "Waiting",
                    description = "Structured concurrency with Kotlin",
                    articlePrepared = true
                )
            )
        )
        val runner = CountingRollbackTransactionRunner(bookmarkDao, taskDao)
        try {
            val repository = ImportRepository(
                taskDao = taskDao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                transactionRunner = runner,
                candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
            )
            repository.onAppBecameActive()
            withTimeout(5000) { while (taskDao.getCount() != 0) delay(10) }

            assertEquals(1, runner.runCount)
            assertNull(taskDao.getTaskById("direct-atomic"))
            assertEquals(2, bookmarkDao.getCount())
            val saved = bookmarkDao.getBookmarkById("direct-atomic")
            assertNotNull(saved)
            assertEquals(listOf("Kotlin"), saved!!.entities)
            assertEquals(
                listOf(BookmarkEntityOccurrence("direct-atomic", 0, "Kotlin", bookmarkSearchKey("Kotlin"))),
                bookmarkDao.occurrences["direct-atomic"]
            )
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun processDirectTaskRollsBackBookmarkAndOccurrencesWhenDeleteFails() = runBlocking {
        val job = SupervisorJob()
        val baseTaskDao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        bookmarkDao.insertOrUpdate(
            BookmarkEntity(
                id = "seed",
                title = "Seed",
                url = "https://example.com/seed",
                description = "",
                entities = listOf("Kotlin"),
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        baseTaskDao.insertOrUpdate(
            ImportTaskEntity.fromImportTask(
                ImportTask(
                    id = "failing-task",
                    url = "https://example.com/kotlin-rollback",
                    title = "Kotlin Rollback",
                    status = TaskStatus.QUEUED,
                    statusText = "Waiting",
                    articlePrepared = true
                )
            )
        )
        val failingTaskDao = object : ImportTaskDao by baseTaskDao {
            override suspend fun deleteById(id: String) {
                if (id == "failing-task") {
                    throw IllegalStateException("Simulated delete failure after bookmark insert")
                }
                baseTaskDao.deleteById(id)
            }
        }
        val runner = CountingRollbackTransactionRunner(bookmarkDao, baseTaskDao)
        try {
            val repository = ImportRepository(
                taskDao = failingTaskDao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                transactionRunner = runner,
                candidateExtractor = EntityCandidateExtractor(StandardTextSegmenter())
            )
            repository.onAppBecameActive()
            withTimeout(5000) {
                while (baseTaskDao.getTaskById("failing-task")?.status != "ERROR") delay(10)
            }

            assertEquals(2, runner.runCount)
            assertNull(bookmarkDao.getBookmarkById("failing-task"))
            assertEquals(1, bookmarkDao.getCount())
            assertNull(bookmarkDao.occurrences["failing-task"])
            val retained = baseTaskDao.getTaskById("failing-task")!!
            assertEquals("ERROR", retained.status)
            assertEquals("Save failed", retained.statusText)
            assertTrue(retained.articlePrepared)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun injectedAdaptersReceiveDomainPolicyMediaRssAndNotificationCalls() = runBlocking {
        val job = SupervisorJob()
        val taskDao = FakeImportTaskDao()
        val bookmarkDao = FakeBookmarkDao()
        val events = mutableListOf<String>()
        var registerDuringInsert = false
        var fetchIconDuringInsert = false
        var ogDuringInsert = false

        val domainPolicyAdapter = object : ImportDomainPolicyAdapter {
            override suspend fun awaitReady() {
                events.add("awaitReady")
            }

            override fun isNsfwDomain(domain: String?): Boolean {
                events.add("isNsfwDomain:$domain")
                return domain == "nsfw.example"
            }

            override fun registerDomain(domain: String?, isNsfw: Boolean, displayName: String) {
                events.add("registerDomain:$domain:$displayName")
            }
        }
        val mediaCacheAdapter = object : ImportMediaCacheAdapter {
            override fun hasDomainIcon(domain: String): Boolean {
                events.add("hasDomainIcon:$domain")
                return false
            }

            override suspend fun fetchDomainIcon(
                url: String,
                appleTouchIconUrl: String?,
                faviconUrl: String?,
                knownCandidates: List<DomainIconCandidate>?
            ) {
                events.add("fetchDomainIcon:$url")
            }

            override suspend fun cacheOgImage(url: String, ogImageUrl: String, rssFeedId: String?) {
                events.add("cacheOgImage:$url:$ogImageUrl:$rssFeedId")
            }
        }
        val rssAdapter = object : ImportRssAdapter {
            override suspend fun resumeAcquisitions() {
                events.add("resumeAcquisitions")
            }

            override suspend fun retryAcquisition(taskId: String) {
                events.add("retryAcquisition:$taskId")
            }

            override suspend fun restoreTask(entity: ImportTaskEntity) {
                events.add("restoreTask:${entity.id}")
            }

            override suspend fun validateRssWrite(url: String, bookmarkId: String, feedId: String, isNsfw: Boolean) {
                events.add("validateRssWrite:$bookmarkId:$feedId:$isNsfw")
            }

            override suspend fun recordRecoveredDirectSave(taskId: String) {
                events.add("recordRecoveredDirectSave:$taskId")
            }
        }
        val notificationAdapter = object : ImportNotificationAdapter {
            override fun importError(error: Throwable) {
                events.add("importError:${error.message}")
            }

            override suspend fun publishCompletedRssRuns() {
                events.add("publishCompletedRssRuns")
            }
        }

        bookmarkDao.onInsertReplacingUrl = {
            registerDuringInsert = events.any { it.startsWith("registerDomain:") }
            fetchIconDuringInsert = events.any { it.startsWith("fetchDomainIcon:") }
            ogDuringInsert = events.any { it.startsWith("cacheOgImage:") }
        }

        try {
            val repository = ImportRepository(
                taskDao = taskDao,
                bookmarkDao = bookmarkDao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { directFixture(it).copy(ogpTags = mapOf("og:site_name" to "NSFW Site", "og:image" to "/hero.png")) },
                domainPolicyAdapter = domainPolicyAdapter,
                mediaCacheAdapter = mediaCacheAdapter,
                rssAdapter = rssAdapter,
                notificationAdapter = notificationAdapter
            )

            // 1. Direct import exercises domain policy + post-commit media caching
            val done = CompletableDeferred<Result<ImportOutcome>>()
            repository.importUrl("https://nsfw.example/post") { done.complete(it) }
            val outcome = withTimeout(5000) { done.await() }.getOrThrow()
            assertTrue(outcome is ImportOutcome.BookmarkSaved)
            assertTrue(bookmarkDao.getBookmarkByUrl("https://nsfw.example/post")!!.isNsfw)
            assertTrue(events.indexOf("awaitReady") in 0 until events.indexOf("isNsfwDomain:nsfw.example"))
            assertFalse(registerDuringInsert)
            assertFalse(fetchIconDuringInsert)
            assertFalse(ogDuringInsert)
            assertTrue(events.contains("registerDomain:nsfw.example:NSFW Site"))
            assertTrue(events.contains("hasDomainIcon:nsfw.example"))
            assertTrue(events.contains("fetchDomainIcon:https://nsfw.example/post"))
            withTimeout(3000) {
                while (events.none { it.startsWith("cacheOgImage:https://nsfw.example/post:") }) delay(10)
            }

            // 2. Invalid URL delegates error to ImportNotificationAdapter
            assertTrue(repository.importUrl("https://").isFailure)
            assertTrue(events.contains("importError:Invalid HTTP/HTTPS URL"))

            // 3. Unprepared RSS error retry delegates to ImportRssAdapter.retryAcquisition
            val unpreparedRss = ImportTask(
                id = "rss-err",
                url = "https://feed.example/1",
                title = "Feed Item",
                status = TaskStatus.ERROR,
                statusText = "Failed",
                rssFeedId = "feed-1",
                articlePrepared = false
            )
            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(unpreparedRss))
            repository.retryErrorTaskAndAwait("rss-err")
            assertTrue(events.contains("retryAcquisition:rss-err"))

            // 4. Restoring an RSS error task delegates to ImportRssAdapter.restoreTask
            repository.restoreErrorTask(unpreparedRss)
            withTimeout(3000) { while (!events.contains("restoreTask:rss-err")) delay(10) }

            // 5. Prepared RSS task in processDirectTask calls validateRssWrite, recordRecoveredDirectSave, and publishCompletedRssRuns
            taskDao.deleteById("rss-err")
            taskDao.insertOrUpdate(
                ImportTaskEntity.fromImportTask(
                    ImportTask(
                        id = "rss-ready",
                        url = "https://feed.example/ready",
                        title = "Ready RSS",
                        status = TaskStatus.QUEUED,
                        statusText = "Ready",
                        rssFeedId = "feed-1",
                        articlePrepared = true
                    )
                )
            )
            repository.onAppBecameActive()
            withTimeout(5000) {
                while (taskDao.getCount() != 0 || !events.contains("publishCompletedRssRuns")) delay(10)
            }
            assertTrue(events.contains("validateRssWrite:rss-ready:feed-1:false"))
            assertTrue(events.contains("recordRecoveredDirectSave:rss-ready"))
        } finally {
            job.cancelAndJoin()
        }
    }

    private class FakeBookmarkDao : StubBookmarkDao(onGetCount = { 0 }) {
        val bookmarks = linkedMapOf<String, BookmarkEntity>()
        val occurrences = linkedMapOf<String, MutableList<BookmarkEntityOccurrence>>()
        var onInsertReplacingUrl: (suspend (BookmarkEntity) -> Unit)? = null

        val savedItems: List<BookmarkItem>
            get() = bookmarks.values.map { it.toBookmarkItem() }

        override suspend fun getBookmarkById(id: String): BookmarkEntity? = bookmarks[id]

        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? =
            bookmarks.values.firstOrNull { it.url == url }

        override suspend fun clearRssFeedId(id: String) {
            val existing = bookmarks[id] ?: return
            bookmarks[id] = existing.copy(rssFeedId = null)
        }

        override suspend fun canonicalEntityValue(skeletonKey: String): String? =
            bookmarks.values.flatMap { it.entities }.firstOrNull { entitySkeletonKey(it) == skeletonKey }

        override suspend fun findSpellingsForKeys(keys: List<String>): List<BookmarkEntitySpelling> {
            val keySet = keys.toSet()
            return bookmarks.values.flatMap { bookmark ->
                bookmark.entities.mapNotNull { entity ->
                    val key = entitySkeletonKey(entity)
                    if (key in keySet) BookmarkEntitySpelling(key, entity) else null
                }
            }
        }

        override suspend fun insertReplacingUrl(bookmark: BookmarkEntity) {
            onInsertReplacingUrl?.invoke(bookmark)
            super.insertReplacingUrl(bookmark)
        }

        public override suspend fun insertRow(bookmark: BookmarkEntity) {
            bookmarks[bookmark.id] = bookmark
        }

        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) {
            bookmarks.forEach { this.bookmarks[it.id] = it }
        }

        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) {
            rows.forEach { row ->
                occurrences.getOrPut(row.bookmarkId) { mutableListOf() }.add(row)
            }
        }

        override suspend fun deleteOccurrences(id: String) {
            occurrences.remove(id)
        }

        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) {
            bookmarkIds.forEach { occurrences.remove(it) }
        }

        override suspend fun deleteById(id: String) {
            bookmarks.remove(id)
            occurrences.remove(id)
        }

        override suspend fun deleteByIdOrUrl(id: String, url: String) {
            val removedIds = bookmarks.values.filter { it.id == id || it.url == url }.map { it.id }
            removedIds.forEach {
                bookmarks.remove(it)
                occurrences.remove(it)
            }
        }

        override suspend fun getCount(): Int = bookmarks.size

        override suspend fun deleteAll() {
            bookmarks.clear()
            occurrences.clear()
        }
    }

    private class CountingRollbackTransactionRunner(
        private val bookmarkDao: FakeBookmarkDao,
        private val taskDao: FakeImportTaskDao
    ) : TransactionRunner {
        var runCount = 0
            private set
        private val mutex = kotlinx.coroutines.sync.Mutex()

        override suspend fun <R> run(block: suspend () -> R): R = mutex.withLock {
            runCount++
            val bookmarkSnapshot = LinkedHashMap(bookmarkDao.bookmarks)
            val occurrenceSnapshot = bookmarkDao.occurrences.mapValues { it.value.toMutableList() }
            val taskSnapshot = taskDao.tasksFlow.value.toList()
            try {
                block()
            } catch (t: Throwable) {
                bookmarkDao.bookmarks.clear()
                bookmarkDao.bookmarks.putAll(bookmarkSnapshot)
                bookmarkDao.occurrences.clear()
                occurrenceSnapshot.forEach { (k, v) -> bookmarkDao.occurrences[k] = v.toMutableList() }
                taskDao.tasksFlow.value = taskSnapshot
                throw t
            }
        }
    }
}

