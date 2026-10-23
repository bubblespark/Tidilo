package me.bubblespark.tidilo.data

import androidx.compose.runtime.Immutable
import java.util.UUID

/**
 * Lifecycle status of an import task.
 */
enum class TaskStatus {
    PROCESSING_AI, // Active inference running on Gemini Nano
    WAITING_AI,    // Waiting for AICore execution slot in processing group
    READY,         // Ready after share or import, awaiting analysis
    COMPLETED,     // Finished analysis and saved to bookmarks
    ERROR          // Failed due to rate limit, non-200 HTTP response, or inference failure
}

/**
 * Transient representation of a task actively importing and parsing a URL.
 */
@Immutable
data class ImportingTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Immutable domain representation of an import task.
 */
@Immutable
data class ImportTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val status: TaskStatus,
    val statusText: String,
    val errorMessage: String? = null,
    val assignedTags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val isNsfw: Boolean = false,
    val siteName: String = "",
    val description: String = "",
    val bodySnippet: String = "",
    val ogImageUrl: String? = null,
    val rssFeedId: String? = null,
    val rssPublishedAt: Long? = null
)
