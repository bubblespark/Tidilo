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
        val policyDao = InMemoryDomainPolicyDao()
        val bookmarkDao = object : StubBookmarkDao() {
            var updatedDomain: String? = null
            var updatedPrivate: Boolean? = null
            override suspend fun updateDomainPrivate(domain: String, isPrivate: Boolean) {
                updatedDomain = domain
                updatedPrivate = isPrivate
            }
        }
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gateUpdates = false
        val store = DomainPolicyStore(
            database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
            transactionRunner = object : TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R {
                    if (gateUpdates) {
                        committed.complete(Unit)
                        release.await()
                    }
                    return block()
                }
            },
            storeScope = scope
        )
        store.awaitReady()
        gateUpdates = true
        val caller = launch {
            store.setDomainPrivateAndAwait("example.com", true)
        }
        try {
            withTimeout(5000) { committed.await() }
            caller.cancelAndJoin()
            release.complete(Unit)
            withTimeout(5000) {
                store.policies.first { list -> list.any { it.domain == "example.com" && it.isPrivate } }
            }
            assertEquals("example.com", bookmarkDao.updatedDomain)
            assertEquals(true, bookmarkDao.updatedPrivate)
        } finally { owner.cancelAndJoin() }
    }
}
