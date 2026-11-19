package me.bubblespark.tidilo.ui.screens.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Visible persistence state of a directly edited field. */
enum class AutosaveStatus { Idle, Pending, Saving, Saved, Failed, Invalid }

/**
 * Persists the latest valid snapshot after a typing pause. Writes are serialized in submission order,
 * so an older snapshot never lands after a newer one. Invalid snapshots are kept by the caller and never
 * written. Callers decide what is owed (see [EditedFields]); every submitted snapshot is written, there is
 * no "already stored" shortcut.
 *
 * Pass one shared [writeLock] to every autosaver that writes the same records, so ordering also holds
 * across autosaver instances, e.g. a final write flushed by a disposed screen and the first write of its
 * recreated replacement. The lock is entered when a write starts (debounce, [flush] or [retry]); [scope]
 * must use an immediate main dispatcher (`Dispatchers.Main.immediate`) so that [flush] queues on the lock
 * synchronously and writes keep their call order.
 *
 * Must be driven from a single thread (the main thread in UI usage).
 */
internal class DebouncedAutosaver<T>(
    private val scope: CoroutineScope,
    private val delayMillis: Long = DEFAULT_DELAY_MILLIS,
    private val isValid: (T) -> Boolean = { true },
    private val writeLock: Mutex = Mutex(),
    private val write: suspend (T) -> Unit
) {
    private val mutableStatus = MutableStateFlow(AutosaveStatus.Idle)
    val status: StateFlow<AutosaveStatus> = mutableStatus.asStateFlow()

    private var pending: Slot<T>? = null
    private var failed: Slot<T>? = null
    private var debounceJob: Job? = null
    private var inFlight = 0
    private var invalid = false

    /** Records the newest draft and restarts the typing-pause timer. */
    fun submit(value: T) {
        debounceJob?.cancel()
        debounceJob = null
        failed = null
        invalid = !isValid(value)
        if (invalid) {
            pending = null
            mutableStatus.value = AutosaveStatus.Invalid
            return
        }
        pending = Slot(value)
        mutableStatus.value = AutosaveStatus.Pending
        debounceJob = scope.launch {
            delay(delayMillis)
            debounceJob = null
            takePending()?.let { save(it) }
        }
    }

    /** Writes any pending draft immediately, e.g. on focus loss or when the screen goes away. */
    fun flush() {
        debounceJob?.cancel()
        debounceJob = null
        val slot = takePending() ?: return
        scope.launch { save(slot) }
    }

    /** Drops a draft that has not started writing, e.g. before deleting the edited object. */
    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
        pending = null
        failed = null
    }

    /** Re-attempts the last failed write. */
    fun retry() {
        val slot = failed ?: return
        failed = null
        inFlight++
        mutableStatus.value = AutosaveStatus.Pending
        scope.launch { save(slot) }
    }

    private fun takePending(): Slot<T>? {
        val slot = pending ?: return null
        pending = null
        inFlight++
        return slot
    }

    private suspend fun save(slot: Slot<T>) {
        try {
            writeLock.withLock {
                mutableStatus.value = AutosaveStatus.Saving
                try {
                    write(slot.value)
                    mutableStatus.value = when {
                        pending != null -> AutosaveStatus.Pending
                        invalid -> AutosaveStatus.Invalid
                        else -> AutosaveStatus.Saved
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A newer draft (pending, queued behind this write, or invalid) supersedes the
                    // failed one; only an unsuperseded failure is kept for Retry.
                    mutableStatus.value = when {
                        pending != null || inFlight > 1 -> AutosaveStatus.Pending
                        invalid -> AutosaveStatus.Invalid
                        else -> {
                            failed = slot
                            AutosaveStatus.Failed
                        }
                    }
                }
            }
        } finally {
            inFlight--
        }
    }

    private class Slot<T>(val value: T)

    companion object {
        const val DEFAULT_DELAY_MILLIS = 500L
    }
}
