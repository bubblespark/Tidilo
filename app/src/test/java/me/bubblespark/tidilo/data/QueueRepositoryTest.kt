package me.bubblespark.tidilo.data

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
}
