package me.bubblespark.tidilo.ui.screens.notifications

import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.TaskStatus
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity

sealed interface NotificationListItem {
    val key: String
    val createdAt: Long
    data class History(val notification: ImportNotificationEntity) : NotificationListItem {
        override val key = "history:${notification.id}"
        override val createdAt = notification.createdAt
    }
    data class Failure(val task: ImportTask) : NotificationListItem {
        override val key = "task:${task.id}"
        override val createdAt = task.createdAt
    }
    data class RssXmlFailure(val feed: RssFeedEntity) : NotificationListItem {
        override val key = "feed:${feed.id}"
        override val createdAt = requireNotNull(feed.lastAttemptAt)
    }
}

fun buildNotificationList(history: List<ImportNotificationEntity>, errors: List<ImportTask>, feeds: List<RssFeedEntity>): List<NotificationListItem> =
    (history.map(NotificationListItem::History) +
        errors.filter { it.status == TaskStatus.ERROR }.map(NotificationListItem::Failure) +
        feeds.filter { !it.deleted && !it.error.isNullOrBlank() && it.lastAttemptAt != null }
            .map(NotificationListItem::RssXmlFailure))
        .sortedWith(compareByDescending<NotificationListItem> { it.createdAt }.thenBy { it.key })

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)

fun formatNotificationTimestamp(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(zone))
