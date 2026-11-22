package me.bubblespark.tidilo.data

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RssManualFetchWorkTest {
    @Test fun pendingWorkPreservesItsChoiceAcrossSchedulerReconciliation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Run this case on an offline test device so WorkManager cannot claim the pending requests.
        assumeTrue(connectivity.activeNetwork == null)
        val manager = WorkManager.getInstance(context)
        val name = RssScheduler.MANUAL
        fun pending() = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
        assumeTrue(pending().isEmpty())
        val db = AppDatabase.getInstance(context)
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        val original = preferences.state.value
        val feedId = "manual-test-${UUID.randomUUID()}"
        val ownedWork = mutableListOf<UUID>()
        try {
            db.rssDao().putFeed(RssFeedEntity(feedId, "https://$feedId.invalid/feed", "Manual test"))
            preferences.save(original.copy(wifiOnly = true))
            RssScheduler.fetchNow(context)
            val wait = pending().single().also { ownedWork.add(it.id) }
            assertNotNull(wait.constraints.requiredNetworkRequest)
            RssScheduler.fetchNow(context, mobileAllowed = true)
            assertEquals(wait.id, pending().single().id)
            assertTrue(RssScheduler.MANUAL_MOBILE !in pending().single().tags)
            manager.cancelWorkById(wait.id).result.get()

            RssScheduler.fetchNow(context, mobileAllowed = true)
            val consented = pending().single().also { ownedWork.add(it.id) }
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
            val later = pending().single().also { ownedWork.add(it.id) }
            assertNotNull(later.constraints.requiredNetworkRequest)
            assertTrue(RssScheduler.MANUAL_MOBILE !in later.tags)
        } finally {
            ownedWork.forEach { manager.cancelWorkById(it).result.get() }
            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(feedId))
            preferences.save(original)
            RssScheduler.reschedule(context)
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
