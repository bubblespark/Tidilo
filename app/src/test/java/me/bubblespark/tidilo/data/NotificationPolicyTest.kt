package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.notifications.notificationErrorDetail
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun lateWorkerRetryKeepsFinishedRunAcrossNextScheduledBoundary() {
        val original = me.bubblespark.tidilo.data.db.RssNotificationRunEntity("original", "work", 100, true, completed = true)
        assertEquals(original, me.bubblespark.tidilo.data.notifications.selectRssNotificationRun(
            unfinished = null, matchingBoundary = null, latest = original, retryAttempt = 1))
        assertNull(me.bubblespark.tidilo.data.notifications.selectRssNotificationRun(
            unfinished = null, matchingBoundary = null, latest = original, retryAttempt = 0))
        assertNull(me.bubblespark.tidilo.data.notifications.selectRssNotificationRun(
            unfinished = null, matchingBoundary = null, latest = original, retryAttempt = 1, explicitBoundary = true))
    }
    @Test fun errorDetailUsesConcreteCauseAndHasSafeLength() {
        assertEquals("HTTP 404", notificationErrorDetail(IllegalStateException("HTTP 404")))
        assertEquals("Import failed", notificationErrorDetail(Exception()))
        assertTrue(notificationErrorDetail(Exception("x".repeat(2000))).length <= 500)
    }
}
