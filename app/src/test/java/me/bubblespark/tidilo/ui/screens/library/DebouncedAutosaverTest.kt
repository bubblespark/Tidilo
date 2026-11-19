package me.bubblespark.tidilo.ui.screens.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncedAutosaverTest {

    private fun autosaveTest(block: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            block(scope)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun coalescesTypingIntoOneWriteAfterThePause() = autosaveTest { scope ->
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 50) { writes += it }

        saver.submit("a")
        saver.submit("ab")
        saver.submit("abc")
        assertEquals(AutosaveStatus.Pending, saver.status.value)
        assertTrue(writes.isEmpty())

        delay(150)
        assertEquals(listOf("abc"), writes)
        assertEquals(AutosaveStatus.Saved, saver.status.value)
    }

    @Test
    fun flushWritesThePendingDraftImmediately() = autosaveTest { scope ->
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10_000) { writes += it }

        saver.submit("leaving now")
        saver.flush()
        repeat(3) { yield() }

        assertEquals(listOf("leaving now"), writes)
        saver.flush()
        repeat(3) { yield() }
        assertEquals("A second flush has nothing left to write", 1, writes.size)
    }

    @Test
    fun newerDraftIsNeverOverwrittenByAnOlderWrite() = autosaveTest { scope ->
        val writes = mutableListOf<String>()
        val firstWriteGate = CompletableDeferred<Unit>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10_000) { value ->
            if (value == "old") firstWriteGate.await()
            writes += value
        }

        saver.submit("old")
        saver.flush()
        repeat(3) { yield() }
        saver.submit("new")
        saver.flush()
        repeat(3) { yield() }
        firstWriteGate.complete(Unit)
        delay(50)

        assertEquals(listOf("old", "new"), writes)
        assertEquals(AutosaveStatus.Saved, saver.status.value)
    }

    @Test
    fun failureKeepsTheDraftForRetry() = autosaveTest { scope ->
        var fail = true
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10) { value ->
            if (fail) error("disk full")
            writes += value
        }

        saver.submit("keep me")
        delay(80)
        assertEquals(AutosaveStatus.Failed, saver.status.value)

        fail = false
        saver.retry()
        delay(50)
        assertEquals(listOf("keep me"), writes)
        assertEquals(AutosaveStatus.Saved, saver.status.value)
    }

    @Test
    fun invalidDraftIsNeverWritten() = autosaveTest { scope ->
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10, isValid = { it.isNotBlank() }) { writes += it }

        saver.submit("Valid")
        saver.submit("   ")
        delay(80)
        saver.flush()
        delay(20)

        assertTrue("A blank title must not replace the last valid value", writes.isEmpty())
        assertEquals(AutosaveStatus.Invalid, saver.status.value)

        saver.submit("Fixed")
        delay(80)
        assertEquals(listOf("Fixed"), writes)
    }

    @Test
    fun valueEqualToStoredIsNotRewrittenWhenNothingIsPending() = autosaveTest { scope ->
        var stored = "saved"
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10, isStored = { it == stored }) {
            writes += it
            stored = it
        }

        saver.submit("saved")
        delay(50)
        assertTrue("Opening the editor must not rewrite the stored value", writes.isEmpty())
        assertEquals(AutosaveStatus.Idle, saver.status.value)

        saver.submit("typed")
        saver.submit("saved")
        delay(50)
        assertTrue("Reverting before the pause cancels the pending write", writes.isEmpty())
    }

    @Test
    fun revertingWhileAWriteIsInFlightStillPersistsTheRevert() = autosaveTest { scope ->
        var stored = "a"
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10_000, isStored = { it == stored }) { value ->
            if (value == "ab") gate.await()
            writes += value
            stored = value
        }

        saver.submit("ab")
        saver.flush()
        repeat(3) { yield() }
        // Room has not reflected "ab" yet, so "a" still looks stored; the in-flight write must not win.
        saver.submit("a")
        saver.flush()
        gate.complete(Unit)
        delay(50)

        assertEquals(listOf("ab", "a"), writes)
    }

    @Test
    fun cancelDropsAPendingDraft() = autosaveTest { scope ->
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10) { writes += it }

        saver.submit("about to delete")
        saver.cancel()
        delay(50)
        saver.flush()
        delay(10)

        assertTrue(writes.isEmpty())
    }

    @Test
    fun failureOfASupersededWriteNeitherMasksInvalidNorOffersRetry() = autosaveTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10_000, isValid = { it.isNotBlank() }) { value ->
            gate.await()
            error("write failed for $value")
        }

        saver.submit("Old title")
        saver.flush()
        repeat(3) { yield() }
        // The user clears the required field while the earlier write is still running.
        saver.submit("")
        gate.complete(Unit)
        delay(50)

        assertEquals(AutosaveStatus.Invalid, saver.status.value)
        saver.retry()
        delay(20)
        assertTrue("Retry must not resubmit a superseded draft", writes.isEmpty())
        assertEquals(AutosaveStatus.Invalid, saver.status.value)
    }

    @Test
    fun failureWithANewerWriteQueuedStaysPendingUntilTheNewerOneLands() = autosaveTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val saver = DebouncedAutosaver<String>(scope, delayMillis = 10_000) { value ->
            if (value == "old") { gate.await(); error("old failed") }
            writes += value
        }

        saver.submit("old")
        saver.flush()
        repeat(3) { yield() }
        saver.submit("new")
        saver.flush()
        repeat(3) { yield() }
        gate.complete(Unit)
        delay(50)

        assertEquals(listOf("new"), writes)
        assertEquals(AutosaveStatus.Saved, saver.status.value)
    }

    @Test
    fun sharedLockKeepsSubmissionOrderAcrossAutosaverInstances() = autosaveTest { scope ->
        val lock = kotlinx.coroutines.sync.Mutex()
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val write: suspend (String) -> Unit = { value -> if (value == "A") gate.await(); writes += value }
        val disposed = DebouncedAutosaver(scope, delayMillis = 10_000, writeLock = lock, write = write)
        val recreated = DebouncedAutosaver(scope, delayMillis = 10_000, writeLock = lock, write = write)

        // The old screen's write A is running and its final write B is queued behind it.
        disposed.submit("A"); disposed.flush()
        repeat(3) { yield() }
        disposed.submit("B"); disposed.flush()
        repeat(3) { yield() }
        // The recreated screen then submits newer text C.
        recreated.submit("C"); recreated.flush()
        repeat(3) { yield() }
        gate.complete(Unit)
        delay(50)

        assertEquals("The newest write must land last", listOf("A", "B", "C"), writes)
    }

    @Test
    fun revertToStoredValueIsWrittenWhileAnotherInstanceHoldsTheSharedLock() = autosaveTest { scope ->
        val lock = kotlinx.coroutines.sync.Mutex()
        val gate = CompletableDeferred<Unit>()
        var stored = "A"
        val writes = mutableListOf<String>()
        val write: suspend (String) -> Unit = { value -> if (value == "B") gate.await(); writes += value; stored = value }
        val disposed = DebouncedAutosaver(scope, delayMillis = 10_000, writeLock = lock, write = write)
        val recreated = DebouncedAutosaver(scope, delayMillis = 10_000, isStored = { it == stored }, writeLock = lock, write = write)

        // The old screen is still writing B; the recreated screen read A before B committed.
        disposed.submit("B"); disposed.flush()
        repeat(3) { yield() }
        recreated.submit("C")
        recreated.submit("A")
        recreated.flush()
        repeat(3) { yield() }
        gate.complete(Unit)
        delay(50)

        assertEquals("The user's revert to A must land after the old write", listOf("B", "A"), writes)
    }
}
