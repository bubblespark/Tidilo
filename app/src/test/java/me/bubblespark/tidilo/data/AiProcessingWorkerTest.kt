package me.bubblespark.tidilo.data

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProcessingWorkerTest {
    private class Session

    @Test
    fun taskCancellationRecoversAndLaterWakeUpStillProcessesWork() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1))
        val firstClosed = CompletableDeferred<Unit>()
        val secondClosed = CompletableDeferred<Unit>()
        val failures = mutableListOf<Int>()
        val processed = mutableListOf<Int>()
        var closes = 0
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), { queue.poll() }, { Session() },
                { if (++closes == 1) firstClosed.complete(Unit) else secondClosed.complete(Unit) },
                { _, task ->
                    if (task == 1) throw CancellationException("Inference interrupted")
                    processed.add(task)
                },
                { task, exception ->
                    assertTrue(exception is CancellationException)
                    failures.add(task)
                },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { firstClosed.await() }
            assertTrue(job.isActive)
            assertEquals(listOf(1), failures)
            queue.add(2)
            worker.wakeUp()
            withTimeout(5000) { secondClosed.await() }
            assertEquals(listOf(2), processed)
            assertEquals(2, closes)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun sessionCancellationWithActiveScopeDoesNotTerminateSignalConsumer() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val failed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var firstQuery = true
        var pending = true
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), {
                    if (firstQuery) {
                        firstQuery = false
                        throw CancellationException("Query interrupted")
                    }
                    if (pending) { pending = false; 1 } else null
                }, { Session() }, { finished.complete(Unit) },
                { _, _ -> },
                { _, exception -> throw AssertionError(exception) },
                { exception ->
                    assertTrue(exception is CancellationException)
                    failed.complete(Unit)
                }
            )
            worker.wakeUp()
            withTimeout(5000) { failed.await() }
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
            assertTrue(job.isActive)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun batchAndRepeatedTriggersShareOneSession() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1, 2, 3))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val sessions = mutableListOf<Session>()
        val processed = mutableListOf<Int>()
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), { queue.poll() },
                { opens.incrementAndGet(); Session() },
                { closed.complete(Unit) },
                { session, task ->
                    sessions.add(session)
                    processed.add(task)
                    if (task == 1) { entered.complete(Unit); release.await() }
                },
                { _, exception -> throw AssertionError(exception) },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { entered.await() }
            repeat(100) { scope.launch { worker.wakeUp() } }
            queue.add(4)
            worker.wakeUp()
            release.complete(Unit)
            withTimeout(5000) { closed.await() }
            yield()
            assertEquals(listOf(1, 2, 3, 4), processed)
            assertEquals(1, opens.get())
            assertTrue(sessions.all { it === sessions.first() })
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun workAddedWhileSessionClosesStartsAnotherSession() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1))
        val processed = mutableListOf<Int>()
        val finished = CompletableDeferred<Unit>()
        var closes = 0
        var opens = 0
        lateinit var worker: AiProcessingWorker<Int, Session>
        try {
            worker = AiProcessingWorker(
                scope, Mutex(), { queue.poll() }, { opens++; Session() },
                {
                    closes++
                    if (closes == 1) { queue.add(2); worker.wakeUp() }
                    else finished.complete(Unit)
                },
                { _, task -> processed.add(task) },
                { _, exception -> throw AssertionError(exception) },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
            assertEquals(listOf(1, 2), processed)
            assertEquals(2, opens)
            assertEquals(2, closes)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun triggerAfterEmptyQueryIsNotLost() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue<Int>()
        val finished = CompletableDeferred<Unit>()
        var firstQuery = true
        lateinit var worker: AiProcessingWorker<Int, Session>
        try {
            worker = AiProcessingWorker(
                scope, Mutex(), {
                    if (firstQuery) {
                        firstQuery = false
                        queue.add(1)
                        worker.wakeUp()
                        null
                    } else queue.poll()
                }, { Session() }, { finished.complete(Unit) },
                { _, task -> assertEquals(1, task) },
                { _, exception -> throw AssertionError(exception) },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun taskFailureAwaitsRecoveryBeforeSelectingNextTask() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1, 2))
        val failed = CompletableDeferred<Unit>()
        val recover = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val processed = mutableListOf<Int>()
        var opens = 0
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), { queue.poll() }, { opens++; Session() },
                { closed.complete(Unit) },
                { _, task -> processed.add(task); if (task == 1) error("busy") },
                { task, _ -> assertEquals(1, task); failed.complete(Unit); recover.await() },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { failed.await() }
            assertEquals(listOf(1), processed)
            recover.complete(Unit)
            withTimeout(5000) { closed.await() }
            assertEquals(listOf(1, 2), processed)
            assertEquals(1, opens)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun cancellationClosesSessionWithoutFailureRecovery() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val started = CompletableDeferred<Unit>()
        var closes = 0
        var failures = 0
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), { 1 }, { Session() }, { closes++ },
                { _, _ -> started.complete(Unit); awaitCancellation() },
                { _, _ -> failures++ }, { failures++ }
            )
            worker.wakeUp()
            withTimeout(5000) { started.await() }
        } finally { job.cancelAndJoin() }
        assertEquals(1, closes)
        assertEquals(0, failures)
    }

    @Test
    fun failedClientInitializationUsesTaskRecoveryAndCanRestart() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1, 2))
        val finished = CompletableDeferred<Unit>()
        var opens = 0
        var closes = 0
        val failures = mutableListOf<Int>()
        val processed = mutableListOf<Int>()
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), { queue.poll() },
                { if (++opens == 1) error("client unavailable"); Session() },
                { closes++; finished.complete(Unit) },
                { _, task -> processed.add(task) },
                { task, _ -> failures.add(task) },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
            assertEquals(listOf(1), failures)
            assertEquals(listOf(2), processed)
            assertEquals(2, opens)
            assertEquals(1, closes)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun retryWaitKeepsSessionAndNewWorkWakesItEarly() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1))
        val waiting = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var retryPending = false
        var opens = 0
        val processed = mutableListOf<Int>()
        lateinit var worker: AiProcessingWorker<Int, Session>
        try {
            worker = AiProcessingWorker(
                scope, Mutex(), {
                    if (queue.isEmpty() && retryPending) {
                        waiting.complete(Unit)
                        worker.awaitWork(60_000)
                        retryPending = false
                        queue.add(1)
                    }
                    queue.poll()
                }, { opens++; Session() }, { finished.complete(Unit) },
                { _, task ->
                    if (processed.isEmpty()) { processed.add(task); error("busy") }
                    processed.add(task)
                }, { _, _ -> retryPending = true },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { waiting.await() }
            queue.add(2)
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
            assertEquals(listOf(1, 2, 1), processed)
            assertEquals(1, opens)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun idleOrPausedQueueDoesNotOpenClientAndCanResume() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        val queue = ConcurrentLinkedQueue(listOf(1))
        val paused = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var foreground = false
        var opens = 0
        try {
            val worker = AiProcessingWorker(
                scope, Mutex(), {
                    if (foreground) queue.poll() else { paused.complete(Unit); null }
                }, { opens++; Session() }, { finished.complete(Unit) },
                { _, _ -> }, { _, exception -> throw AssertionError(exception) },
                { throw AssertionError(it) }
            )
            worker.wakeUp()
            withTimeout(5000) { paused.await() }
            assertEquals(0, opens)
            foreground = true
            worker.wakeUp()
            withTimeout(5000) { finished.await() }
            assertEquals(1, opens)
        } finally { job.cancelAndJoin() }
    }
}
