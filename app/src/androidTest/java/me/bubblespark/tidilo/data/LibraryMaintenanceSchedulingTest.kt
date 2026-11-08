package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.maintenance.LibraryMaintenanceScheduler
import me.bubblespark.tidilo.data.maintenance.runLibraryMaintenanceAttempt
import org.junit.Assert.*
import org.junit.Test

class LibraryMaintenanceSchedulingTest {
    @Test fun repeatedSchedulingIsUniqueOfflineAndPreservesOverdueBoundary() = runBlocking {
        val manager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "maintenance-test-${UUID.randomUUID()}"
        val now = Instant.now()
        val zone = ZoneId.of("Asia/Tokyo")
        try {
            LibraryMaintenanceScheduler.schedule(manager, now, zone, name = name)
            fun active() = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
            val first = active().single()
            assertEquals(NetworkType.NOT_REQUIRED, first.constraints.requiredNetworkType)
            assertNull(first.constraints.requiredNetworkRequest)
            LibraryMaintenanceScheduler.schedule(manager, now.plusSeconds(3600), zone, name = name)
            assertEquals(first.id, active().single().id)
            assertEquals(first.tags, active().single().tags)
            // Clock jumps forward: an overdue request must not be postponed even on realignment.
            LibraryMaintenanceScheduler.schedule(manager, now.plusSeconds(3 * 86_400),
                ZoneId.of("America/New_York"), realignClock = true, name = name)
            assertEquals(first.tags, active().single().tags)
            assertEquals(WorkInfo.State.ENQUEUED, active().single().state)
        } finally { manager.cancelUniqueWork(name).result.get() }
    }

    @Test fun futureBoundaryRealignsOnTimezoneChangeAndSuccess() = runBlocking {
        val manager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val name = "maintenance-test-${UUID.randomUUID()}"
        val now = Instant.now().plusSeconds(10 * 86_400)
        try {
            LibraryMaintenanceScheduler.schedule(manager, now, ZoneId.of("Asia/Tokyo"), name = name)
            val first = manager.getWorkInfosForUniqueWork(name).get().first { !it.state.isFinished }
            LibraryMaintenanceScheduler.schedule(manager, now, ZoneId.of("America/New_York"),
                realignClock = true, name = name)
            val updated = manager.getWorkInfosForUniqueWork(name).get().first { !it.state.isFinished }
            assertEquals(first.id, updated.id)
            assertTrue(updated.tags.contains("$name:zone:America/New_York"))
            assertNotEquals(first.tags, updated.tags)
        } finally { manager.cancelUniqueWork(name).result.get() }
    }

    @Test fun failuresRetryWithoutRealignmentAndCancellationPropagates() = runBlocking {
        var aligned = false
        assertEquals(ListenableWorker.Result.retry(), runLibraryMaintenanceAttempt(
            clean = { throw IOException("fixture") }, realign = { aligned = true }))
        assertFalse(aligned)
        try {
            runLibraryMaintenanceAttempt(clean = { throw CancellationException("fixture") }, realign = { aligned = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(aligned)
        assertEquals(ListenableWorker.Result.success(), runLibraryMaintenanceAttempt(clean = {}, realign = { aligned = true }))
        assertTrue(aligned)
    }
}
