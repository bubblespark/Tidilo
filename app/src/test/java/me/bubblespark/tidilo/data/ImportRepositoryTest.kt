package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.GenAiException
import me.bubblespark.tidilo.config.AiCoreFallbackMode
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
}
