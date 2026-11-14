package me.bubblespark.tidilo.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.DomainPolicyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class DomainPolicyStoreTest {

    private class RecordingBookmarkDao : StubBookmarkDao() {
        val rows = linkedMapOf<String, BookmarkEntity>()
        override suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean) {
            rows.replaceAll { _, entity ->
                if (entity.domain == domain) entity.copy(isNsfw = isNsfw) else entity
            }
        }
    }

    @Test
    fun registrationDisplayNameAndSyncPreserveExistingPolicyValues() = runBlocking {
        val policyDao = InMemoryDomainPolicyDao()
        val bookmarkDao = RecordingBookmarkDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )

            store.registerDomainAndAwait("  PIXIV.NET  ")
            assertFalse(store.isNsfwDomain("pixiv.net"))
            assertEquals("", store.getDomainDisplayName("pixiv.net"))
            assertEquals("pixiv.net", store.getDisplayName("pixiv.net"))

            store.setDomainNsfwAndAwait("pixiv.net", true)
            assertTrue(store.isNsfwDomain("PIXIV.NET"))

            // Re-registering with isNsfw=false does not overwrite isNsfw=true, and fills blank displayName.
            store.registerDomainAndAwait("pixiv.net", isNsfw = false, displayName = "Pixiv")
            assertTrue(store.isNsfwDomain("pixiv.net"))
            assertEquals("Pixiv", store.getDomainDisplayName("pixiv.net"))
            assertEquals("Pixiv", store.getDisplayName("pixiv.net"))

            // Explicit displayName update replaces existing displayName while preserving isNsfw.
            store.setDomainDisplayNameAndAwait("pixiv.net", "pixiv")
            assertEquals("pixiv", store.getDisplayName("pixiv.net"))
            assertTrue(store.isNsfwDomain("pixiv.net"))

            store.syncDomainsAndAwait(listOf("pixiv.net", "dlsite.com", null, " "))
            assertTrue(store.isNsfwDomain("pixiv.net"))
            assertFalse(store.isNsfwDomain("dlsite.com"))

            val listed = store.getAllPolicies()
            assertEquals(listOf("dlsite.com", "pixiv.net"), listed.map { it.domain })
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun syncDomainsChunksBulkInsertsToAtMost500Rows() = runBlocking {
        val chunkSizes = mutableListOf<Int>()
        val policyDao = object : InMemoryDomainPolicyDao() {
            override suspend fun insertAllIgnore(policies: List<DomainPolicyEntity>) {
                chunkSizes += policies.size
                super.insertAllIgnore(policies)
            }
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(domainPolicyDao = policyDao),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )
            val domains = (1..1205).map { "site$it.example" }
            store.syncDomainsAndAwait(domains)

            assertEquals(listOf(500, 500, 205), chunkSizes)
            assertEquals(1205, store.getAllPolicies().size)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun setDomainNsfwUpdatesPolicyAndBookmarksInSingleTransactionAndDoesNotPublishOnFailure() = runBlocking {
        val policyDao = InMemoryDomainPolicyDao()
        val bookmarkDao = RecordingBookmarkDao()
        bookmarkDao.rows["b1"] = BookmarkEntity(
            id = "b1", title = "One", url = "https://example.com/1", description = "",
            createdAt = 1L, updatedAt = 1L, isNsfw = false
        )
        bookmarkDao.rows["b2"] = BookmarkEntity(
            id = "b2", title = "Two", url = "https://other.com/2", description = "",
            createdAt = 2L, updatedAt = 2L, isNsfw = false
        )

        var shouldFail = false
        var transactionCount = 0
        val atomicRunner = object : TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R {
                transactionCount++
                val policySnapshot = LinkedHashMap(policyDao.rows)
                val bookmarkSnapshot = LinkedHashMap(bookmarkDao.rows)
                return try {
                    val result = block()
                    if (shouldFail) throw IllegalStateException("disk full")
                    result
                } catch (e: Throwable) {
                    policyDao.rows.clear()
                    policyDao.rows.putAll(policySnapshot)
                    bookmarkDao.rows.clear()
                    bookmarkDao.rows.putAll(bookmarkSnapshot)
                    throw e
                }
            }
        }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = atomicRunner,
                storeScope = scope
            )
            store.awaitReady()
            transactionCount = 0

            store.setDomainNsfwAndAwait(" EXAMPLE.COM ", true)
            assertEquals(1, transactionCount)
            assertTrue(store.isNsfwDomain("example.com"))
            assertTrue(bookmarkDao.rows.getValue("b1").isNsfw)
            assertFalse(bookmarkDao.rows.getValue("b2").isNsfw)

            shouldFail = true
            val thrown = runCatching {
                store.setDomainNsfwAndAwait("example.com", false)
            }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
            // Rolled back in DB and never published to in-memory cache/StateFlow.
            assertTrue(store.isNsfwDomain("example.com"))
            assertTrue(store.policies.value.single { it.domain == "example.com" }.isNsfw)
            assertTrue(bookmarkDao.rows.getValue("b1").isNsfw)
            assertTrue(policyDao.rows.getValue("example.com").isNsfw)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun legacyJsonMigrationMergesWithoutLosingFlagsAndDeletesFilesOnlyAfterCommit() = runBlocking {
        val dir = Files.createTempDirectory("domain-policy-migration").toFile()
        try {
            val jsonFile = File(dir, "domain_policies.json")
            val pendingFile = File(dir, "domain_policies.json.pending")
            jsonFile.writeText(
                """
                [
                  {"domain": "PIXIV.NET", "isNsfw": true, "displayName": "Pixiv"},
                  {"domain": "example.com", "isNsfw": false, "displayName": "Example"},
                  {"domain": "merge.example", "isNsfw": true}
                ]
                """.trimIndent(),
                Charsets.UTF_8
            )
            pendingFile.writeText("[]", Charsets.UTF_8)

            val policyDao = InMemoryDomainPolicyDao()
            // Simulate an already-present default row that must not erase legacy isNsfw=true.
            policyDao.rows["merge.example"] = DomainPolicyEntity("merge.example", isNsfw = false, displayName = "")

            var failMigration = true
            val runner = object : TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R {
                    val snapshot = LinkedHashMap(policyDao.rows)
                    return try {
                        val result = block()
                        if (failMigration) throw IllegalStateException("transient db failure")
                        result
                    } catch (e: Throwable) {
                        policyDao.rows.clear()
                        policyDao.rows.putAll(snapshot)
                        throw e
                    }
                }
            }

            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val store = DomainPolicyStore(
                    database = fakeAppDatabase(domainPolicyDao = policyDao),
                    legacyJsonFile = jsonFile,
                    transactionRunner = runner,
                    storeScope = scope
                )

                val firstAttempt = runCatching { store.awaitReady() }.exceptionOrNull()
                assertTrue(firstAttempt is IllegalStateException)
                assertTrue("Legacy JSON must stay until commit", jsonFile.exists())
                assertTrue("Pending file must stay until commit", pendingFile.exists())

                failMigration = false
                store.syncDomainsAndAwait(listOf("merge.example", "fresh.example"))

                assertFalse("Legacy JSON must be removed after commit", jsonFile.exists())
                assertFalse("Pending file must be removed after commit", pendingFile.exists())
                assertTrue(store.isNsfwDomain("pixiv.net"))
                assertEquals("Pixiv", store.getDisplayName("pixiv.net"))
                assertEquals("Example", store.getDisplayName("example.com"))
                assertTrue(store.isNsfwDomain("merge.example"))
                assertFalse(store.isNsfwDomain("fresh.example"))
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setDomainNsfwDoesNotUpdateCacheOptimisticallyBeforeCommitOrOnFailure() = runBlocking {
        val policyDao = InMemoryDomainPolicyDao()
        val bookmarkDao = RecordingBookmarkDao()
        var gate: CompletableDeferred<Unit>? = null
        var enteredWriteTx: CompletableDeferred<Unit>? = null
        var shouldFailWrite = true

        val runner = object : TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R {
                val activeGate = gate
                if (activeGate != null) {
                    enteredWriteTx?.complete(Unit)
                    activeGate.await()
                }
                val policySnapshot = LinkedHashMap(policyDao.rows)
                return try {
                    val result = block()
                    if (activeGate != null && shouldFailWrite) {
                        throw IllegalStateException("write rejected")
                    }
                    result
                } catch (e: Throwable) {
                    policyDao.rows.clear()
                    policyDao.rows.putAll(policySnapshot)
                    throw e
                }
            }
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = runner,
                storeScope = scope
            )
            store.awaitReady()

            // Failed background write must not leave an optimistic NSFW state in cache or StateFlow.
            enteredWriteTx = CompletableDeferred()
            gate = CompletableDeferred()
            store.setDomainNsfw("async.example", true)
            withTimeout(5000) { enteredWriteTx.await() }
            assertFalse(store.isNsfwDomain("async.example"))
            assertTrue(store.policies.value.none { it.domain == "async.example" })

            gate.complete(Unit)
            withTimeout(5000) {
                while (scope.coroutineContext[Job]?.children?.any { it.isActive } == true) {
                    delay(10)
                }
            }
            assertFalse(store.isNsfwDomain("async.example"))
            assertTrue(store.policies.value.none { it.domain == "async.example" })

            // Successful background write updates cache and StateFlow only after commit.
            shouldFailWrite = false
            enteredWriteTx = CompletableDeferred()
            gate = CompletableDeferred()
            store.setDomainNsfw("async.example", true)
            withTimeout(5000) { enteredWriteTx.await() }
            assertFalse(store.isNsfwDomain("async.example"))

            gate.complete(Unit)
            withTimeout(5000) {
                while (!store.isNsfwDomain("async.example")) {
                    delay(10)
                }
            }
            assertTrue(store.policies.value.single { it.domain == "async.example" }.isNsfw)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun awaitReadyOffloadsDiskAndDbInitializationToIoDispatcher() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fake-main-caller")
        }
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        var observedTxThreadName: String? = null
        val runner = object : TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R {
                observedTxThreadName = Thread.currentThread().name
                return block()
            }
        }
        try {
            val dir = Files.createTempDirectory("domain-policy-io-dispatch").toFile()
            try {
                val jsonFile = File(dir, "domain_policies.json")
                jsonFile.writeText("""[{"domain":"io.example","isNsfw":false,"displayName":"IO"}]""", Charsets.UTF_8)
                // Cancel storeScope upfront so eager warmup skips and awaitReady() runs on callerDispatcher.
                val cancelledScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).apply {
                    coroutineContext[Job]?.cancel()
                }
                val store = DomainPolicyStore(
                    database = fakeAppDatabase(domainPolicyDao = InMemoryDomainPolicyDao()),
                    legacyJsonFile = jsonFile,
                    transactionRunner = runner,
                    storeScope = cancelledScope
                )
                withContext(callerDispatcher) {
                    store.awaitReady()
                }
                assertNotEquals("fake-main-caller", observedTxThreadName)
                assertEquals("IO", store.getDisplayName("io.example"))
            } finally {
                dir.deleteRecursively()
            }
        } finally {
            callerDispatcher.close()
        }
    }

    @Test
    fun concurrentCacheAndStoreMutationsStayConsistent() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(domainPolicyDao = InMemoryDomainPolicyDao()),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )
            store.awaitReady()

            val jobs = (0 until 20).map { idx ->
                launch(Dispatchers.Default) {
                    val domain = "concurrent-$idx.example"
                    store.registerDomain(domain, displayName = "Site $idx")
                    store.setDomainDisplayName(domain, "Updated $idx")
                    store.syncDomains(listOf(domain, "shared.example"))
                    store.setDomainNsfwAndAwait(domain, idx % 2 == 0)
                }
            }
            jobs.joinAll()
            scope.coroutineContext[Job]?.children?.toList()?.joinAll()

            val snapshot = store.policies.value
            assertEquals(snapshot.map { it.domain }.sorted(), snapshot.map { it.domain })
            assertTrue(snapshot.any { it.domain == "shared.example" })
            for (idx in 0 until 20) {
                val domain = "concurrent-$idx.example"
                assertEquals(idx % 2 == 0, store.isNsfwDomain(domain))
                assertEquals("Updated $idx", store.getDisplayName(domain))
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
