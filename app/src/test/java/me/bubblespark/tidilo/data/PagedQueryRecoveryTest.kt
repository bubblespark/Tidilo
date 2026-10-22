package me.bubblespark.tidilo.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PagedQueryRecoveryTest {
    @Test
    fun readsWaitForInitializationAndCanRetryAfterFailure() = runBlocking {
        val ready = MutableStateFlow(false)
        val retry = MutableStateFlow(0)
        val failed = CompletableDeferred<Unit>()
        val received = CompletableDeferred<Int>()
        var attempts = 0
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            recoverableBookmarkQuery(ready, retry, { failed.complete(Unit) }) {
                flow { if (++attempts == 1) error("Read failed") else emit(42) }
            }.collect { received.complete(it) }
        }
        try {
            yield()
            assertEquals(0, attempts)
            ready.value = true
            withTimeout(5000) { failed.await() }
            assertTrue(observer.isActive)
            retry.value++
            assertEquals(42, withTimeout(5000) { received.await() })
            assertEquals(2, attempts)
        } finally { observer.cancelAndJoin() }
    }

    @Test
    fun cancellingTheSettingsCallerStillPublishesAnAlreadyStartedPolicyChange() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val caller = launch {
            completeDomainPolicyChange(scope, {
                committed.complete(Unit)
                release.await()
            }, { published.complete(Unit) })
        }
        try {
            withTimeout(5000) { committed.await() }
            caller.cancelAndJoin()
            release.complete(Unit)
            withTimeout(5000) { published.await() }
        } finally { owner.cancelAndJoin() }
    }
}
