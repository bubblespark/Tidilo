package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.ui.screens.queue.QueueItem
import me.bubblespark.tidilo.ui.screens.queue.QueueItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRepositoryTest {

    @Test
    fun testRemoveAndRestoreQueuingItemLogic() {
        val item1 = QueueItem(id = "1", url = "https://example.com/1", title = "Item 1", status = QueueItemStatus.QUEUED, statusText = "Waiting")
        val item2 = QueueItem(id = "2", url = "https://example.com/2", title = "Item 2", status = QueueItemStatus.QUEUED, statusText = "Waiting")
        val item3 = QueueItem(id = "3", url = "https://example.com/3", title = "Item 3", status = QueueItemStatus.QUEUED, statusText = "Waiting")

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
    fun testRemoveAndRestoreErrorItemLogic() {
        val error1 = QueueItem(id = "e1", url = "https://example.com/err1", title = "Err 1", status = QueueItemStatus.ERROR, statusText = "Failed", errorMessage = "404")
        val error2 = QueueItem(id = "e2", url = "https://example.com/err2", title = "Err 2", status = QueueItemStatus.ERROR, statusText = "Failed", errorMessage = "500")

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

        org.junit.Assert.assertTrue(QueueRepository.isAiCoreOrInterruptionException(aiCoreException1))
        org.junit.Assert.assertTrue(QueueRepository.isAiCoreOrInterruptionException(aiCoreException2))
        org.junit.Assert.assertTrue(QueueRepository.isAiCoreOrInterruptionException(aiCoreException3))
        org.junit.Assert.assertTrue(QueueRepository.isAiCoreOrInterruptionException(remoteException))
        org.junit.Assert.assertTrue(QueueRepository.isAiCoreOrInterruptionException(cancelException))

        val http404 = Exception("HTTP Error 404: Not Found")
        val http500 = Exception("HTTP Error 500: Server Error")
        val invalidUrl = IllegalArgumentException("Invalid HTTP/HTTPS URL")

        org.junit.Assert.assertFalse(QueueRepository.isAiCoreOrInterruptionException(http404))
        org.junit.Assert.assertFalse(QueueRepository.isAiCoreOrInterruptionException(http500))
        org.junit.Assert.assertFalse(QueueRepository.isAiCoreOrInterruptionException(invalidUrl))
    }

    @Test
    fun testInterruptedRunningItemRetainsWaitingStatusWithoutError() {
        val runningItem = QueueItem(
            id = "run-test",
            url = "https://example.com/test",
            title = "Test Item",
            status = QueueItemStatus.PROCESSING_AI,
            statusText = "Processing..."
        )

        // When interrupted by background/AICore exception, it should be set to WAITING_AI with paused status
        val interruptedItem = runningItem.copy(
            status = QueueItemStatus.WAITING_AI,
            statusText = "Paused • Retrying when app is active"
        )

        assertEquals(QueueItemStatus.WAITING_AI, interruptedItem.status)
        assertEquals("Paused • Retrying when app is active", interruptedItem.statusText)
        assertEquals("run-test", interruptedItem.id)
    }

    @Test
    fun testPrecomputedQueueItemMetadataFields() {
        val item = QueueItem(
            id = "preparsed-1",
            url = "https://example.com/article",
            title = "Example Article Title",
            status = QueueItemStatus.QUEUED,
            statusText = "Queued • Ready for AI",
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

        org.junit.Assert.assertTrue(promptJa.contains("URL: https://example.com/article"))
        org.junit.Assert.assertTrue(promptJa.contains("Site: Example Blog"))
        org.junit.Assert.assertTrue(promptJa.contains("Title: Example Article Title"))
        org.junit.Assert.assertTrue(promptJa.contains("Description: A concise article description"))
        org.junit.Assert.assertTrue(promptJa.contains("Content: This is the extracted body text snippet used for instant AI tagging."))
        org.junit.Assert.assertTrue(promptJa.contains("Language: Japanese."))
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
            QueueRepository.extractAiCoreErrorCode(busyEx)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            QueueRepository.extractAiCoreErrorCode(quotaEx)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            QueueRepository.extractAiCoreErrorCode(blockedEx)
        )

        // Nested in runtime exception wrapper
        val wrappedQuotaEx = RuntimeException("Wrapper exception", quotaEx)
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            QueueRepository.extractAiCoreErrorCode(wrappedQuotaEx)
        )

        // String message fallback detection
        val msgFallbackQuota = Exception("AICore battery use quota exceeded (code 27)")
        val msgFallbackBusy = Exception("AICore BUSY rate limit exceeded")
        val msgFallbackBlocked = Exception("AICore BACKGROUND_USE_BLOCKED error")

        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            QueueRepository.extractAiCoreErrorCode(msgFallbackQuota)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BUSY,
            QueueRepository.extractAiCoreErrorCode(msgFallbackBusy)
        )
        assertEquals(
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            QueueRepository.extractAiCoreErrorCode(msgFallbackBlocked)
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
        org.junit.Assert.assertTrue(mappedBusy.message?.contains("AICore Rate Limit Exceeded") == true)

        val genAiQuota = com.google.mlkit.genai.common.GenAiException(
            "Quota limit hit",
            null,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
        )
        val mappedQuota = me.bubblespark.tidilo.service.GeminiTaggingService.mapInferenceException(genAiQuota)
        assertEquals(com.google.mlkit.genai.common.GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED, mappedQuota.errorCode)
        org.junit.Assert.assertTrue(mappedQuota.message?.contains("battery use quota exceeded") == true)

        val genAiBlocked = com.google.mlkit.genai.common.GenAiException(
            "Background not allowed",
            null,
            com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
        )
        val mappedBlocked = me.bubblespark.tidilo.service.GeminiTaggingService.mapInferenceException(genAiBlocked)
        assertEquals(com.google.mlkit.genai.common.GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED, mappedBlocked.errorCode)
        org.junit.Assert.assertTrue(mappedBlocked.message?.contains("background use blocked") == true)
    }

    @Test
    fun testMoveAllRunningToQueuingLogic() {
        val item1 = QueueItem(id = "run-1", url = "https://example.com/1", title = "Task 1", status = QueueItemStatus.PROCESSING_AI, statusText = "Inference")
        val item2 = QueueItem(id = "run-2", url = "https://example.com/2", title = "Task 2", status = QueueItemStatus.WAITING_AI, statusText = "Waiting")
        val currentRunning = listOf(item1, item2)

        // When battery quota exceeded occurs:
        val converted = currentRunning.map { item ->
            item.copy(
                status = QueueItemStatus.QUEUED,
                statusText = "Queued • Quota exceeded"
            )
        }

        assertEquals(2, converted.size)
        assertEquals(QueueItemStatus.QUEUED, converted[0].status)
        assertEquals("Queued • Quota exceeded", converted[0].statusText)
        assertEquals(QueueItemStatus.QUEUED, converted[1].status)
        assertEquals("Queued • Quota exceeded", converted[1].statusText)
    }

    @Test
    fun testMoveAllRunningToErrorBatchFailureLogic() {
        val item1 = QueueItem(id = "run-1", url = "https://example.com/1", title = "Task 1", status = QueueItemStatus.PROCESSING_AI, statusText = "Inference")
        val item2 = QueueItem(id = "run-2", url = "https://example.com/2", title = "Task 2", status = QueueItemStatus.WAITING_AI, statusText = "Waiting")
        val currentRunning = listOf(item1, item2)

        val primaryFailedItem = item1
        val failureMsg = "AICore busy: Retry limit exceeded (3/3)"

        val errorItems = currentRunning.map { item ->
            item.copy(
                status = QueueItemStatus.ERROR,
                statusText = "Failed",
                errorMessage = if (item.id == primaryFailedItem.id) {
                    failureMsg
                } else {
                    "Batch stopped: $failureMsg"
                }
            )
        }

        assertEquals(2, errorItems.size)
        assertEquals(QueueItemStatus.ERROR, errorItems[0].status)
        assertEquals(failureMsg, errorItems[0].errorMessage)
        assertEquals(QueueItemStatus.ERROR, errorItems[1].status)
        assertEquals("Batch stopped: AICore busy: Retry limit exceeded (3/3)", errorItems[1].errorMessage)
    }
}
