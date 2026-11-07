package me.bubblespark.tidilo.ui.screens.notifications

import java.time.Instant
import java.time.ZoneId
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.TaskStatus
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Assert.*
import org.junit.Test

class NotificationListItemTest {
    @Test fun mergesPersistedSourcesNewestFirstWithoutCollapsingSameUrl() {
        val history = ImportNotificationEntity(7, "WARNING", "example.com", "https://example.com", "Duplicate", createdAt = 100)
        val task = ImportTask("task", "https://example.com", "Page", TaskStatus.ERROR, "Failed", createdAt = 200)
        val feed = RssFeedEntity("feed", "https://example.com", "Feed", error = "HTTP 503", lastAttemptAt = 300)
        val rows = buildNotificationList(listOf(history), listOf(task), listOf(feed))
        assertEquals(listOf("feed:feed", "task:task", "history:7"), rows.map { it.key })
        assertEquals(listOf(300L, 200L, 100L), rows.map { it.createdAt })
    }

    @Test fun ignoresDeletedFeedsMissingErrorDatesAndNonErrorTasks() {
        val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", error = "HTTP 404", lastAttemptAt = 5)
        val task = ImportTask("queued", "https://example.com", "Page", TaskStatus.QUEUED, "Queued")
        assertTrue(buildNotificationList(emptyList(), listOf(task), listOf(
            feed.copy(deleted = true), feed.copy(id = "no-date", lastAttemptAt = null), feed.copy(id = "ok", error = null)
        )).isEmpty())
    }

    @Test fun equalDatesHaveDeterministicKeysAndEmptyInputIsEmpty() {
        assertTrue(buildNotificationList(emptyList(), emptyList(), emptyList()).isEmpty())
        val a = ImportTask("a", "https://example.com", "A", TaskStatus.ERROR, "Failed", createdAt = 1)
        assertEquals(listOf("task:a", "task:b"), buildNotificationList(emptyList(), listOf(a.copy(id = "b"), a), emptyList()).map { it.key })
    }

    @Test fun datesUsePersistedSecondsAndRequestedZoneAcrossYearBoundary() {
        val timestamp = Instant.parse("2026-12-31T15:04:05Z").toEpochMilli()
        assertEquals("2027/01/01 00:04:05", formatNotificationTimestamp(timestamp, ZoneId.of("Asia/Tokyo")))
        assertEquals("2026/12/31 15:04:05", formatNotificationTimestamp(timestamp, ZoneId.of("UTC")))
    }
}
