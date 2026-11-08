package me.bubblespark.tidilo.data

import java.io.IOException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TrashQueryTest {
    @Test fun initializationFailureReachesConsumerInsteadOfEndlessLoading(): Unit = runBlocking {
        val failure = IOException("initialization")
        val result = runCatching { withTimeout(500) { trashBookmarkQuery(flowOf(false), flowOf(failure)) { flowOf(1) }.first() } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("initialization", result.exceptionOrNull()?.message)
    }

    @Test fun readFailureReachesConsumer(): Unit = runBlocking {
        val failure = IOException("query")
        val result = runCatching { withTimeout(500) { trashBookmarkQuery(flowOf(true), flowOf(null)) { flow<Int> { throw failure } }.first() } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("query", result.exceptionOrNull()?.message)
    }

    @Test fun readsWaitForInitialization(): Unit = runBlocking {
        val ready = MutableStateFlow(false)
        var queried = false
        val source = trashBookmarkQuery(ready, flowOf(null)) { queried = true; flowOf(7) }
        val read = async { source.first() }
        yield()
        assertFalse(queried)
        assertFalse(read.isCompleted)
        ready.value = true
        assertEquals(7, read.await())
        assertTrue(queried)
    }
}
