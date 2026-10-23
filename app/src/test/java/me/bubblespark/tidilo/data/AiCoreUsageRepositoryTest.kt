package me.bubblespark.tidilo.data

import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiCoreUsageRepositoryTest {
    private class MemoryStorage : AiUsageStorage {
        var text: String? = null
        var writable = true
        override fun read() = text
        override fun write(value: String): Boolean {
            if (writable) text = value
            return writable
        }
    }
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }

    @Test fun tracksConcurrentSourcesAndOnlyFirstQuotaPerStartDay() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-23T12:00:00Z"))
        val repo = AiCoreUsageRepository(MemoryStorage(), clock) { ZoneOffset.UTC }
        val ids = (0 until 20).map { async(Dispatchers.Default) { repo.begin(if (it % 2 == 0) AiUsageSource.DEBUG else AiUsageSource.NORMAL) } }.awaitAll()
        repo.complete(ids[0], AiUsageOutcome.FAILED, true)
        ids.drop(1).forEach { repo.complete(it, AiUsageOutcome.SUCCESS, false) }
        val extra = repo.begin(AiUsageSource.NORMAL)
        repo.complete(extra, AiUsageOutcome.FAILED, true)
        repo.complete(extra, AiUsageOutcome.SUCCESS, false)
        val state = (repo.state.value as AiUsageState.Ready).snapshot
        val day = state.days.getValue(LocalDate.parse("2026-10-23"))
        assertEquals(21L, day.total)
        assertEquals(19L, day.success)
        assertEquals(2L, day.failed)
        assertEquals(10L, day.debug)
        assertEquals(0L, day.pending)
        assertEquals(20L, state.lastQuota!!.calls)
    }

    @Test fun midnightCompletionBelongsToStartDateAndRestartRecoversPending() = runBlocking {
        val storage = MemoryStorage()
        val clock = MutableClock(Instant.parse("2026-10-23T23:59:59Z"))
        val repo = AiCoreUsageRepository(storage, clock) { ZoneOffset.UTC }
        val id = repo.begin(AiUsageSource.NORMAL)
        clock.now = Instant.parse("2026-10-24T00:00:01Z")
        repo.complete(id, AiUsageOutcome.SUCCESS, false)
        repo.begin(AiUsageSource.DEBUG)
        val reopened = AiCoreUsageRepository(storage, clock) { ZoneOffset.UTC }
        reopened.initialize()
        val snapshot = (reopened.state.value as AiUsageState.Ready).snapshot
        assertEquals(1L, snapshot.days.getValue(LocalDate.parse("2026-10-23")).success)
        assertEquals(1L, snapshot.days.getValue(LocalDate.parse("2026-10-24")).interrupted)
        assertEquals(0L, snapshot.days.getValue(LocalDate.parse("2026-10-24")).pending)
        assertEquals(listOf(false, false, false, false, false, true, true), snapshot.week(LocalDate.parse("2026-10-24")).map { it.recorded })
    }

    @Test fun trackerPreservesErrorsCancellationAndInferenceSuccess() = runBlocking {
        val repo = AiCoreUsageRepository(MemoryStorage(), Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)) { ZoneOffset.UTC }
        val error = IllegalStateException("quota fixture")
        val tracker = AiUsageTracker(repo) { it === error }
        assertEquals("raw generation", tracker.track(AiUsageSource.NORMAL) { "raw generation" })
        try { tracker.track(AiUsageSource.DEBUG) { throw error }; fail() } catch (actual: IllegalStateException) { assertSame(error, actual) }
        val entered = CompletableDeferred<Unit>()
        val request = launch { tracker.track(AiUsageSource.NORMAL) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); request.cancelAndJoin()
        val state = (repo.state.value as AiUsageState.Ready).snapshot
        val day = state.days.values.single()
        assertEquals(3L, day.total)
        assertEquals(1L, day.success)
        assertEquals(1L, day.failed)
        assertEquals(1L, day.cancelled)
        assertEquals(0L, day.pending)
        assertEquals(2L, state.lastQuota!!.calls)
    }

    @Test fun failedPersistenceDoesNotBlockInferenceAndRetrySavesCurrentTotals() = runBlocking {
        val storage = MemoryStorage().apply { writable = false }
        val repo = AiCoreUsageRepository(storage)
        assertEquals(42, AiUsageTracker(repo) { false }.track(AiUsageSource.NORMAL) { 42 })
        assertTrue((repo.state.value as AiUsageState.Ready).persistenceFailed)
        storage.writable = true
        repo.initialize()
        assertFalse((repo.state.value as AiUsageState.Ready).persistenceFailed)
        val reopened = AiCoreUsageRepository(storage)
        reopened.initialize()
        assertEquals(1L, (reopened.state.value as AiUsageState.Ready).snapshot.days.values.single().success)
    }

    @Test fun corruptStorageIsUnavailableButDoesNotPreventGeneration() = runBlocking {
        val repo = AiCoreUsageRepository(MemoryStorage().apply { text = "not json" })
        assertEquals(7, AiUsageTracker(repo) { false }.track(AiUsageSource.NORMAL) { 7 })
        assertTrue(repo.state.value is AiUsageState.Error)
    }

    @Test fun currentLocalDateUsesNewTimezoneWithoutReassigningHistory() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-23T23:30:00Z"))
        var zone: ZoneId = ZoneOffset.UTC
        val repo = AiCoreUsageRepository(MemoryStorage(), clock) { zone }
        val first = repo.begin(AiUsageSource.NORMAL)
        zone = ZoneId.of("Asia/Tokyo")
        repo.complete(first, AiUsageOutcome.SUCCESS, false)
        val second = repo.begin(AiUsageSource.NORMAL)
        repo.complete(second, AiUsageOutcome.SUCCESS, false)
        val days = (repo.state.value as AiUsageState.Ready).snapshot.days
        assertEquals(1L, days.getValue(LocalDate.parse("2026-10-23")).total)
        assertEquals(1L, days.getValue(LocalDate.parse("2026-10-24")).total)
    }

    @Test fun cancellationDuringStartPersistenceDoesNotCountAnUncalledSdk() = runBlocking {
        val memory = MemoryStorage()
        lateinit var request: Job
        var cancelOnWrite = false
        val repo = AiCoreUsageRepository(object : AiUsageStorage {
            override fun read() = memory.read()
            override fun write(value: String): Boolean {
                if (cancelOnWrite) { cancelOnWrite = false; request.cancel() }
                return memory.write(value)
            }
        })
        repo.initialize()
        var entered = false
        request = launch(start = CoroutineStart.LAZY) {
            AiUsageTracker(repo) { false }.track(AiUsageSource.DEBUG) { entered = true }
        }
        cancelOnWrite = true
        request.start(); request.join()
        assertFalse(entered)
        val day = (repo.state.value as AiUsageState.Ready).snapshot.days.values.single()
        assertEquals(0L, day.total)
        assertEquals(0L, day.debug)
        assertEquals(0L, day.pending)
        assertEquals(0L, day.cancelled)
    }

    @Test fun discardedReservationsOnlyAdjustQuotaBenchmarksThatIncludedThem() = runBlocking {
        val storage = MemoryStorage()
        val repository = AiCoreUsageRepository(storage)
        val cancelledBeforeQuota = repository.begin(AiUsageSource.DEBUG)
        val failed = repository.begin(AiUsageSource.NORMAL)
        repository.complete(failed, AiUsageOutcome.FAILED, true)
        repository.discardUnstarted(cancelledBeforeQuota, AiUsageSource.DEBUG)
        val cancelledAfterQuota = repository.begin(AiUsageSource.DEBUG)
        repository.discardUnstarted(cancelledAfterQuota, AiUsageSource.DEBUG)
        val reopened = AiCoreUsageRepository(storage)
        reopened.initialize()
        val snapshot = (reopened.state.value as AiUsageState.Ready).snapshot
        assertEquals(1L, snapshot.lastQuota!!.calls)
        val day = snapshot.days.values.single()
        assertEquals(1L, day.total)
        assertEquals(1L, day.failed)
        assertEquals(0L, day.pending)
        assertEquals(0L, day.debug)
        assertEquals(snapshot.lastQuota, day.firstQuota)
    }
}
