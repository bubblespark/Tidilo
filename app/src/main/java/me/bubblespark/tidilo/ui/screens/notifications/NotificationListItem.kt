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
    /** Unresolved failure that stays listed until it is retried successfully or deleted. */
    sealed interface NeedsAttention : NotificationListItem
    data class History(val notification: ImportNotificationEntity) : NotificationListItem {
        override val key = "history:${notification.id}"
        override val createdAt = notification.createdAt
    }
    data class Failure(val task: ImportTask) : NeedsAttention {
        override val key = "task:${task.id}"
        override val createdAt = task.createdAt
    }
    data class RssXmlFailure(val feed: RssFeedEntity) : NeedsAttention {
        override val key = "feed:${feed.id}"
        override val createdAt = requireNotNull(feed.lastAttemptAt)
    }
}

/** Original diagnostic text stored with an unresolved failure. */
val NotificationListItem.NeedsAttention.diagnostic: String
    get() = when (this) {
        is NotificationListItem.Failure -> task.errorMessage ?: task.statusText
        is NotificationListItem.RssXmlFailure -> feed.error.orEmpty()
    }

/** Notification screen content: unresolved failures above past history, each newest first. */
data class NotificationSections(
    val needsAttention: List<NotificationListItem.NeedsAttention>,
    val history: List<NotificationListItem.History>
) {
    val isEmpty: Boolean get() = needsAttention.isEmpty() && history.isEmpty()
}

private val NEWEST_FIRST = compareByDescending<NotificationListItem> { it.createdAt }.thenBy { it.key }

fun buildNotificationSections(history: List<ImportNotificationEntity>, errors: List<ImportTask>,
    feeds: List<RssFeedEntity>): NotificationSections = NotificationSections(
    needsAttention = (errors.filter { it.status == TaskStatus.ERROR }.map(NotificationListItem::Failure) +
        feeds.filter { !it.deleted && !it.error.isNullOrBlank() && it.lastAttemptAt != null }
            .map(NotificationListItem::RssXmlFailure))
        .sortedWith(NEWEST_FIRST),
    history = history.map(NotificationListItem::History).sortedWith(NEWEST_FIRST)
)

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)

fun formatNotificationTimestamp(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(zone))
