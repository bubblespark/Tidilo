package me.bubblespark.tidilo.data

import android.annotation.SuppressLint
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.rss.RssFetchWorker
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus
import me.bubblespark.tidilo.ui.screens.settings.manualFetchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RssManualFetchWorkTest {
    @SuppressLint("RestrictedApi")
    @Test fun pendingWorkPreservesItsChoiceAcrossSchedulerReconciliation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        val original = preferences.state.value
        val feedId = "manual-test-${UUID.randomUUID()}"
        val defaultManager = WorkManager.getInstance(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val manager = WorkManager.getInstance(context)
        fun pending() = manager.getWorkInfosForUniqueWork(RssScheduler.MANUAL).get().filter { !it.state.isFinished }
        try {
            assertNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            db.rssDao().putFeed(RssFeedEntity(feedId, "https://$feedId.invalid/feed", "Manual test"))
            preferences.save(original.copy(wifiOnly = true))
            RssScheduler.fetchNow(context)
            val wait = pending().single()
            assertNotNull(wait.constraints.requiredNetworkRequest)
            RssScheduler.reschedule(context, finishingWorkId = wait.id)
            assertEquals(wait.generation, pending().single().generation)
            RssScheduler.fetchNow(context, mobileAllowed = true)
            assertEquals(wait.id, pending().single().id)
            assertTrue(RssScheduler.MANUAL_MOBILE !in pending().single().tags)
            preferences.save(original.copy(wifiOnly = false))
            RssScheduler.reschedule(context)
            val ordinaryUpdate = pending().single()
            assertEquals(wait.id, ordinaryUpdate.id)
            assertNull(ordinaryUpdate.constraints.requiredNetworkRequest)
            manager.cancelWorkById(wait.id).result.get()
            preferences.save(original.copy(wifiOnly = true))

            RssScheduler.fetchNow(context, mobileAllowed = true)
            val consented = pending().single()
            assertNull(consented.constraints.requiredNetworkRequest)
            assertTrue(RssScheduler.MANUAL_MOBILE in consented.tags)
            RssScheduler.reschedule(context)
            val reconciled = pending().single()
            assertEquals(consented.id, reconciled.id)
            assertEquals(consented.tags, reconciled.tags)
            assertNull(reconciled.constraints.requiredNetworkRequest)
            assertTrue(preferences.state.value.wifiOnly)
            manager.cancelWorkById(consented.id).result.get()

            RssScheduler.fetchNow(context)
            val later = pending().single()
            assertNotNull(later.constraints.requiredNetworkRequest)
            assertTrue(RssScheduler.MANUAL_MOBILE !in later.tags)
        } finally {
            try {
                manager.cancelAllWork().result.get()
                WorkManagerTestInitHelper.closeWorkDatabase()
            } finally {
                WorkManagerImpl.setDelegate(null)
                withContext(NonCancellable + Dispatchers.IO) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(feedId))
                    preferences.save(original)
                }
                assertSame(defaultManager, WorkManager.getInstance(context))
            }
        }
    }

    @Test fun anUnfinishedManualRequestRejectsBothKindsOfDuplicate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val name = RssScheduler.MANUAL
        assumeTrue(manager.getWorkInfosForUniqueWork(name).get().none { !it.state.isFinished })
        val pending = OneTimeWorkRequestBuilder<RssFetchWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        try {
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, pending).result.get()
            listOf(
                async { RssScheduler.fetchNow(context) },
                async { RssScheduler.fetchNow(context, mobileAllowed = true) }
            ).awaitAll()
            val unfinished = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
            assertEquals(listOf(pending.id), unfinished.map { it.id })
            assertEquals(ManualFetchStatus.Waiting, manualFetchStatus(unfinished.map { it.state }))
        } finally {
            manager.cancelWorkById(pending.id).result.get()
        }
    }

    @Test fun queuedRequestRemainsWaitingUntilCancelled() = runBlocking {
        val manager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "rss-test-manual-${UUID.randomUUID()}"
        try {
            manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInitialDelay(1, TimeUnit.HOURS).build()).result.get()
            val queued = withTimeout(5000) { manager.getWorkInfosForUniqueWorkFlow(name).first { it.isNotEmpty() } }
            assertEquals(ManualFetchStatus.Waiting, manualFetchStatus(queued.map { it.state }))
            manager.cancelUniqueWork(name).result.get()
            val cancelled = withTimeout(5000) { manager.getWorkInfosForUniqueWorkFlow(name).first { infos ->
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            } }
            assertEquals(ManualFetchStatus.Idle, manualFetchStatus(cancelled.map { it.state }))
        } finally {
            manager.cancelUniqueWork(name).result.get()
        }
    }
}
