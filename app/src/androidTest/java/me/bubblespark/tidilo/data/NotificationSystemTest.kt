package me.bubblespark.tidilo.data

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.data.notifications.NotificationCategory
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssNotificationRunEntity
import me.bubblespark.tidilo.data.db.RssNotificationItemEntity
import java.util.UUID

class NotificationSystemTest {
    @Test fun hundredArticleErrorsProduceOneImportErrorAndOneSummaryWithoutReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(NotificationManager::class.java)
        val coordinator = NotificationCoordinator.getInstance(context)
        val dao = AppDatabase.getInstance(context).rssNotificationDao()
        val runId = "test-${UUID.randomUUID()}"
        try {
            dao.putRun(RssNotificationRunEntity(runId, runId, System.currentTimeMillis(), true))
            for (index in 1..100) dao.putItem(RssNotificationItemEntity(runId, "$index", "IMPORT_ERROR", "HTTP 404"))
            dao.finish(runId)
            coordinator.publishCompletedRssRuns()
            val notices = manager.activeNotifications.filter { it.tag == runId }
            assertEquals(2, notices.size)
            val errors = notices.single { it.notification.channelId == NotificationCategory.IMPORT_ERROR.channelId }
            assertTrue(errors.notification.extras.getCharSequence("android.text").toString().contains("100 RSS articles"))
            assertTrue(notices.none { it.notification.channelId == NotificationCategory.RSS_ERROR.channelId })
            val times = notices.associate { it.id to it.postTime }
            coordinator.publishCompletedRssRuns()
            assertEquals(times, manager.activeNotifications.filter { it.tag == runId }.associate { it.id to it.postTime })
        } finally {
            for (category in NotificationCategory.entries) manager.cancel(runId, category.id)
            // Remove only this test's isolated bookkeeping records.
            val sql = AppDatabase.getInstance(context).openHelper.writableDatabase
            sql.execSQL("DELETE FROM rss_notification_items WHERE runId = ?", arrayOf(runId))
            sql.execSQL("DELETE FROM rss_notification_runs WHERE id = ?", arrayOf(runId))
        }
    }

    @Test fun fiveChannelsAndDestinationsUseStandardNotifications() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val coordinator = NotificationCoordinator.getInstance(context)
        coordinator.createChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(listOf("URLs Pending AI Analysis", "Import Errors", "RSS Scheduled Updates", "RSS Errors", "AICore Errors"),
            NotificationCategory.entries.map { manager.getNotificationChannel(it.channelId).name.toString() })
        for (category in NotificationCategory.entries) {
            val notification = coordinator.buildNotification(category, "First line\nSecond line")
            assertEquals(category.channelId, notification.channelId)
            assertEquals("First line\nSecond line", notification.extras.getCharSequence("android.bigText").toString())
            assertNotNull(notification.contentIntent)
            assertEquals(0, notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
        }
        assertEquals("import", NotificationCategory.READY.destination)
        assertEquals("import", NotificationCategory.IMPORT_ERROR.destination)
        assertEquals("library", NotificationCategory.RSS_UPDATES.destination)
        assertEquals("settings_rss", NotificationCategory.RSS_ERROR.destination)
        assertEquals("import", NotificationCategory.AICORE_ERROR.destination)
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, coordinator.settingsIntent().action)
        assertEquals(context.packageName, coordinator.settingsIntent().getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }
}
