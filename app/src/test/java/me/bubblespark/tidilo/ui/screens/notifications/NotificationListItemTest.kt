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
    @Test fun splitsUnresolvedFailuresFromHistoryNewestFirstWithoutCollapsingSameUrl() {
        val history = ImportNotificationEntity(7, "WARNING", "example.com", "https://example.com", "Duplicate", createdAt = 400)
        val task = ImportTask("task", "https://example.com", "Page", TaskStatus.ERROR, "Failed", createdAt = 200)
        val feed = RssFeedEntity("feed", "https://example.com", "Feed", error = "HTTP 503", lastAttemptAt = 300)
        val sections = buildNotificationSections(listOf(history), listOf(task), listOf(feed))
        assertEquals(listOf("feed:feed", "task:task"), sections.needsAttention.map { it.key })
        assertEquals(listOf(300L, 200L), sections.needsAttention.map { it.createdAt })
        assertEquals(listOf("history:7"), sections.history.map { it.key })
        assertFalse(sections.isEmpty)
    }

    @Test fun ignoresDeletedFeedsMissingErrorDatesAndNonErrorTasks() {
        val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", error = "HTTP 404", lastAttemptAt = 5)
        val task = ImportTask("queued", "https://example.com", "Page", TaskStatus.QUEUED, "Queued")
        assertTrue(buildNotificationSections(emptyList(), listOf(task), listOf(
            feed.copy(deleted = true), feed.copy(id = "no-date", lastAttemptAt = null), feed.copy(id = "ok", error = null)
        )).isEmpty)
    }

    @Test fun equalDatesHaveDeterministicKeysAndEmptyInputIsEmpty() {
        assertTrue(buildNotificationSections(emptyList(), emptyList(), emptyList()).isEmpty)
        val a = ImportTask("a", "https://example.com", "A", TaskStatus.ERROR, "Failed", createdAt = 1)
        assertEquals(listOf("task:a", "task:b"),
            buildNotificationSections(emptyList(), listOf(a.copy(id = "b"), a), emptyList()).needsAttention.map { it.key })
        val h = ImportNotificationEntity(2, "INFO", "example.com", "https://example.com", "Saved", createdAt = 1)
        assertEquals(listOf("history:1", "history:2"),
            buildNotificationSections(listOf(h, h.copy(id = 1)), emptyList(), emptyList()).history.map { it.key })
    }

    @Test fun diagnosticFallsBackToStatusTextAndEmptyFeedError() {
        val task = ImportTask("task", "https://example.com", "Page", TaskStatus.ERROR, "Save failed")
        assertEquals("Save failed", NotificationListItem.Failure(task).diagnostic)
        assertEquals("Article HTTP 503", NotificationListItem.Failure(task.copy(errorMessage = "Article HTTP 503")).diagnostic)
        val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", error = "HTTP 503", lastAttemptAt = 5)
        assertEquals("HTTP 503", NotificationListItem.RssXmlFailure(feed).diagnostic)
        assertEquals("", NotificationListItem.RssXmlFailure(feed.copy(error = null)).diagnostic)
    }

    @Test fun datesUsePersistedSecondsAndRequestedZoneAcrossYearBoundary() {
        val timestamp = Instant.parse("2026-12-31T15:04:05Z").toEpochMilli()
        assertEquals("2027/01/01 00:04:05", formatNotificationTimestamp(timestamp, ZoneId.of("Asia/Tokyo")))
        assertEquals("2026/12/31 15:04:05", formatNotificationTimestamp(timestamp, ZoneId.of("UTC")))
    }
}
