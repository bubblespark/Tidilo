package me.bubblespark.tidilo.data

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class PermanentOgImageFailure : IOException()

internal class OgImageFetchCoordinator<T : Any>(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    val generation = AtomicLong(0)
    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadSemaphore = Semaphore(4)
    private class Flight<T>(val result: Deferred<T?>, var callers: Int = 0)
    private val inFlightDownloads = mutableMapOf<String, Flight<T>>()
    private val failedImageKeys = mutableSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()

    /** Shares work until its last caller leaves; cancelled work retains its permit until stopped. */
    suspend fun fetch(key: String, startGeneration: Long, block: suspend () -> T?): T? {
        currentCoroutineContext().ensureActive()
        val flightKey = "$startGeneration|$key"
        val flight = synchronized(lock) {
            if (generation.get() != startGeneration || key in failedImageKeys ||
                (retryAfter[key]?.let { nowMillis() < it } == true)) return null
            inFlightDownloads.getOrPut(flightKey) {
                Flight(scope.async(start = CoroutineStart.LAZY) {
                    try {
                        downloadSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            if (generation.get() != startGeneration) null else block()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val workContext = currentCoroutineContext()
                        synchronized(lock) {
                            // Last-caller cancellation uses this same lock; check after acquiring it.
                            workContext.ensureActive()
                            if (generation.get() == startGeneration) {
                                if (failure is PermanentOgImageFailure) failedImageKeys.add(key)
                                else retryAfter[key] = nowMillis() + 60_000
                            }
                        }
                        null
                    }
                })
            }.also { it.callers++ }
        }
        try {
            val result = flight.result.await()
            return if (generation.get() == startGeneration) result else null
        } finally {
            synchronized(lock) {
                flight.callers--
                if (flight.callers == 0) {
                    if (inFlightDownloads[flightKey] === flight) inFlightDownloads.remove(flightKey)
                    flight.result.cancel()
                }
            }
        }
    }

    fun clear() = synchronized(lock) {
        generation.incrementAndGet()
        val old = inFlightDownloads.values.toList()
        inFlightDownloads.clear()
        failedImageKeys.clear()
        retryAfter.clear()
        old.forEach { it.result.cancel() }
    }
}
