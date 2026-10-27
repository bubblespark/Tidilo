package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.rss.RssFetchWorker
import me.bubblespark.tidilo.ui.screens.settings.ManualFetchStatus
import me.bubblespark.tidilo.ui.screens.settings.manualFetchStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RssManualFetchWorkTest {
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
