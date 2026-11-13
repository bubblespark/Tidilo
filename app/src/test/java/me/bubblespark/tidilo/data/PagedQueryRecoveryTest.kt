package me.bubblespark.tidilo.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PagedQueryRecoveryTest {
    @Test
    fun readsWaitForInitializationAndCanRetryAfterFailure() = runBlocking {
        var attempts = 0
        val dao = object : StubBookmarkDao() {
            override fun observeDistinctDomains(): Flow<List<String>> = flow {
                if (++attempts == 1) error("Read failed") else emit(listOf("example.com"))
            }
        }
        val repoScope = CoroutineScope(coroutineContext + SupervisorJob())
        val repository = BookmarkRepository(
            database = fakeAppDatabase(dao),
            transactionRunner = directTransactionRunner(),
            repositoryScope = repoScope,
            autoInitialize = false
        )
        val received = CompletableDeferred<List<String>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeDomains().collect { received.complete(it) }
        }
        try {
            yield()
            assertEquals(0, attempts)
            repository.retryInitializationAndAwait()
            withTimeout(5000) {
                repository.initializationFailure.filterNotNull().first()
            }
            assertTrue(observer.isActive)
            repository.retryInitialization()
            assertEquals(listOf("example.com"), withTimeout(5000) { received.await() })
            assertEquals(2, attempts)
        } finally {
            observer.cancelAndJoin()
            repoScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun cancellingTheSettingsCallerStillPublishesAnAlreadyStartedPolicyChange() = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val caller = launch {
            completeDomainPolicyChange(scope, { publish ->
                committed.complete(Unit)
                release.await()
                publish()
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
