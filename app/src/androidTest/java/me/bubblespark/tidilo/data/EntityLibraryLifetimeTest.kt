package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.screens.library.EntityLibraryState
import me.bubblespark.tidilo.ui.screens.library.rememberEntityLibraryState
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EntityLibraryLifetimeTest {
    private val host = HeaderTestHost()

    @Test fun lookupCollectsOnlyWhileNeededAndReopensLoadingWithFreshSnapshot(): Unit = runBlocking {
        val activity = host.launch()
        val needed = mutableStateOf(false)
        val retry = mutableStateOf(0)
        val input = mutableStateOf("")
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val snapshots = Channel<EntityLibraryState>(Channel.UNLIMITED)
        val rendered = AtomicReference<Pair<Boolean, EntityLibraryState>>()
        var originalFailure: Throwable? = null
        try {
            host.main { activity.setContent {
                val source = remember(retry.value) {
                    flow {
                        starts.incrementAndGet()
                        try {
                            emit(snapshots.receive())
                            awaitCancellation()
                        } finally { stops.incrementAndGet() }
                    }
                }
                val state = rememberEntityLibraryState(needed.value, source)
                val visible = needed.value
                Text(input.value)
                SideEffect { rendered.set(visible to state) }
            } }
            await { rendered.get()?.first == false }
            host.instrumentation.waitForIdleSync()
            assertEquals("Detail and parent editor do not need the aggregate", 0, starts.get())

            host.main { needed.value = true }
            await { starts.get() == 1 && rendered.get()?.first == true }
            assertTrue(rendered.get().second.loading)
            snapshots.send(EntityLibraryState(mapOf("tag" to "Old tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Old tag" }
            host.main { input.value = "Typing does not restart lookup" }
            host.instrumentation.waitForIdleSync()
            assertEquals(1, starts.get())

            host.main { needed.value = false }
            await { rendered.get()?.first == false && stops.get() == 1 }
            host.main { needed.value = true }
            await { starts.get() == 2 && rendered.get()?.first == true }
            assertTrue("Reopen must gate confirmation until a fresh snapshot", rendered.get().second.loading)
            assertTrue(rendered.get().second.spellings.isEmpty())
            snapshots.send(EntityLibraryState(mapOf("tag" to "Fresh tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Fresh tag" }

            host.main { retry.value++ }
            await { starts.get() == 3 && stops.get() == 2 }
            assertTrue("Retry must discard the previous ready snapshot", rendered.get().second.loading)
            assertTrue(rendered.get().second.spellings.isEmpty())
            snapshots.send(EntityLibraryState(loading = false, error = IllegalStateException("Fixture")))
            await { rendered.get()?.second?.error != null }
            host.main { retry.value++ }
            await { starts.get() == 4 && stops.get() == 3 }
            assertTrue(rendered.get().second.loading)
            assertNull(rendered.get().second.error)
            snapshots.send(EntityLibraryState(mapOf("tag" to "Recovered tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Recovered tag" }
            host.main { needed.value = false }
            await { stops.get() == 4 }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            tearDownLookup(originalFailure,
                disposeContent = { host.main { activity.setContent {} } },
                confirmCancellation = { await { stops.get() == starts.get() } },
                finishActivity = { host.main { activity.finish() } },
                closeSnapshots = { snapshots.close() })
        }
    }

    @Test fun teardownPreservesBodyFailureAndAttemptsEveryCleanup(): Unit = runBlocking {
        val bodyFailure = AssertionError("Body failure")
        val disposalFailure = IllegalStateException("Disposal failure")
        val finishFailure = IllegalStateException("Finish failure")
        val closeFailure = IllegalStateException("Close failure")
        val attempted = mutableListOf<String>()
        val snapshots = Channel<Unit>()
        var caught: Throwable? = null
        try {
            try { throw bodyFailure } finally {
                tearDownLookup(bodyFailure,
                    disposeContent = { attempted += "dispose"; throw disposalFailure },
                    confirmCancellation = { attempted += "cancel"; withTimeout(1) { awaitCancellation() } },
                    finishActivity = { attempted += "finish"; throw finishFailure },
                    closeSnapshots = { attempted += "close"; snapshots.close(); throw closeFailure })
            }
        } catch (failure: Throwable) { caught = failure }
        assertEquals(listOf("dispose", "cancel", "finish", "close"), attempted)
        assertSame(bodyFailure, caught)
        assertEquals(4, bodyFailure.suppressed.size)
        assertSame(disposalFailure, bodyFailure.suppressed[0])
        assertTrue(bodyFailure.suppressed[1] is TimeoutCancellationException)
        assertSame(finishFailure, bodyFailure.suppressed[2])
        assertSame(closeFailure, bodyFailure.suppressed[3])
        assertTrue(snapshots.receiveCatching().isClosed)
    }

    @Test fun teardownPropagatesCancellationTimeoutAfterFinishingAndClosing(): Unit = runBlocking {
        val attempted = mutableListOf<String>()
        val closeFailure = IllegalStateException("Close failure")
        var caught: Throwable? = null
        try {
            tearDownLookup(null,
                disposeContent = { attempted += "dispose" },
                confirmCancellation = { attempted += "cancel"; withTimeout(1) { awaitCancellation() } },
                finishActivity = { attempted += "finish" },
                closeSnapshots = { attempted += "close"; throw closeFailure })
        } catch (failure: Throwable) { caught = failure }
        assertEquals(listOf("dispose", "cancel", "finish", "close"), attempted)
        assertTrue(caught is TimeoutCancellationException)
        assertArrayEquals(arrayOf(closeFailure), requireNotNull(caught).suppressed)
    }

    private suspend fun tearDownLookup(
        originalFailure: Throwable?,
        disposeContent: () -> Unit,
        confirmCancellation: suspend () -> Unit,
        finishActivity: () -> Unit,
        closeSnapshots: () -> Unit
    ) {
        var cleanupFailure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) {
                val primary = originalFailure ?: cleanupFailure
                if (primary != null) primary.addSuppressed(failure) else cleanupFailure = failure
            }
        }
        attempt { disposeContent() }
        attempt { confirmCancellation() }
        attempt { finishActivity() }
        attempt { closeSnapshots() }
        if (originalFailure == null) cleanupFailure?.let { throw it }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(8000) {
        while (!predicate()) delay(30)
    }
}
