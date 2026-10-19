package me.bubblespark.tidilo.ui.screens.importscreen

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.ImportTask

class ImportViewModel(
    private val repository: ImportRepository = ImportRepository.getInstance(TidiloApplication.instance)
) : ViewModel() {

    // Processing group: AI analysis in progress or awaiting execution
    val processingTasks: StateFlow<List<ImportTask>> = repository.processingTasks

    // Batch progress for background AI processing
    val aiProcessingProgress: StateFlow<me.bubblespark.tidilo.data.AiProcessingProgress> = repository.aiProcessingProgress

    // Ready group: Waiting after share or import
    val readyTasks: StateFlow<List<ImportTask>> = repository.readyTasks

    // Error group: Tasks that failed due to rate limits, HTTP errors, etc.
    val errorTasks: StateFlow<List<ImportTask>> = repository.errorTasks

    // Battery Quota Exceeded dialog state
    val quotaExceededDialogVisible: StateFlow<Boolean> = repository.quotaExceededDialogVisible

    fun dismissQuotaExceededDialog() {
        repository.dismissQuotaExceededDialog()
    }

    /**
     * Imports URL into the Ready group.
     */
    fun importUrl(url: String, title: String? = null) {
        repository.importUrl(url, title)
    }

    /**
     * "AI Analysis All": Promotes all Ready URLs to Processing.
     */
    fun aiAnalysisAll() {
        repository.aiAnalysisAll()
    }

    /**
     * Promotes a single Ready task to Processing.
     */
    fun promoteToProcessing(taskId: String) {
        repository.promoteTaskToProcessing(taskId)
    }

    /**
     * Manually triggers or retries AI analysis for a task in the Processing group.
     */
    fun retryProcessing(taskId: String) {
        repository.executeTaskAiAnalysis(taskId)
    }

    /**
     * Retries all waiting or paused tasks in the Processing group.
     */
    fun retryWaitingProcessing() {
        repository.retryWaitingProcessingTasks()
    }

    /**
     * Retries a task in the Error group.
     */
    fun retryError(taskId: String) {
        repository.retryErrorTask(taskId)
    }

    /**
     * Deletes a Ready task (returns previous state for Undo).
     */
    fun deleteReadyTask(taskId: String): Pair<ImportTask, Int>? {
        return repository.removeReadyTask(taskId)
    }

    /**
     * Restores a deleted Ready task.
     */
    fun restoreReadyTask(task: ImportTask, index: Int = 0) {
        repository.restoreReadyTask(task, index)
    }

    /**
     * Deletes an Error task (returns previous state for Undo).
     */
    fun deleteErrorTask(taskId: String): Pair<ImportTask, Int>? {
        return repository.removeErrorTask(taskId)
    }

    /**
     * Restores a deleted Error task.
     */
    fun restoreErrorTask(task: ImportTask, index: Int = 0) {
        repository.restoreErrorTask(task, index)
    }

    companion object {
        /**
         * Validates whether the given URL is a valid http/https URL.
         */
        fun isValidHttpUrl(url: String?): Boolean {
            return ImportRepository.isValidHttpUrl(url)
        }

        fun extractHostOrTitle(url: String): String {
            return ImportRepository.extractHostOrTitle(url)
        }
    }
}
