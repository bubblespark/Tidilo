package me.bubblespark.tidilo.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ImageCacheNotificationsTest {
    @Test
    fun matchingUpdatesRepeatAndClearsReachAllDomains() = runBlocking {
        val updates = MutableSharedFlow<String>()
        val clears = MutableStateFlow(0L)
        val github = mutableListOf<Any>()
        val android = mutableListOf<Any>()
        val first = launch { imageRefreshSignals("github.com", updates, clears).collect { github.add(it) } }
        val second = launch { imageRefreshSignals("android.com", updates, clears).collect { android.add(it) } }
        withTimeout(5_000) { while (updates.subscriptionCount.value < 2 || github.size < 2 || android.size < 2) yield() }
        val githubInitial = github.size
        val androidInitial = android.size
        updates.emit("github.com")
        withTimeout(5_000) { while (github.size < githubInitial + 1) yield() }
        updates.emit("github.com")
        withTimeout(5_000) { while (github.size < githubInitial + 2) yield() }
        assertEquals(androidInitial, android.size)
        assertNotSame(github[githubInitial], github[githubInitial + 1])
        clears.value++
        withTimeout(5_000) { while (github.size < githubInitial + 3 || android.size < androidInitial + 1) yield() }
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test
    fun collectionRestartRefreshesWithoutReplayingDomainEvents() = runBlocking {
        val updates = MutableSharedFlow<String>()
        val clears = MutableSharedFlow<Long>()
        val signals = imageRefreshSignals("github.com", updates, clears)
        val received = mutableListOf<Any>()
        var job = launch(start = CoroutineStart.UNDISPATCHED) { signals.collect { received.add(it) } }
        withTimeout(5_000) { while (updates.subscriptionCount.value == 0) yield() }
        job.cancelAndJoin()
        updates.emit("github.com")
        job = launch(start = CoroutineStart.UNDISPATCHED) { signals.collect { received.add(it) } }
        assertEquals(2, received.size)
        assertNotSame(received[0], received[1])
        job.cancelAndJoin()
    }

    @Test
    fun fullBufferDoesNotDropDomainInvalidations() = runBlocking {
        val updates = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val received = mutableSetOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            updates.collect { received.add(it) }
        }
        repeat(50) { updates.emitUpdate("domain$it.com") }
        withTimeout(5_000) { while (received.size < 50) yield() }
        collector.cancelAndJoin()
        assertEquals(50, received.size)
    }
}
