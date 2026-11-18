package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.notifications.notificationErrorDetail
import me.bubblespark.tidilo.data.notifications.shouldOfferNotificationPermissionRationale
import me.bubblespark.tidilo.data.notifications.shouldPromptNotificationPermissionOnRssEvent
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

    @Test fun notificationPermissionPromptTriggersOnlyOnInitialDailyEnableOrRegistrationWhenUnrequested() {
        assertTrue(shouldOfferNotificationPermissionRationale(permissionGranted = false, alreadyRequested = false))
        assertFalse(shouldOfferNotificationPermissionRationale(permissionGranted = true, alreadyRequested = false))
        assertFalse(shouldOfferNotificationPermissionRationale(permissionGranted = false, alreadyRequested = true))
        assertFalse(shouldOfferNotificationPermissionRationale(permissionGranted = true, alreadyRequested = true))

        assertTrue(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = false,
            previousDailyEnabled = false,
            newDailyEnabled = true
        ))
        assertTrue(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = false,
            initialRegistrationCompleted = true
        ))

        assertFalse(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = false,
            previousDailyEnabled = true,
            newDailyEnabled = true
        ))
        assertFalse(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = false,
            previousDailyEnabled = true,
            newDailyEnabled = false
        ))
        assertFalse(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = false
        ))
        assertFalse(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = false,
            alreadyRequested = true,
            previousDailyEnabled = false,
            newDailyEnabled = true
        ))
        assertFalse(shouldPromptNotificationPermissionOnRssEvent(
            permissionGranted = true,
            alreadyRequested = false,
            initialRegistrationCompleted = true
        ))
    }
}
