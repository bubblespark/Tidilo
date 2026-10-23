package me.bubblespark.tidilo.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class OgImageFetchCoordinatorTest {
    @Test
    fun tenRequestsShareFourPermits() = runBlocking {
        val coordinator = OgImageFetchCoordinator<Int>()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = (0 until 10).map { key -> async {
            coordinator.fetch(key.toString(), 0) {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                entered.send(Unit)
                try { release.receive(); key } finally { active.decrementAndGet() }
            }
        } }
        withTimeout(5000) { repeat(4) { entered.receive() } }
        assertEquals(4, active.get())
        repeat(10) { release.send(Unit) }
        assertEquals((0 until 10).toList(), withTimeout(5000) { requests.awaitAll() })
        assertEquals(4, peak.get())
    }

    @Test
    fun oneCancelledWaiterDoesNotCancelSharedSuccess() = runBlocking {
        val coordinator = OgImageFetchCoordinator<Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.fetch("same", 0) {
            calls.incrementAndGet(); entered.complete(Unit); release.await(); 42
        } }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.fetch("same", 0) { error("Duplicate") } }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals(42, withTimeout(5000) { second.await() })
        assertEquals(1, calls.get())
    }

    @Test
    fun lastCancellationStopsWorkAndAllowsImmediateRetry() = runBlocking {
        val coordinator = OgImageFetchCoordinator<Int>()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val first = async { coordinator.fetch("same", 0) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        entered.await()
        first.cancelAndJoin()
        withTimeout(5000) { stopped.await() }
        assertEquals(9, coordinator.fetch("same", 0) { 9 })
    }

    @Test
    fun cancelledQueuedRequestNeverRunsOrPoisonsRetries() = runBlocking {
        val coordinator = OgImageFetchCoordinator<Int>()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val busy = (0 until 4).map { async { coordinator.fetch("busy$it", 0) {
            entered.send(Unit); release.await(); 1
        } } }
        repeat(4) { entered.receive() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.fetch("queued", 0) { error("Cancelled queue started I/O") }
        }
        queued.cancelAndJoin()
        release.complete(Unit)
        busy.awaitAll()
        assertEquals(7, coordinator.fetch("queued", 0) { 7 })
    }

    @Test
    fun transientFailuresExpireAndPermanentFailuresResetOnClear() = runBlocking {
        var now = 0L
        val coordinator = OgImageFetchCoordinator<Int>(nowMillis = { now })
        assertNull(coordinator.fetch("temporary", 0) { throw IOException("Offline") })
        assertNull(coordinator.fetch("temporary", 0) { error("Backoff bypassed") })
        now = 60_000
        assertEquals(5, coordinator.fetch("temporary", 0) { 5 })
        assertNull(coordinator.fetch("invalid", 0) { throw PermanentOgImageFailure() })
        now = 120_000
        assertNull(coordinator.fetch("invalid", 0) { error("Permanent failure retried") })
        coordinator.clear()
        assertEquals(6, coordinator.fetch("invalid", 1) { 6 })
    }

    @Test
    fun clearingCancelsOldWorkAndRejectsItsFailure() = runBlocking {
        val coordinator = OgImageFetchCoordinator<Int>()
        val entered = CompletableDeferred<Unit>()
        val old = async { coordinator.fetch("same", 0) {
            entered.complete(Unit)
            withContext(NonCancellable) { delay(20) }
            throw PermanentOgImageFailure()
        } }
        entered.await()
        coordinator.clear()
        old.join()
        assertEquals(12, coordinator.fetch("same", 1) { 12 })
        assertNull(coordinator.fetch("different", 0) { error("Stale generation executed") })
    }
}
