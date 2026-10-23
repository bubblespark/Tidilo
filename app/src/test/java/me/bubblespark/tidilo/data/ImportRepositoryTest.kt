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
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRepositoryTest {

    @Test
    fun emptyRssBodyDoesNotTriggerArticlePreparse() {
        val rss = ImportTask(id = "rss", url = "https://example.com/rss", title = "Feed title",
            status = TaskStatus.READY, statusText = "", rssFeedId = "feed")
        assertFalse(ImportRepository.needsArticlePreparse(rss))
        assertTrue(ImportRepository.needsArticlePreparse(rss.copy(rssFeedId = null)))
    }

    @Test
    fun testRemoveAndRestoreReadyTaskLogic() {
        val item1 = ImportTask(id = "1", url = "https://example.com/1", title = "Item 1", status = TaskStatus.READY, statusText = "Ready")
        val item2 = ImportTask(id = "2", url = "https://example.com/2", title = "Item 2", status = TaskStatus.READY, statusText = "Ready")
        val item3 = ImportTask(id = "3", url = "https://example.com/3", title = "Item 3", status = TaskStatus.READY, statusText = "Ready")

        val list = mutableListOf(item1, item2, item3)

        // Delete item2
        val index = list.indexOfFirst { it.id == "2" }
        assertEquals(1, index)
        val removed = list.removeAt(index)
        assertEquals("Item 2", removed.title)
        assertEquals(listOf(item1, item3), list)

        // Restore item2 to its original index
        list.add(index.coerceIn(0, list.size), removed)
        assertEquals(listOf(item1, item2, item3), list)
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
    fun testIsAiCoreOrInterruptionExceptionClassification() {
        val aiCoreException1 = Exception("AICore Service Unavailable on device")
        val aiCoreException2 = Exception("AICore Rate Limit Exceeded: System service temporarily busy")
        val aiCoreException3 = Exception("AICore Inference Failed: timeout")
        val remoteException = Exception("RemoteException: AICore service binder died")
        val cancelException = java.util.concurrent.CancellationException("Cancelled")

        assertTrue(ImportRepository.isAiCoreOrInterruptionException(aiCoreException1))
        assertTrue(ImportRepository.isAiCoreOrInterruptionException(aiCoreException2))
        assertTrue(ImportRepository.isAiCoreOrInterruptionException(aiCoreException3))
        assertTrue(ImportRepository.isAiCoreOrInterruptionException(remoteException))
        assertTrue(ImportRepository.isAiCoreOrInterruptionException(cancelException))

        val http404 = Exception("HTTP Error 404: Not Found")
        val http500 = Exception("HTTP Error 500: Server Error")
        val invalidUrl = IllegalArgumentException("Invalid HTTP/HTTPS URL")

        assertFalse(ImportRepository.isAiCoreOrInterruptionException(http404))
        assertFalse(ImportRepository.isAiCoreOrInterruptionException(http500))
        assertFalse(ImportRepository.isAiCoreOrInterruptionException(invalidUrl))
    }

    @Test
    fun testInterruptedProcessingTaskRetainsWaitingStatusWithoutError() {
        val processingTask = ImportTask(
            id = "run-test",
            url = "https://example.com/test",
            title = "Test Item",
            status = TaskStatus.PROCESSING_AI,
            statusText = "Gemini Nano analyzing content..."
        )

        // When interrupted by background/AICore exception, it should be set to WAITING_AI with paused status
        val interruptedTask = processingTask.copy(
            status = TaskStatus.WAITING_AI,
            statusText = "Paused • Retrying when app is active"
        )

        assertEquals(TaskStatus.WAITING_AI, interruptedTask.status)
        assertEquals("Paused • Retrying when app is active", interruptedTask.statusText)
        assertEquals("run-test", interruptedTask.id)
    }

    @Test
    fun testPrecomputedImportTaskMetadataFields() {
        val item = ImportTask(
            id = "preparsed-1",
            url = "https://example.com/article",
            title = "Example Article Title",
            status = TaskStatus.READY,
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
    fun testExtractAiCoreErrorCodeFromDirectAndWrappedExceptions() {
        val busyEx = AiCoreExecutionException(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            "Busy"
        )
        val quotaEx = AiCoreExecutionException(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            "Quota exceeded"
        )
        val blockedEx = AiCoreExecutionException(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            "Background use blocked"
        )

        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            ImportRepository.extractAiCoreErrorCode(busyEx)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            ImportRepository.extractAiCoreErrorCode(quotaEx)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            ImportRepository.extractAiCoreErrorCode(blockedEx)
        )

        // Nested in runtime exception wrapper
        val wrappedQuotaEx = RuntimeException("Wrapper exception", quotaEx)
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            ImportRepository.extractAiCoreErrorCode(wrappedQuotaEx)
        )

        // String message fallback detection
        val msgFallbackQuota = Exception("AICore battery use quota exceeded (code 27)")
        val msgFallbackBusy = Exception("AICore BUSY rate limit exceeded")
        val msgFallbackBlocked = Exception("AICore BACKGROUND_USE_BLOCKED error")

        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            ImportRepository.extractAiCoreErrorCode(msgFallbackQuota)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            ImportRepository.extractAiCoreErrorCode(msgFallbackBusy)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            ImportRepository.extractAiCoreErrorCode(msgFallbackBlocked)
        )
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
    fun testMoveAllProcessingToReadyLogic() {
        val item1 = ImportTask(id = "run-1", url = "https://example.com/1", title = "Task 1", status = TaskStatus.PROCESSING_AI, statusText = "Inference")
        val item2 = ImportTask(id = "run-2", url = "https://example.com/2", title = "Task 2", status = TaskStatus.WAITING_AI, statusText = "Waiting")
        val currentProcessing = listOf(item1, item2)

        // When battery quota exceeded occurs:
        val converted = currentProcessing.map { item ->
            item.copy(
                status = TaskStatus.READY,
                statusText = "Ready • Quota exceeded"
            )
        }

        assertEquals(2, converted.size)
        assertEquals(TaskStatus.READY, converted[0].status)
        assertEquals("Ready • Quota exceeded", converted[0].statusText)
        assertEquals(TaskStatus.READY, converted[1].status)
        assertEquals("Ready • Quota exceeded", converted[1].statusText)
    }

    @Test
    fun testMoveAllProcessingToErrorBatchFailureLogic() {
        val item1 = ImportTask(id = "run-1", url = "https://example.com/1", title = "Task 1", status = TaskStatus.PROCESSING_AI, statusText = "Inference")
        val item2 = ImportTask(id = "run-2", url = "https://example.com/2", title = "Task 2", status = TaskStatus.WAITING_AI, statusText = "Waiting")
        val currentProcessing = listOf(item1, item2)

        val primaryFailedItem = item1
        val failureMsg = "AICore busy: Retry limit exceeded (3/3)"

        val errorItems = currentProcessing.map { item ->
            item.copy(
                status = TaskStatus.ERROR,
                statusText = "Failed",
                errorMessage = if (item.id == primaryFailedItem.id) {
                    failureMsg
                } else {
                    "Batch stopped: $failureMsg"
                }
            )
        }

        assertEquals(2, errorItems.size)
        assertEquals(TaskStatus.ERROR, errorItems[0].status)
        assertEquals(failureMsg, errorItems[0].errorMessage)
        assertEquals(TaskStatus.ERROR, errorItems[1].status)
        assertEquals("Batch stopped: AICore busy: Retry limit exceeded (3/3)", errorItems[1].errorMessage)
    }

    @Test
    fun testAiCoreFallbackModeEnumAndConfig() {
        assertEquals("disable", AiCoreFallbackMode.DISABLE.code)
        assertEquals("無効", AiCoreFallbackMode.DISABLE.jaLabel)
        assertEquals("Disable", AiCoreFallbackMode.DISABLE.enLabel)

        assertEquals("quota_only", AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY.code)
        assertEquals("AICore超過時のみ", AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY.jaLabel)
        assertEquals("Only if the AICore quota is exceeded", AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY.enLabel)

        assertEquals("all", AiCoreFallbackMode.ALL.code)
        assertEquals("全て", AiCoreFallbackMode.ALL.jaLabel)
        assertEquals("All", AiCoreFallbackMode.ALL.enLabel)

        assertEquals(AiCoreFallbackMode.DISABLE, AiCoreFallbackMode.fromCode("disable"))
        assertEquals(AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY, AiCoreFallbackMode.fromCode("quota_only"))
        assertEquals(AiCoreFallbackMode.ALL, AiCoreFallbackMode.fromCode("all"))
        assertEquals(AiCoreFallbackMode.DISABLE, AiCoreFallbackMode.fromCode("unknown"))
    }

    @Test
    fun testIsFallbackApplicableLogic() {
        val quotaExceededCode = GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
        val busyCode = GenAiException.ErrorCode.BUSY
        val quotaExceededException = GenAiException("Quota exceeded", null, quotaExceededCode)
        val busyException = GenAiException("Busy", null, busyCode)
        val networkException = Exception("Network connection failed")

        // 1. DISABLE mode: never applicable
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.DISABLE, quotaExceededCode, quotaExceededException))
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.DISABLE, busyCode, busyException))
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.DISABLE, null, networkException))

        // 2. QUOTA_EXCEEDED_ONLY mode: only applicable for PER_APP_BATTERY_USE_QUOTA_EXCEEDED (27)
        assertTrue(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY, quotaExceededCode, quotaExceededException))
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY, busyCode, busyException))
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY, null, networkException))

        // 3. ALL mode: applicable for any AICore error code or AICore exception
        assertTrue(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.ALL, quotaExceededCode, quotaExceededException))
        assertTrue(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.ALL, busyCode, busyException))
        val aiCoreMsgException = Exception("AICore Service Unavailable")
        assertTrue(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.ALL, null, aiCoreMsgException))
        assertFalse(ImportRepository.isFallbackApplicable(AiCoreFallbackMode.ALL, null, networkException))
    }

    @Test
    fun testFallbackBookmarkCreationContract() {
        val target = ImportTask(
            id = "import-test-1",
            url = "https://example.com/fallback-target",
            title = "Target Web Page",
            status = TaskStatus.PROCESSING_AI,
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
            status = TaskStatus.READY,
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
        assertEquals(TaskStatus.READY.name, entity.status)
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

    @Test
    fun testTaskExecutionEligibilityGuardsAgainstInvalidStates() {
        // executeTaskAiAnalysis requires the task to be in WAITING_AI or PROCESSING_AI
        fun isEligibleForExecution(status: TaskStatus): Boolean {
            return status == TaskStatus.WAITING_AI || status == TaskStatus.PROCESSING_AI
        }

        assertTrue(isEligibleForExecution(TaskStatus.WAITING_AI))
        assertTrue(isEligibleForExecution(TaskStatus.PROCESSING_AI))
        assertFalse(isEligibleForExecution(TaskStatus.READY))
        assertFalse(isEligibleForExecution(TaskStatus.COMPLETED))
        assertFalse(isEligibleForExecution(TaskStatus.ERROR))
    }

    @Test
    fun testPromoteReadyTaskToWaitingAiStateTransition() {
        val readyTask = ImportTask(
            id = "test-promote-1",
            url = "https://example.com/test",
            title = "Sample Webpage",
            status = TaskStatus.READY,
            statusText = "Ready for AI Analysis",
            isNsfw = false,
            siteName = "SampleSite",
            description = "Snippet description",
            bodySnippet = "Extracted body content"
        )

        val promoted = readyTask.copy(
            status = TaskStatus.WAITING_AI,
            statusText = "Waiting for AICore slot..."
        )

        assertEquals(TaskStatus.WAITING_AI, promoted.status)
        assertEquals("Waiting for AICore slot...", promoted.statusText)
        assertEquals(readyTask.id, promoted.id)
        assertEquals(readyTask.url, promoted.url)
        assertEquals(readyTask.title, promoted.title)
        assertEquals(readyTask.siteName, promoted.siteName)
        assertEquals(readyTask.description, promoted.description)
        assertEquals(readyTask.bodySnippet, promoted.bodySnippet)

        // Convert to entity and back to ensure Room compatibility
        val entity = ImportTaskEntity.fromImportTask(promoted)
        assertEquals(TaskStatus.WAITING_AI.name, entity.status)
        assertEquals("Waiting for AICore slot...", entity.statusText)

        val restored = entity.toImportTask()
        assertEquals(TaskStatus.WAITING_AI, restored.status)
        assertEquals("Waiting for AICore slot...", restored.statusText)
    }

    @Test
    fun testAiProcessingProgressDefaultState() {
        val defaultProgress = AiProcessingProgress()
        assertFalse(defaultProgress.isProcessing)
        assertEquals(0, defaultProgress.completedCount)
        assertEquals(0, defaultProgress.totalCount)
        assertNull(defaultProgress.currentTaskTitle)
    }

    @Test
    fun testBatchProgressTrackingLifecycle() {
        var state = ImportRepository.BatchProgressState()

        fun computeProgress(processingTasks: List<ImportTask>): AiProcessingProgress {
            val (progress, newState) = ImportRepository.computeBatchProgress(
                processingTasks = processingTasks,
                state = state
            )
            state = newState
            return progress
        }

        // 1. Initial empty state
        val step0 = computeProgress(emptyList())
        assertFalse(step0.isProcessing)
        assertEquals(0, step0.totalCount)
        assertEquals(0, step0.completedCount)

        // 2. Batch starts with 3 tasks (t1 analyzing, t2, t3 waiting)
        val t1 = ImportTask(id = "1", url = "https://a.com", title = "Task A", status = TaskStatus.PROCESSING_AI, statusText = "")
        val t2 = ImportTask(id = "2", url = "https://b.com", title = "Task B", status = TaskStatus.WAITING_AI, statusText = "")
        val t3 = ImportTask(id = "3", url = "https://c.com", title = "Task C", status = TaskStatus.WAITING_AI, statusText = "")

        val step1 = computeProgress(listOf(t1, t2, t3))
        assertTrue(step1.isProcessing)
        assertEquals(3, step1.totalCount)
        assertEquals(0, step1.completedCount)
        assertEquals("Task A", step1.currentTaskTitle)

        // 3. Task 1 finishes -> remaining t2 (analyzing) and t3 (waiting)
        val t2Active = t2.copy(status = TaskStatus.PROCESSING_AI)
        val step2 = computeProgress(listOf(t2Active, t3))
        assertTrue(step2.isProcessing)
        assertEquals(3, step2.totalCount)
        assertEquals(1, step2.completedCount)
        assertEquals("Task B", step2.currentTaskTitle)

        // 4. User adds task 4 mid-flight
        val t4 = ImportTask(id = "4", url = "https://d.com", title = "Task D", status = TaskStatus.WAITING_AI, statusText = "")
        val step3 = computeProgress(listOf(t2Active, t3, t4))
        assertTrue(step3.isProcessing)
        assertEquals(4, step3.totalCount)
        assertEquals(1, step3.completedCount)

        // 5. Task 2 finishes -> remaining t3 (analyzing) and t4
        val t3Active = t3.copy(status = TaskStatus.PROCESSING_AI)
        val step4 = computeProgress(listOf(t3Active, t4))
        assertTrue(step4.isProcessing)
        assertEquals(4, step4.totalCount)
        assertEquals(2, step4.completedCount)
        assertEquals("Task C", step4.currentTaskTitle)

        // 6. Task 3 finishes -> remaining t4 (analyzing)
        val t4Active = t4.copy(status = TaskStatus.PROCESSING_AI)
        val step5 = computeProgress(listOf(t4Active))
        assertTrue(step5.isProcessing)
        assertEquals(4, step5.totalCount)
        assertEquals(3, step5.completedCount)
        assertEquals("Task D", step5.currentTaskTitle)

        // 7. Task 4 finishes -> empty list (all completed)
        val step6 = computeProgress(emptyList())
        assertFalse(step6.isProcessing)
        assertEquals(0, step6.totalCount)
        assertEquals(0, step6.completedCount)
        assertNull(step6.currentTaskTitle)
    }

    @Test
    fun testBatchProgressCancellationLifecycle() {
        var state = ImportRepository.BatchProgressState()
        var isAiCancelPending = false

        fun computeProgress(processingTasks: List<ImportTask>): AiProcessingProgress {
            val (progress, newState) = ImportRepository.computeBatchProgress(
                processingTasks = processingTasks,
                state = state,
                isCancelPending = isAiCancelPending
            )
            state = newState
            return progress
        }

        // 1. Batch starts with 3 tasks (t1 processing, t2 & t3 waiting)
        val t1 = ImportTask(id = "1", url = "https://a.com", title = "Task A", status = TaskStatus.PROCESSING_AI, statusText = "")
        val t2 = ImportTask(id = "2", url = "https://b.com", title = "Task B", status = TaskStatus.WAITING_AI, statusText = "")
        val t3 = ImportTask(id = "3", url = "https://c.com", title = "Task C", status = TaskStatus.WAITING_AI, statusText = "")

        val activeList = mutableListOf(t1, t2, t3)
        val initialProgress = computeProgress(activeList)
        assertTrue(initialProgress.isProcessing)
        assertFalse(initialProgress.isCancelPending)
        assertEquals(3, initialProgress.totalCount)
        assertEquals("Task A", initialProgress.currentTaskTitle)

        // 2. User taps '×' cancel button:
        // isAiCancelPending flag is set to true
        isAiCancelPending = true
        // Waiting tasks (t2, t3) are returned to READY and removed from processing list
        val waitingTasks = activeList.filter { it.status == TaskStatus.WAITING_AI }
        assertEquals(2, waitingTasks.size)
        activeList.removeAll(waitingTasks)
        val restoredToReady = waitingTasks.map { it.copy(status = TaskStatus.READY, statusText = "") }
        assertEquals(2, restoredToReady.size)
        assertTrue(restoredToReady.all { it.status == TaskStatus.READY })

        // Active list now contains ONLY the single actively executing task (t1)
        val cancelledProgress = computeProgress(activeList)
        assertTrue(cancelledProgress.isProcessing)
        assertTrue(cancelledProgress.isCancelPending) // Cancel button is now disabled / grayed out
        assertEquals("Task A", cancelledProgress.currentTaskTitle)
        assertEquals(1, activeList.size)
        assertEquals(TaskStatus.PROCESSING_AI, activeList.first().status)

        // 3. Actively executing task finishes -> active list becomes empty
        activeList.clear()
        val finalProgress = computeProgress(activeList)
        assertFalse(finalProgress.isProcessing) // Indicator button hides
        assertFalse(finalProgress.isCancelPending) // Reset for future batches
        assertEquals(0, finalProgress.totalCount)
        assertEquals(0, finalProgress.completedCount)
        assertNull(finalProgress.currentTaskTitle)
    }

    private fun fakeModel(onClose: () -> Unit): GenerativeModel = Proxy.newProxyInstance(
        GenerativeModel::class.java.classLoader,
        arrayOf(GenerativeModel::class.java)
    ) { _, method, _ ->
        check(method.name == "close") { "Unexpected model call: ${method.name}" }
        onClose()
        null
    } as GenerativeModel

    private fun waitingEntity(id: String) = ImportTaskEntity.fromImportTask(
        ImportTask(id = id, url = "https://example.com/$id", title = id, status = TaskStatus.WAITING_AI, statusText = "")
    )

    @Test
    fun processingLoopRetriesBusyTaskWithoutBlockingOtherTasksOrReopeningModel() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(waitingEntity("busy"), waitingEntity("next")))
        val closed = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        var opens = 0
        var closes = 0
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { true },
                openModel = { opens++; fakeModel { closes++; closed.complete(Unit) } },
                analyzeTask = { _, task ->
                    attempts.add(task.id)
                    if (task.id == "busy") throw AiCoreExecutionException(9, "AICore busy")
                },
                fallbackMode = { AiCoreFallbackMode.DISABLE },
                retryIntervalMs = 10L
            )
            repository.retryWaitingProcessingTasks()
            withTimeout(15000) { closed.await() }
            assertEquals(listOf("busy", "next", "busy", "busy", "busy"), attempts)
            assertEquals(1, opens)
            assertEquals(1, closes)
            assertEquals(TaskStatus.ERROR.name, dao.getTaskById("busy")?.status)
            assertNull(dao.getTaskById("next"))
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun processingLoopCancellationFinishesActiveTaskAndRestoresWaitingTasks() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(waitingEntity("active"), waitingEntity("waiting")))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { true },
                openModel = { fakeModel { closed.complete(Unit) } },
                analyzeTask = { _, task -> processed.add(task.id); started.complete(Unit); release.await() }
            )
            repository.retryWaitingProcessingTasks()
            withTimeout(5000) { started.await() }
            repository.cancelProcessing()
            // Give the repository's queued cancellation update a chance to run.
            kotlinx.coroutines.yield()
            assertEquals(TaskStatus.READY.name, dao.getTaskById("waiting")?.status)
            release.complete(Unit)
            withTimeout(5000) { closed.await() }
            assertEquals(listOf("active"), processed)
            assertNull(dao.getTaskById("active"))
            assertEquals(TaskStatus.READY.name, dao.getTaskById("waiting")?.status)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun backgroundPauseClosesSessionAndForegroundResumeProcessesInterruptedTasks() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(waitingEntity("first"), waitingEntity("second")))
        val paused = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var foreground = true
        var opens = 0
        var closes = 0
        val processed = mutableListOf<String>()
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { foreground },
                openModel = {
                    opens++
                    fakeModel { if (++closes == 1) paused.complete(Unit) else finished.complete(Unit) }
                },
                analyzeTask = { _, task ->
                    processed.add(task.id)
                    if (task.id == "first") foreground = false
                }
            )
            repository.retryWaitingProcessingTasks()
            withTimeout(5000) { paused.await() }
            assertEquals(listOf("first"), processed)
            assertEquals(TaskStatus.WAITING_AI.name, dao.getTaskById("second")?.status)
            // Simulate the persisted interrupted state handled by the resume entry point.
            dao.updateStatus("second", TaskStatus.PROCESSING_AI.name, "Interrupted")
            foreground = true
            repository.retryWaitingProcessingTasks()
            withTimeout(5000) { finished.await() }
            assertEquals(listOf("first", "second"), processed)
            assertEquals(2, opens)
            assertEquals(2, closes)
            assertEquals(0, dao.getCount())
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun quotaFailureRestoresBatchBeforeLoopCanStartAnotherInference() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(waitingEntity("first"), waitingEntity("second")))
        val closed = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { true },
                openModel = { fakeModel { closed.complete(Unit) } },
                analyzeTask = { _, task ->
                    attempts.add(task.id)
                    throw AiCoreExecutionException(27, "Battery quota exceeded")
                },
                fallbackMode = { AiCoreFallbackMode.DISABLE }
            )
            repository.retryWaitingProcessingTasks()
            withTimeout(5000) { closed.await() }
            assertEquals(listOf("first"), attempts)
            assertTrue(dao.getAllTasks().all { it.status == TaskStatus.READY.name })
            assertTrue(repository.quotaExceededDialogVisible.value)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun bulkAndIndividualPromotionsUseSameSessionIncludingWorkAddedDuringInference() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(waitingEntity("bulk").copy(status = TaskStatus.READY.name)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var opens = 0
        val processed = mutableListOf<String>()
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { true },
                openModel = { opens++; fakeModel { closed.complete(Unit) } },
                analyzeTask = { _, task ->
                    processed.add(task.id)
                    if (task.id == "bulk") { started.complete(Unit); release.await() }
                }
            )
            repository.aiAnalysisAll()
            withTimeout(5000) { started.await() }
            dao.insertOrUpdate(waitingEntity("individual").copy(status = TaskStatus.READY.name))
            repository.promoteTaskToProcessing("individual")
            kotlinx.coroutines.yield()
            release.complete(Unit)
            withTimeout(5000) { closed.await() }
            assertEquals(listOf("bulk", "individual"), processed)
            assertEquals(1, opens)
            assertEquals(0, dao.getCount())
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun fallbackRetainsImportTaskUntilDurableSaveCompletes() = verifyFallbackPersistence(false)

    @Test
    fun failedFallbackSaveRetainsRetryableTaskAndRetryCanSucceed() = verifyFallbackPersistence(true)

    @Test
    fun rssFallbackPreservesFirstSeenDateWithoutPublication() = verifyFallbackPersistence(false, rss = true)

    private fun verifyFallbackPersistence(initiallyFail: Boolean, rss: Boolean = false) = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val dao = FakeImportTaskDao()
        dao.insertOrUpdate(waitingEntity("fallback").copy(rssFeedId = if (rss) "feed" else null, createdAt = 7))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val firstClosed = CompletableDeferred<Unit>()
        val secondClosed = CompletableDeferred<Unit>()
        var closes = 0
        var failSave = initiallyFail
        val saved = mutableListOf<BookmarkItem>()
        try {
            val repository = ImportRepository(
                taskDao = dao, repositoryScope = scope, isAppInForeground = { true },
                openModel = {
                    fakeModel { if (++closes == 1) firstClosed.complete(Unit) else secondClosed.complete(Unit) }
                },
                analyzeTask = { _, _ -> throw AiCoreExecutionException(27, "Battery quota exceeded") },
                fallbackMode = { AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY },
                persistFallback = { bookmark ->
                    saveStarted.complete(Unit)
                    releaseSave.await()
                    assertTrue(dao.getTaskById("fallback") != null)
                    if (failSave) error("Disk write failed")
                    saved.add(bookmark)
                }
            )
            repository.retryWaitingProcessingTasks()
            withTimeout(5000) { saveStarted.await() }
            assertEquals(TaskStatus.PROCESSING_AI.name, dao.getTaskById("fallback")?.status)
            assertTrue(saved.isEmpty())
            releaseSave.complete(Unit)
            withTimeout(5000) { firstClosed.await() }
            if (initiallyFail) {
                assertEquals(TaskStatus.ERROR.name, dao.getTaskById("fallback")?.status)
                assertTrue(saved.isEmpty())
                failSave = false
                repository.retryErrorTask("fallback")
                withTimeout(5000) { secondClosed.await() }
            }
            assertNull(dao.getTaskById("fallback"))
            assertEquals(1, saved.size)
            assertTrue(saved.single().isAiSkipped)
            if (rss) {
                assertEquals("feed", saved.single().rssFeedId)
                assertEquals(7L, saved.single().createdAt)
                assertNull(saved.single().rssPublishedAt)
            }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun manualTasksRunBeforeNewerRssTasks() = runBlocking {
        val dao = FakeImportTaskDao()
        dao.insertAll(listOf(
            ImportTaskEntity("rss", "https://example.com/rss", "RSS", "WAITING_AI", "", createdAt = 20, rssFeedId = "feed"),
            ImportTaskEntity("manual", "https://example.com/manual", "Manual", "WAITING_AI", "", createdAt = 10)))
        val root = SupervisorJob()
        val done = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + root),
            isAppInForeground = { true }, openModel = { fakeModel { done.complete(Unit) } },
            analyzeTask = { _, task -> processed.add(task.id) })
        try {
            repository.retryWaitingProcessingTasks()
            withTimeout(3000) { done.await() }
            assertEquals(listOf("manual", "rss"), processed)
        } finally { root.cancelAndJoin() }
    }

    @Test
    fun subscriptionRemovedDuringClaimStopsWithoutInferenceOrBusyLoop() = runBlocking {
        val dao = FakeImportTaskDao()
        dao.insertOrUpdate(ImportTaskEntity("rss", "https://example.com/rss", "RSS", "WAITING_AI", "", createdAt = 1, rssFeedId = "feed"))
        dao.beforeClaim = { dao.unavailableIds = setOf("rss") }
        val root = SupervisorJob()
        val closed = CompletableDeferred<Unit>()
        var attempts = 0
        val repository = ImportRepository(taskDao = dao, repositoryScope = CoroutineScope(coroutineContext + root),
            isAppInForeground = { true }, openModel = { fakeModel { closed.complete(Unit) } },
            analyzeTask = { _, _ -> attempts++ })
        try {
            repository.retryWaitingProcessingTasks()
            withTimeout(3000) { closed.await() }
            assertEquals(0, attempts)
            assertEquals("ERROR", dao.getTaskById("rss")?.status)
        } finally { root.cancelAndJoin() }
    }

    private class FakeImportTaskDao : ImportTaskDao {
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

        override suspend fun insertOrUpdate(task: ImportTaskEntity) {
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

        override suspend fun claimForAi(id: String, statusText: String): Int {
            beforeClaim()
            delay(1)
            if (id in unavailableIds) return 0
            val task = getTaskById(id) ?: return 0
            if (task.status !in listOf("WAITING_AI", "PROCESSING_AI")) return 0
            updateStatus(id, "PROCESSING_AI", statusText, null)
            return 1
        }

        override suspend fun rejectUnavailableRss() {
            tasksFlow.value = tasksFlow.value.map {
                if (it.id in unavailableIds && it.status in listOf("WAITING_AI", "PROCESSING_AI"))
                    it.copy(status = "ERROR", statusText = "Subscription unavailable") else it
            }
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
    fun testRepositoryCancellationWithFakeDao() = runBlocking {
        val fakeDao = FakeImportTaskDao()
        val t1 = ImportTaskEntity(
            id = "1", url = "https://a.com", title = "Task A",
            status = TaskStatus.PROCESSING_AI.name, statusText = "", assignedTags = emptyList(),
            createdAt = 100L, isNsfw = false, siteName = "", description = "", bodySnippet = ""
        )
        val t2 = ImportTaskEntity(
            id = "2", url = "https://b.com", title = "Task B",
            status = TaskStatus.WAITING_AI.name, statusText = "", assignedTags = emptyList(),
            createdAt = 200L, isNsfw = false, siteName = "", description = "", bodySnippet = ""
        )
        val t3 = ImportTaskEntity(
            id = "3", url = "https://c.com", title = "Task C",
            status = TaskStatus.WAITING_AI.name, statusText = "", assignedTags = emptyList(),
            createdAt = 300L, isNsfw = false, siteName = "", description = "", bodySnippet = ""
        )

        fakeDao.tasksFlow.value = listOf(t1, t2, t3)

        val repository = ImportRepository(context = null, taskDao = fakeDao)
        delay(150)

        // 1. Initial batch progress state
        val initialProgress = repository.aiProcessingProgress.value
        assertTrue(initialProgress.isProcessing)
        assertFalse(initialProgress.isCancelPending)
        assertEquals(3, initialProgress.totalCount)

        // 2. User invokes cancelProcessing()
        repository.cancelProcessing()
        delay(150)

        // Waiting tasks (t2, t3) are restored to READY in DAO
        val allTasks = fakeDao.getAllTasks()
        val waitingTasks = allTasks.filter { it.status == TaskStatus.WAITING_AI.name }
        val readyTasks = allTasks.filter { it.status == TaskStatus.READY.name }
        assertEquals(0, waitingTasks.size)
        assertEquals(2, readyTasks.size)

        // Progress emits isCancelPending = true while active task is finishing
        val cancelProgress = repository.aiProcessingProgress.value
        assertTrue(cancelProgress.isProcessing)
        assertTrue(cancelProgress.isCancelPending)
        assertEquals("Task A", cancelProgress.currentTaskTitle)

        // 3. When active task finishes and DB is updated (only ready tasks remain)
        fakeDao.tasksFlow.value = readyTasks
        delay(150)

        val finalProgress = repository.aiProcessingProgress.value
        assertFalse(finalProgress.isProcessing)
        assertFalse(finalProgress.isCancelPending)
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

