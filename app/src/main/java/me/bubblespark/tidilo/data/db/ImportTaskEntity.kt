package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.TaskStatus

/**
 * Room entity representing an import task in SQLite.
 */
@Entity(
    tableName = "import_tasks",
    indices = [
        Index(value = ["url"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class ImportTaskEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val status: String,
    val statusText: String,
    val errorMessage: String? = null,
    val assignedTags: List<String> = emptyList(),
    val createdAt: Long,
    val isNsfw: Boolean = false,
    val siteName: String = "",
    val description: String = "",
    val bodySnippet: String = ""
) {
    fun toImportTask(): ImportTask {
        val taskStatus = runCatching {
            when (status) {
                "QUEUED" -> TaskStatus.READY
                else -> TaskStatus.valueOf(status)
            }
        }.getOrDefault(TaskStatus.READY)

        return ImportTask(
            id = id,
            url = url,
            title = title,
            status = taskStatus,
            statusText = statusText,
            errorMessage = errorMessage,
            assignedTags = assignedTags,
            createdAt = createdAt,
            isNsfw = isNsfw,
            siteName = siteName,
            description = description,
            bodySnippet = bodySnippet
        )
    }

    companion object {
        fun fromImportTask(task: ImportTask): ImportTaskEntity {
            return ImportTaskEntity(
                id = task.id,
                url = task.url,
                title = task.title,
                status = task.status.name,
                statusText = task.statusText,
                errorMessage = task.errorMessage,
                assignedTags = task.assignedTags,
                createdAt = task.createdAt,
                isNsfw = task.isNsfw,
                siteName = task.siteName,
                description = task.description,
                bodySnippet = task.bodySnippet
            )
        }
    }
}
