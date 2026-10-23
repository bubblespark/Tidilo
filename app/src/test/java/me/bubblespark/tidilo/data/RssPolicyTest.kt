package me.bubblespark.tidilo.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.rss.nextRssFetchAt
import me.bubblespark.tidilo.data.rss.selectRssPending
import org.junit.Assert.*
import org.junit.Test

class RssPolicyTest {
    @Test fun pendingTasksKeepNewestFiveIncludingExistingAiWaiters() {
        fun task(id: Int, waiting: Boolean = false) = ImportTaskEntity(id.toString(), "https://example.com/$id", "Post",
            if (waiting) "WAITING_AI" else "READY", "", createdAt = id.toLong(), rssFeedId = "feed", rssPublishedAt = id.toLong())
        val rows = selectRssPending(listOf(task(1, true), task(9, true)), (2..8).map { task(it) })
        assertEquals(listOf("9", "8", "7", "6", "5"), rows.map { it.id })
        assertEquals("WAITING_AI", rows.first().status)
    }
    @Test fun midnightScheduleUsesLocalWallClockAcrossDst() {
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), nextRssFetchAt(
            Instant.parse("2026-03-08T05:00:00Z"), LocalTime.MIDNIGHT, ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-10-23T15:00:00Z"), nextRssFetchAt(
            Instant.parse("2026-10-23T14:00:00Z"), LocalTime.MIDNIGHT, ZoneId.of("Asia/Tokyo")))
    }
}
