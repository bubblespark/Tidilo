package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.rss.RssFetchWorker
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssNotificationRunEntity
import me.bubblespark.tidilo.data.db.RssNotificationItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssDailySchedulingTest {
    @Test fun disablingDefersCancellationUntilUnfinishedRunIsFinalized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = WorkManager.getInstance(context)
        val daily = "rss-test-recovery-${UUID.randomUUID()}"
        val next = LocalTime.now().plusHours(1)
        val settings = RssSettings(hour = next.hour, minute = next.minute)
        try {
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            val work = manager.getWorkInfosForUniqueWork(daily).get().first { !it.state.isFinished }
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", work.id.toString(), 1, true))
            dao.putItem(RssNotificationItemEntity("run", "item", "PENDING"))
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily, dao)
            assertTrue(manager.getWorkInfosForUniqueWork(daily).get().any { !it.state.isFinished })
            assertEquals("run", dao.pendingOwner("item"))
            dao.finish("run")
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily, dao, work.id)
            assertTrue(manager.getWorkInfosForUniqueWork(daily).get().all { it.state.isFinished })
            assertTrue(dao.unpublishedRuns().any { it.id == "run" })
        } finally {
            manager.cancelUniqueWork(daily).result.get()
            db.close()
        }
    }

    @Test fun disablingDailyCancelsOnlyDailyAndReenablingSchedulesIt() = runBlocking {
        val manager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val suffix = UUID.randomUUID().toString()
        val daily = "rss-test-daily-$suffix"
        val manual = "rss-test-manual-$suffix"
        val next = LocalTime.now().plusHours(1)
        val settings = RssSettings(hour = next.hour, minute = next.minute)
        try {
            manager.enqueueUniqueWork(manual, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInitialDelay(1, TimeUnit.HOURS).build()).result.get()
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            assertTrue(manager.getWorkInfosForUniqueWork(daily).get().any { !it.state.isFinished })
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily)
            assertTrue(manager.getWorkInfosForUniqueWork(daily).get().all { it.state.isFinished })
            assertTrue(manager.getWorkInfosForUniqueWork(manual).get().any { it.state == WorkInfo.State.ENQUEUED })
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            assertTrue(manager.getWorkInfosForUniqueWork(daily).get().any { !it.state.isFinished })
        } finally {
            manager.cancelUniqueWork(daily).result.get()
            manager.cancelUniqueWork(manual).result.get()
        }
    }
}
