package me.bubblespark.tidilo.data

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import me.bubblespark.tidilo.data.db.TrashDao
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import org.junit.Assert.*
import org.junit.Test

class TrashQueryTest {
    private fun trashDao(
        onObserve: (Boolean) -> Flow<List<TrashBookmarkRow>>
    ): TrashDao = object : StubTrashDao() {
        override fun observeTrash(showPrivate: Boolean): Flow<List<TrashBookmarkRow>> = onObserve(showPrivate)
    }

    private fun sampleRow(id: String = "t1") = TrashBookmarkRow(
        item = TrashedBookmarkEntity(id, "Saved", "https://example.com/$id", "", createdAt = 1, updatedAt = 1, note = "", isPrivate = false, trashedAt = 2),
        duplicateInLibrary = false
    )

    @Test fun initializationFailureReachesConsumerInsteadOfEndlessLoading(): Unit = runBlocking {
        val failure = IOException("initialization")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = BookmarkRepository(
                database = fakeAppDatabase(StubBookmarkDao { throw failure }, trashDao { flowOf(listOf(sampleRow())) }),
                transactionRunner = directTransactionRunner(),
                repositoryScope = scope,
                autoInitialize = false
            )
            repository.retryInitializationAndAwait()
            val result = runCatching { withTimeout(500) { repository.observeTrash(true).first() } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals("initialization", result.exceptionOrNull()?.message)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun readFailureReachesConsumer(): Unit = runBlocking {
        val failure = IOException("query")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = BookmarkRepository(
                database = fakeAppDatabase(StubBookmarkDao(), trashDao { flow { throw failure } }),
                transactionRunner = directTransactionRunner(),
                repositoryScope = scope,
                autoInitialize = false
            )
            repository.retryInitializationAndAwait()
            val result = runCatching { withTimeout(500) { repository.observeTrash(true).first() } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals("query", result.exceptionOrNull()?.message)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun readsWaitForInitialization(): Unit = runBlocking {
        var queried = false
        val expected = listOf(sampleRow())
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = BookmarkRepository(
                database = fakeAppDatabase(StubBookmarkDao(), trashDao {
                    queried = true
                    flowOf(expected)
                }),
                transactionRunner = directTransactionRunner(),
                repositoryScope = scope,
                autoInitialize = false
            )
            val read = async { repository.observeTrash(true).first() }
            yield()
            assertFalse(queried)
            assertFalse(read.isCompleted)
            repository.retryInitializationAndAwait()
            assertEquals(expected, read.await())
            assertTrue(queried)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
