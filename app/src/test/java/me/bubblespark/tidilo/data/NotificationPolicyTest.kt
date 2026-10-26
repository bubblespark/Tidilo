package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.notifications.ReadyNotificationPolicy
import me.bubblespark.tidilo.data.notifications.ReadyNotificationAction
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
    @Test fun readyThresholdUpdatesOneNotificationAndRearmsBelowEleven() {
        val policy = ReadyNotificationPolicy()
        assertEquals(ReadyNotificationAction.CANCEL, policy.update(10, false))
        assertEquals(ReadyNotificationAction.POST, policy.update(11, false))
        assertEquals(ReadyNotificationAction.UPDATE, policy.update(12, true))
        assertEquals(ReadyNotificationAction.NONE, policy.update(12, true))
        assertEquals(ReadyNotificationAction.CANCEL, policy.update(10, true))
        assertEquals(ReadyNotificationAction.POST, policy.update(11, false))
    }

    @Test fun dismissedEpisodeDoesNotRepostOnEveryDatabaseUpdate() {
        val policy = ReadyNotificationPolicy()
        assertEquals(ReadyNotificationAction.POST, policy.update(11, false))
        assertEquals(ReadyNotificationAction.NONE, policy.update(12, false))
        assertEquals(ReadyNotificationAction.NONE, policy.update(15, false))
        policy.update(0, false)
        assertEquals(ReadyNotificationAction.POST, policy.update(11, false))
    }

    @Test fun restartPreservesAnAlreadyAlertedEpisode() {
        val policy = ReadyNotificationPolicy(aboveThreshold = true)
        assertEquals(ReadyNotificationAction.UPDATE, policy.update(15, true))
        val dismissed = ReadyNotificationPolicy(aboveThreshold = true)
        assertEquals(ReadyNotificationAction.NONE, dismissed.update(15, false))
    }

    @Test fun errorDetailUsesConcreteCauseAndHasSafeLength() {
        assertEquals("HTTP 404", notificationErrorDetail(IllegalStateException("HTTP 404")))
        assertEquals("Import failed", notificationErrorDetail(Exception()))
        assertTrue(notificationErrorDetail(Exception("x".repeat(2000))).length <= 500)
    }
}
