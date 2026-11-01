package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Data
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import me.bubblespark.tidilo.data.rss.RssFetchWorker
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssNotificationRunEntity
import me.bubblespark.tidilo.data.db.RssNotificationItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import me.bubblespark.tidilo.data.rss.startRssFetchRun
import me.bubblespark.tidilo.data.rss.startScheduledRssFetchRun
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Test

class RssDailySchedulingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun configured() = RssSettings(listOf(LocalTime.now().plusHours(3).withSecond(0).withNano(0),
        LocalTime.now().plusHours(3).plusMinutes(1).withSecond(0).withNano(0),
        LocalTime.now().plusHours(6).withSecond(0).withNano(0)).sorted(), dailyEnabled = true)
    private fun active(manager: WorkManager, name: String) = manager.getWorkInfosByTag("$name:triggers").get().filter { !it.state.isFinished }

    @Test fun overdueWorkManagerRequestsCoalesceInEitherOrderAndConcurrently() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.LocalDate.now(zone).minusDays(2)
        val now = date.plusDays(1).atTime(13, 0).atZone(zone).toInstant()
        val times = listOf(LocalTime.of(9, 0), LocalTime.NOON)
        val settings = RssSettings(times, dailyEnabled = true)
        for (order in 0..2) {
            val family = "rss-test-coalesce-${UUID.randomUUID()}"
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                db.rssDao().putFeed(RssFeedEntity("fixture", "https://fixture.invalid/feed", "Fixture"))
                val inputs = times.map { time ->
                    val minute = time.hour * 60 + time.minute
                    val boundary = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    val input = workDataOf("triggerMinuteOfDay" to minute, "requestedAt" to boundary)
                    val request = androidx.work.PeriodicWorkRequestBuilder<RssFetchWorker>(24, TimeUnit.HOURS)
                        .setInitialDelay(1, TimeUnit.DAYS).setInputData(input)
                        .addTag("$family:triggers").addTag("$family:minute:$minute").addTag("$family:at:$boundary").build()
                    manager.enqueueUniquePeriodicWork("$family-$minute", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request).result.get()
                    request.id to input
                }
                suspend fun start(index: Int) = RssScheduler.withRunStartLock {
                    startScheduledRssFetchRun(db, manager, family, inputs[index].first, inputs[index].second,
                        emptySet(), 0, settings, now)
                }
                val results = when (order) {
                    0 -> listOf(start(0), start(1))
                    1 -> listOf(start(1), start(0))
                    else -> kotlinx.coroutines.coroutineScope {
                        val a = async(kotlinx.coroutines.Dispatchers.IO) { start(0) }
                        val b = async(kotlinx.coroutines.Dispatchers.IO) { start(1) }
                        listOf(a.await(), b.await())
                    }
                }
                assertEquals(1, results.count { it != null })
                assertEquals(date.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                    requireNotNull(results.filterNotNull().single()).requestedAt)
            } finally { manager.cancelAllWorkByTag("$family:triggers").result.get(); db.close() }
        }
    }

    @Test fun olderPendingRunIsSkippedButUnfinishedRunResumes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.rssDao().putFeed(RssFeedEntity("fixture", "https://fixture.invalid/feed", "Fixture"))
            val settings = RssSettings(listOf(LocalTime.of(9, 0), LocalTime.NOON), dailyEnabled = true)
            val oldId = UUID.randomUUID()
            val old = workDataOf("triggerMinuteOfDay" to 540, "requestedAt" to 90L)
            val newer = workDataOf("triggerMinuteOfDay" to 720, "requestedAt" to 120L)
            val now = java.time.Instant.ofEpochMilli(130L)
            assertNull(startRssFetchRun(db, oldId, old, emptySet(), 0, settings, now, 120L))
            val newRun = startRssFetchRun(db, UUID.randomUUID(), newer, emptySet(), 0, settings, now, 120L)
            assertEquals(120L, requireNotNull(newRun).requestedAt)
            db.rssNotificationDao().putRun(RssNotificationRunEntity("unfinished", oldId.toString(), 90L, true))
            assertEquals("unfinished", startRssFetchRun(db, oldId, old, emptySet(), 1, settings, latestDue = 120L)?.id)
        } finally { db.close() }
    }

    @Test fun threeTimesCreateIndependentRequestsAndDeletingPreservesSiblings() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val family = "rss-test-three-${UUID.randomUUID()}"
        val settings = configured()
        try {
            RssScheduler.scheduleDaily(manager, settings, true, family)
            val original = active(manager, family)
            assertEquals(3, original.size)
            assertEquals(3, original.map { it.id }.distinct().size)
            settings.triggerTimes.forEach { time ->
                val minute = time.hour * 60 + time.minute
                assertEquals(1, original.count { "$family:minute:$minute" in it.tags })
            }
            RssScheduler.scheduleDaily(manager, settings, false, family)
            assertEquals(original.associate { it.id to it.nextScheduleTimeMillis }, active(manager, family).associate { it.id to it.nextScheduleTimeMillis })
            val removed = settings.triggerTimes.first()
            val removedTag = "$family:minute:${removed.hour * 60 + removed.minute}"
            RssScheduler.scheduleDaily(manager, settings.copy(triggerTimes = settings.triggerTimes.drop(1)), false, family)
            assertEquals(original.filter { removedTag !in it.tags }.map { it.id }.toSet(), active(manager, family).map { it.id }.toSet())
            assertTrue(requireNotNull(manager.getWorkInfoById(original.first { removedTag in it.tags }.id).get()).state.isFinished)
        } finally { manager.cancelAllWorkByTag("$family:triggers").result.get() }
    }

    @Test fun finishingOneTimeDoesNotRescheduleWaitingSibling() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val family = "rss-test-sibling-${UUID.randomUUID()}"
        try {
            val settings = configured()
            RssScheduler.scheduleDaily(manager, settings, true, family)
            val before = active(manager, family)
            val finishing = before.first()
            val sibling = before[1]
            db.rssNotificationDao().putRun(RssNotificationRunEntity("waiting", sibling.id.toString(), 123, true))
            RssScheduler.scheduleDaily(manager, settings, true, family, db.rssNotificationDao(), finishing.id)
            val after = active(manager, family).associateBy { it.id }
            before.filter { it.id != finishing.id }.forEach {
                assertEquals(it.nextScheduleTimeMillis, after.getValue(it.id).nextScheduleTimeMillis)
                assertEquals(it.runAttemptCount, after.getValue(it.id).runAttemptCount)
            }
            assertEquals(123L, db.rssNotificationDao().unfinishedRun(sibling.id.toString())?.requestedAt)
        } finally { manager.cancelAllWorkByTag("$family:triggers").result.get(); db.close() }
    }

    @Test fun legacyAndRemovedRunsRetireAfterFinalizationAndEmptyNeverSchedules() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val family = "rss-test-retire-${UUID.randomUUID()}"
        try {
            manager.enqueueUniqueWork(family, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).addTag("rss-notifications:legacy").build()).result.get()
            val legacy = manager.getWorkInfosForUniqueWork(family).get().first { !it.state.isFinished }
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("legacy", legacy.id.toString(), 1, true))
            RssScheduler.scheduleDaily(manager, RssSettings(dailyEnabled = true), true, family, dao)
            assertTrue(active(manager, family).isEmpty())
            assertFalse(requireNotNull(manager.getWorkInfoById(legacy.id).get()).state.isFinished)
            dao.finish("legacy")
            RssScheduler.scheduleDaily(manager, RssSettings(), true, family, dao, legacy.id)
            assertTrue(requireNotNull(manager.getWorkInfoById(legacy.id).get()).state.isFinished)
            assertTrue(dao.unpublishedRuns().any { it.id == "legacy" })
        } finally { manager.cancelUniqueWork(family).result.get(); manager.cancelAllWorkByTag("$family:triggers").result.get(); db.close() }
    }

    @Test fun obsoleteWorkerCannotStartButUnfinishedRunCanResume() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.rssDao().putFeed(RssFeedEntity("fixture", "https://fixture.invalid/feed", "Fixture"))
            val workId = UUID.randomUUID()
            val input = workDataOf("triggerMinuteOfDay" to 480, "requestedAt" to 123L)
            for (settings in listOf(RssSettings(), RssSettings(dailyEnabled = true),
                RssSettings(listOf(LocalTime.NOON), dailyEnabled = true))) {
                assertNull(RssScheduler.withRunStartLock { startRssFetchRun(db, workId, input, emptySet(), 0, settings) })
                assertNull(db.rssNotificationDao().latestRun(workId.toString()))
            }
            val valid = RssSettings(listOf(LocalTime.of(8, 0)), dailyEnabled = true)
            assertNull(RssScheduler.withRunStartLock { startRssFetchRun(db, workId, Data.EMPTY,
                setOf("rss-notifications:legacy"), 0, valid) })
            val started = RssScheduler.withRunStartLock { startRssFetchRun(db, workId, input, emptySet(), 0, valid) }
            assertEquals(123L, requireNotNull(started).requestedAt)
            val resumed = RssScheduler.withRunStartLock { startRssFetchRun(db, workId, input, emptySet(), 1, RssSettings()) }
            assertEquals(started.id, resumed?.id)
            val manual = RssScheduler.withRunStartLock { startRssFetchRun(db, UUID.randomUUID(),
                workDataOf("requestedAt" to 789L), emptySet(), 0, RssSettings()) }
            assertEquals(789L, manual?.requestedAt)
            db.rssDao().putFeed(RssFeedEntity("fixture", "https://fixture.invalid/feed", "Fixture", enabled = false))
            assertNull(RssScheduler.withRunStartLock { startRssFetchRun(db, UUID.randomUUID(), input, emptySet(), 0, valid) })
        } finally { db.close() }
    }

    @Test fun zoneAndWifiChangesRespectRetryState() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val family = "rss-test-zone-${UUID.randomUUID()}"
        val originalZone = java.util.TimeZone.getDefault()
        try {
            val settings = configured()
            RssScheduler.scheduleDaily(manager, settings, true, family)
            val before = active(manager, family)
            val retry = before.first()
            db.rssNotificationDao().putRun(RssNotificationRunEntity("retry", retry.id.toString(), 456, true))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
            RssScheduler.scheduleDaily(manager, settings.copy(wifiOnly = false), true, family, db.rssNotificationDao())
            val after = active(manager, family).associateBy { it.id }
            assertEquals(retry.nextScheduleTimeMillis, after.getValue(retry.id).nextScheduleTimeMillis)
            assertEquals(456L, db.rssNotificationDao().unfinishedRun(retry.id.toString())?.requestedAt)
            after.values.forEach { assertEquals(androidx.work.NetworkType.CONNECTED, it.constraints.requiredNetworkType) }
            assertTrue(after.values.all { it.tags.any { tag -> tag.endsWith(":false:Pacific/Honolulu") } })
        } finally {
            java.util.TimeZone.setDefault(originalZone)
            manager.cancelAllWorkByTag("$family:triggers").result.get()
            db.close()
        }
    }

    @Test fun wifiPolicyEditKeepsOverdueUnstartedRequestBoundary() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val family = "rss-test-overdue-${UUID.randomUUID()}"
        val time = LocalTime.now().minusHours(2).withSecond(0).withNano(0)
        val minute = time.hour * 60 + time.minute
        val requestedAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)
        val zone = java.time.ZoneId.systemDefault()
        val request = androidx.work.PeriodicWorkRequestBuilder<RssFetchWorker>(24, TimeUnit.HOURS)
            .setInputData(workDataOf("triggerMinuteOfDay" to minute, "requestedAt" to requestedAt))
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkRequest(
                android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET).build(),
                androidx.work.NetworkType.CONNECTED).build())
            .setNextScheduleTimeOverride(requestedAt)
            .addTag("$family:triggers").addTag("$family:minute:$minute").addTag("$family:zone:$zone")
            .addTag("$family:at:$requestedAt").addTag("rss-notifications:$minute:true:$zone").build()
        try {
            manager.enqueueUniquePeriodicWork("$family-$minute", androidx.work.ExistingPeriodicWorkPolicy.KEEP, request).result.get()
            assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(manager.getWorkInfoById(request.id).get()).state)
            assertTrue(requireNotNull(manager.getWorkInfoById(request.id).get()).nextScheduleTimeMillis < System.currentTimeMillis())
            val edited = RssSettings(listOf(time, time.plusHours(5)).sorted(), dailyEnabled = true)
            RssScheduler.scheduleDaily(manager, edited, false, family)
            assertEquals("Adding another time must not move the overdue sibling", requestedAt,
                requireNotNull(manager.getWorkInfoById(request.id).get()).nextScheduleTimeMillis)
            RssScheduler.scheduleDaily(manager, edited.copy(wifiOnly = false), false, family)
            val spec = requireNotNull(androidx.work.impl.WorkManagerImpl.getInstance(context).workDatabase
                .workSpecDao().getWorkSpec(request.id.toString()))
            assertEquals("A policy edit must retain the overdue request, not tomorrow's boundary", requestedAt,
                spec.input.getLong("requestedAt", -1))
            assertEquals(minute, spec.input.getInt("triggerMinuteOfDay", -1))
        } finally { manager.cancelAllWorkByTag("$family:triggers").result.get() }
    }

    @Test fun disablingDefersCancellationUntilUnfinishedRunIsFinalized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = WorkManager.getInstance(context)
        val daily = "rss-test-recovery-${UUID.randomUUID()}"
        val next = LocalTime.now().plusHours(1)
        val settings = RssSettings(listOf(LocalTime.of(next.hour, next.minute)), dailyEnabled = true)
        try {
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            val work = active(manager, daily).first()
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", work.id.toString(), 1, true))
            dao.putItem(RssNotificationItemEntity("run", "item", "PENDING"))
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily, dao)
            assertTrue(active(manager, daily).isNotEmpty())
            assertEquals("run", dao.pendingOwner("item"))
            dao.finish("run")
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily, dao, work.id)
            assertTrue(active(manager, daily).isEmpty())
            assertTrue(dao.unpublishedRuns().any { it.id == "run" })
        } finally {
            manager.cancelAllWorkByTag("$daily:triggers").result.get()
            db.close()
        }
    }

    @Test fun disablingDailyCancelsOnlyDailyAndReenablingSchedulesIt() = runBlocking {
        val manager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val suffix = UUID.randomUUID().toString()
        val daily = "rss-test-daily-$suffix"
        val manual = "rss-test-manual-$suffix"
        val next = LocalTime.now().plusHours(1)
        val settings = RssSettings(listOf(LocalTime.of(next.hour, next.minute)), dailyEnabled = true)
        try {
            manager.enqueueUniqueWork(manual, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInitialDelay(1, TimeUnit.HOURS).build()).result.get()
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            assertTrue(active(manager, daily).isNotEmpty())
            RssScheduler.scheduleDaily(manager, settings.copy(dailyEnabled = false), true, daily)
            assertTrue(active(manager, daily).isEmpty())
            assertTrue(manager.getWorkInfosForUniqueWork(manual).get().any { it.state == WorkInfo.State.ENQUEUED })
            RssScheduler.scheduleDaily(manager, settings, true, daily)
            assertTrue(active(manager, daily).isNotEmpty())
        } finally {
            manager.cancelAllWorkByTag("$daily:triggers").result.get()
            manager.cancelUniqueWork(manual).result.get()
        }
    }
}
