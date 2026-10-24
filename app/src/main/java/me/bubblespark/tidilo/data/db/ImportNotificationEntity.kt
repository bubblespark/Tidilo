package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Notification types for import and system events.
 */
enum class NotificationType {
    INFO,
    WARNING,
    ERROR
}

/**
 * Room entity representing an in-app notification recorded during import.
 */
@Entity(
    tableName = "import_notifications",
    indices = [
        Index(value = ["createdAt"])
    ]
)
data class ImportNotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String, // "INFO", "WARNING", "ERROR"
    val domain: String,
    val url: String,
    val message: String,
    val existingBookmarkId: String? = null,
    val isRead: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
