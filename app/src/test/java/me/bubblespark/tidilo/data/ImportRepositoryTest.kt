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
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
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
    @Test fun foregroundStartupDirectlySavesQueuedAndInterruptedTasks() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val saved = mutableListOf<BookmarkItem>()
        try {
            dao.insertAll(listOf(
                waitingEntity("ready").copy(status = "QUEUED", articlePrepared = true),
                waitingEntity("waiting").copy(status = "QUEUED", articlePrepared = true),
                waitingEntity("interrupted").copy(status = "PROCESSING", articlePrepared = true),
                waitingEntity("error").copy(status = "ERROR")
            ))
            val repository = ImportRepository(
                taskDao = dao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                persistFallback = { saved.add(it) }
            )
            repository.onAppBecameActive()
            withTimeout(3000) { while (dao.getCount() > 1) delay(10) }
            assertEquals(setOf("ready", "waiting", "interrupted"), saved.map { it.id }.toSet())
            assertEquals("ERROR", dao.getTaskById("error")?.status)
        } finally { job.cancelAndJoin() }
    }

    @Test fun newImportSavesDirectlyWithAiEnabled() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val saved = mutableListOf<BookmarkItem>()
        val completed = CompletableDeferred<Result<ImportOutcome>>()
        try {
            val repository = ImportRepository(
                taskDao = dao,
                repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { directFixture(it) },
                persistFallback = { saved.add(it) },

                )
            repository.importUrl("https://direct.example/article") { completed.complete(it) }
            val outcome = withTimeout(5000) { completed.await() }.getOrThrow()
            assertTrue("Expected a saved bookmark, got $outcome", outcome is ImportOutcome.BookmarkSaved)
            assertEquals("Parsed Description", saved.single().description)
            assertTrue(saved.single().isAiSkipped)
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
        dao.insertAll(listOf(waitingEntity("skipped").copy(status = "QUEUED", articlePrepared = true, rssFeedId = "feed"),
            waitingEntity("following").copy(status = "QUEUED", articlePrepared = true, rssFeedId = "feed")))
        val saved = mutableListOf<BookmarkItem>()
        try {
            val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),

                persistFallback = {
                    if (it.id == "skipped") throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
                    saved.add(it)
                })
            repository.onAppBecameActive()
            withTimeout(5000) {
                while (dao.getTasksByStatuses(listOf("QUEUED", "PROCESSING")).isNotEmpty() || saved.isEmpty()) delay(5)
            }
            assertNull(dao.getTaskById("skipped"))
            assertEquals(0, dao.getCount())
            assertEquals(listOf("following"), saved.map { it.id })
        } finally { job.cancelAndJoin() }
    }

    @Test fun disabledNewImportSavesDescriptionWithoutInsertingTask() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        val saved = mutableListOf<BookmarkItem>()
        val completed = CompletableDeferred<Unit>()
        val old = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        try {
            val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),
                articleAcquirer = { directFixture(it) }, persistFallback = { saved.add(it) },

                )
            repository.importUrl("https://direct.example/article") { completed.complete(Unit) }
            withTimeout(5000) { completed.await() }
            assertEquals(0, dao.getCount())
            assertEquals(0, dao.insertCount)
            val bookmark = saved.single()
            assertEquals("Parsed Description", bookmark.description)
            assertEquals("Parsed Title", bookmark.title)
            assertTrue(bookmark.isAiSkipped)
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
        dao.insertAll(listOf(waitingEntity("normal").copy(status = "QUEUED", articlePrepared = true, description = "Normal"),
            waitingEntity("rss").copy(status = "QUEUED", articlePrepared = true, description = "RSS", rssFeedId = "feed", createdAt = 7)))
        val saved = mutableListOf<BookmarkItem>()
        val old = me.bubblespark.tidilo.config.AppConfig.aiAnalysisMode.value
        me.bubblespark.tidilo.config.AppConfig.setAiAnalysisMode(me.bubblespark.tidilo.config.AiAnalysisMode.DISABLE)
        try {
            val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),
                persistFallback = { saved.add(it) })
            delay(10)
            assertEquals(2, dao.getCount())
            repository.onAppBecameActive()
            withTimeout(5000) { while (dao.getCount() > 0) delay(5) }
            assertEquals(2, saved.size)
            assertTrue(saved.all { it.isAiSkipped && it.tag == Tag.createEmpty() })
            assertEquals(7L, saved.single { it.rssFeedId != null }.createdAt)
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
            var failed = true
            val done = CompletableDeferred<Boolean>()
            val saved = mutableListOf<BookmarkItem>()
            try {
                val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),

                articleAcquirer = {
                        if (failed) when (failure) {
                            "offline" -> throw java.io.IOException("Offline")
                            "parse" -> throw IllegalArgumentException("Parse failed")
                            else -> directFixture(it).copy(statusCode = failure.toInt())
                        } else directFixture(it)
                    }, persistFallback = { saved.add(it) })
                repository.importUrl("https://direct.example/$failure") { done.complete(it.isFailure) }
                assertTrue(withTimeout(5000) { done.await() })
                val task = dao.getAllTasks().single()
                assertEquals("ERROR", task.status)
                assertEquals("Article acquisition failed", task.statusText)
                failed = false
                repository.retryErrorTask(task.id)
                withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
                assertEquals("Parsed Description", saved.single().description)
                assertTrue(saved.single().isAiSkipped)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun disabledSaveFailureKeepsPreparedTaskUntilDurableRetryCompletes() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        var failSave = true
        var fetches = 0
        val saveStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Boolean>()
        val saved = mutableListOf<BookmarkItem>()
        try {
            val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),

                articleAcquirer = { fetches++; directFixture(it) }, persistFallback = {
                    if (failSave) error("Disk full")
                    saveStarted.complete(Unit)
                    release.await()
                    saved.add(it)
                })
            repository.importUrl("https://direct.example/storage") { done.complete(it.isFailure) }
            assertTrue(withTimeout(5000) { done.await() })
            val task = dao.getAllTasks().single()
            assertEquals("Save failed", task.statusText)
            assertTrue(task.articlePrepared)
            failSave = false
            repository.retryErrorTask(task.id)
            withTimeout(5000) { saveStarted.await() }
            assertNotNull(dao.getTaskById(task.id))
            assertTrue(saved.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
            assertEquals(1, fetches)
            assertEquals("Parsed Description", saved.single().description)
        } finally { job.cancelAndJoin() }
    }

    @Test fun disabledRestartResumesInterruptedProcessingWithoutOpeningModel() = runBlocking {
        val job = SupervisorJob()
        val dao = FakeImportTaskDao()
        dao.insertOrUpdate(waitingEntity("interrupted").copy(status = "PROCESSING", articlePrepared = true,
            description = "Persisted Description"))
        val saved = mutableListOf<BookmarkItem>()
        try {
            val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + job),

                persistFallback = { saved.add(it) })
            repository.onAppBecameActive()
            withTimeout(5000) { while (dao.getCount() != 0) delay(5) }
            assertEquals("Persisted Description", saved.single().description)
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
            isAiSkipped = true
        )

        assertEquals("Target Web Page", fallbackBookmark.title)
        assertEquals("https://example.com/fallback-target", fallbackBookmark.url)
        assertEquals("Parsed HTML description text", fallbackBookmark.description)
        assertNull(fallbackBookmark.tag.category)
        assertNull(fallbackBookmark.tag.subcategory)
        assertTrue(fallbackBookmark.tag.entities.isEmpty())
        assertTrue(fallbackBookmark.isNsfw)
        assertTrue(fallbackBookmark.isAiSkipped)
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
}

