package me.bubblespark.tidilo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one sequential processing session, including work arriving during shutdown.
 * Worker cancellation is rethrown; task-level [CancellationException] is passed to [handleFailure].
 */
internal class AiProcessingWorker<T : Any, S : Any>(
    scope: CoroutineScope,
    mutex: Mutex,
    private val nextTask: suspend () -> T?,
    private val openSession: () -> S,
    private val closeSession: (S) -> Unit,
    private val processTask: suspend (S, T) -> Unit,
    private val handleFailure: suspend (T, Exception) -> Unit,
    private val reportSessionFailure: (Exception) -> Unit
) {
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in signals) {
                try {
                    mutex.withLock {
                        var session: S? = null
                        try {
                            while (true) {
                                val task = nextTask() ?: break
                                try {
                                    val activeSession = session ?: openSession().also { session = it }
                                    processTask(activeSession, task)
                                } catch (e: CancellationException) {
                                    currentCoroutineContext().ensureActive()
                                    handleFailure(task, e)
                                } catch (e: Exception) {
                                    handleFailure(task, e)
                                }
                            }
                        } finally {
                            session?.let(closeSession)
                        }
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    reportSessionFailure(e)
                } catch (e: Exception) {
                    reportSessionFailure(e)
                }
            }
        }
    }

    fun wakeUp() {
        signals.trySend(Unit)
    }

    /** Keeps the session open during retry backoff, waking early for newly queued work. */
    suspend fun awaitWork(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) { signals.receive() }
    }
}
