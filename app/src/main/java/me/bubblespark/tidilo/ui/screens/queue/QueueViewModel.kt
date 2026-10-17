package me.bubblespark.tidilo.ui.screens.queue

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.QueueRepository
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class QueueItemStatus {
    PROCESSING_AI, // AI tag extraction in progress
    WAITING_AI,    // Waiting for execution
    QUEUED,        // Queued after share or addition
    COMPLETED,     // AI tagging completed and registered in Library
    ERROR          // Rate limits, non-200 HTTP response, inference failure, etc.
}

@Immutable
data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val status: QueueItemStatus,
    val statusText: String,
    val errorMessage: String? = null,
    val assignedTags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val isNsfw: Boolean = false,
    val siteName: String = "",
    val description: String = "",
    val bodySnippet: String = ""
)

class QueueViewModel : ViewModel() {

    private val repository = QueueRepository.getInstance(TidiloApplication.instance)

    // Running group: AI tag extraction in progress or awaiting execution
    val runningItems: StateFlow<List<QueueItem>> = repository.runningItems

    // Queuing group: Waiting after share or addition
    val queuingItems: StateFlow<List<QueueItem>> = repository.queuingItems

    // Error group: Items that failed due to rate limits, HTTP errors, etc.
    val errorItems: StateFlow<List<QueueItem>> = repository.errorItems

    // Battery Quota Exceeded dialog state
    val quotaExceededDialogVisible: StateFlow<Boolean> = repository.quotaExceededDialogVisible

    fun dismissQuotaExceededDialog() {
        repository.dismissQuotaExceededDialog()
    }

    /**
     * Enqueues URL into the Queuing group.
     */
    fun enqueueUrl(url: String, title: String? = null) {
        repository.enqueue(url, title)
    }

    /**
     * "AI Tagging All": Promotes all Queuing URLs to Running.
     */
    fun aiTaggingAll() {
        repository.aiTaggingAll()
    }

    /**
     * Promotes a single Queuing item to Running.
     */
    fun promoteToRunning(itemId: String) {
        repository.promoteItemToRunning(itemId)
    }

    /**
     * Manually triggers or retries AI tagging for an item in the Running group.
     */
    fun retryRunning(itemId: String) {
        repository.executeItemAiTagging(itemId)
    }

    /**
     * Retries all waiting or paused items in the Running group.
     */
    fun retryWaitingRunning() {
        repository.retryWaitingRunningItems()
    }

    /**
     * Retries an item in the Error group.
     */
    fun retryError(itemId: String) {
        repository.retryErrorItem(itemId)
    }

    /**
     * Deletes a Queuing item (returns previous state for Undo).
     */
    fun deleteQueuingItem(itemId: String): Pair<QueueItem, Int>? {
        return repository.removeQueuingItem(itemId)
    }

    /**
     * Restores a deleted Queuing item.
     */
    fun restoreQueuingItem(item: QueueItem, index: Int = 0) {
        repository.restoreQueuingItem(item, index)
    }

    /**
     * Deletes an Error item (returns previous state for Undo).
     */
    fun deleteErrorItem(itemId: String): Pair<QueueItem, Int>? {
        return repository.removeErrorItem(itemId)
    }

    /**
     * Restores a deleted Error item.
     */
    fun restoreErrorItem(item: QueueItem, index: Int = 0) {
        repository.restoreErrorItem(item, index)
    }

    companion object {
        /**
         * Validates whether the given URL is a valid http/https URL.
         */
        fun isValidHttpUrl(url: String?): Boolean {
            return QueueRepository.isValidHttpUrl(url)
        }

        fun extractHostOrTitle(url: String): String {
            return QueueRepository.extractHostOrTitle(url)
        }
    }
}
