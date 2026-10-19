package me.bubblespark.tidilo.data

/**
 * State representing the active batch progress of background AI processing.
 *
 * @param isProcessing True if at least one task is in WAITING_AI or PROCESSING_AI state.
 * @param completedCount Number of tasks completed in the current batch.
 * @param totalCount Total number of tasks queued in the current batch.
 * @param currentTaskTitle Title or URL of the task currently undergoing AI inference.
 */
data class AiProcessingProgress(
    val isProcessing: Boolean = false,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val currentTaskTitle: String? = null,
    val isCancelPending: Boolean = false
)
