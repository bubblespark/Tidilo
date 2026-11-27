package me.bubblespark.tidilo.data.db

import android.database.sqlite.SQLiteException
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.DomainPolicyStore
import me.bubblespark.tidilo.data.TransactionRunner
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BookmarkTransactionRoomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private suspend fun results(db: AppDatabase, query: String, showPrivate: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showPrivate)).load(
            PagingSource.LoadParams.Refresh(null, 30, false)
        )
        assertTrue(page.toString(), page is PagingSource.LoadResult.Page)
        return (page as PagingSource.LoadResult.Page).data
    }

    @Test
    fun engagementMutationsAreAtomicAndLikeOffPreservesTimestamp() = runBlocking {
        val db = inMemoryDatabase()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(BookmarkEntity("liked", "Saved", "https://example.com", "", createdAt = 1, updatedAt = 1))
            val repository = BookmarkRepository(db, repositoryScope = scope, autoInitialize = false)
            coroutineScope {
                repeat(40) { launch(Dispatchers.IO) { repository.recordBrowserOpen("liked", 10) } }
                repeat(30) { launch(Dispatchers.IO) { repository.recordDetailView("liked", 20) } }
                repeat(20) { launch(Dispatchers.IO) { repository.recordSummaryPeek("liked", 30) } }
            }
            assertEquals(
                mapOf(EngagementType.BROWSER_OPEN.code to 40, EngagementType.DETAIL.code to 30, EngagementType.PEEK.code to 20),
                db.engagementDao().eventsFor("liked").groupingBy { it.type }.eachCount()
            )
            dao.toggleLike("liked", 1234)
            assertTrue(dao.observeById("liked").first()!!.isLiked)
            dao.toggleLike("liked", 2345)
            val unliked = dao.getBookmarkById("liked")!!
            assertFalse(unliked.isLiked)
            assertEquals(1234L, unliked.lastInteractedAt)
            repository.recordBrowserOpen("missing", 1)
            repository.toggleLike("missing", 1)
            assertTrue(db.engagementDao().eventsFor("missing").isEmpty())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    @Test
    fun trashMoveRollsBackOnFailureAndWorksWithoutLoadingTheLibrary() = runBlocking {
        val db = inMemoryDatabase()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repository = BookmarkRepository(db, repositoryScope = scope, autoInitialize = false)
        try {
            db.bookmarkDao().insertOrUpdate(
                BookmarkEntity(
                    "off-page", "Original", "https://example.com", "kept",
                    entities = listOf("Retained"), createdAt = 1, updatedAt = 1, note = "important"
                )
            )
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trash BEFORE INSERT ON trashed_bookmarks BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            try {
                repository.moveToTrash("off-page", 10, 100)
                fail("Failed insertion must not report success")
            } catch (_: SQLiteException) {
                assertEquals("important", db.bookmarkDao().getBookmarkById("off-page")?.note)
                assertEquals(listOf("off-page"), results(db, "#Retained").map { it.id })
                assertEquals(0, db.trashDao().getCount())
            }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_trash")
            assertTrue(repository.moveToTrash("off-page", 11, 100))
            assertNull(db.bookmarkDao().getBookmarkById("off-page"))
            assertEquals("important", db.trashDao().getAllTrash().single().note)
            assertFalse(repository.moveToTrash("missing", 12, 100))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }

    @Test
    fun domainPolicyStoreMigratesLegacyJsonIdempotentlyAndDeletesFilesAfterCommit() = runBlocking {
        val folder = File(context.cacheDir, "issue233-policy-${UUID.randomUUID()}").apply { mkdirs() }
        val legacyFile = File(folder, "domain_policies.json")
        val pendingFile = File(folder, "domain_policies.json.pending")
        val db = inMemoryDatabase()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            legacyFile.writeText(
                """
                [
                  {"domain": "issue85.invalid", "isNsfw": true, "displayName": "Issue 85"},
                  {"domain": "safe.invalid", "isNsfw": false, "displayName": "Safe"}
                ]
                """.trimIndent()
            )
            pendingFile.writeText("[]")
            // Pre-populate default row that must not erase legacy isNsfw=true or displayName.
            db.domainPolicyDao().upsert(DomainPolicyEntity("issue85.invalid", isPrivate = false, displayName = ""))

            var failFirst = true
            val runner = object : TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction {
                    val result = block()
                    if (failFirst) throw IOException("Simulated commit failure")
                    result
                }
            }
            val store = DomainPolicyStore(
                database = db,
                legacyJsonFile = legacyFile,
                transactionRunner = runner,
                storeScope = scope
            )
            try {
                store.setDomainPrivateAndAwait("safe.invalid", true)
                fail("Storage failure must reach the initiating caller")
            } catch (_: IOException) {
                assertTrue(legacyFile.exists())
                assertTrue(pendingFile.exists())
                assertTrue(store.policies.value.none { it.domain == "safe.invalid" && it.isPrivate })
            }

            failFirst = false
            store.syncDomainsAndAwait(listOf("issue85.invalid", "new.invalid"))
            assertFalse(legacyFile.exists())
            assertFalse(pendingFile.exists())
            assertTrue(store.isPrivateDomain("issue85.invalid"))
            assertEquals("Issue 85", store.getDisplayName("issue85.invalid"))
            assertEquals("Safe", store.getDisplayName("safe.invalid"))
            assertFalse(store.isPrivateDomain("new.invalid"))
            assertTrue(db.domainPolicyDao().getByDomain("issue85.invalid")!!.isPrivate)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
            folder.deleteRecursively()
        }
    }

    @Test
    fun failedPolicySaveRollsBackMixedBookmarkFlagsAndSuccessfulRetryCommits() = runBlocking {
        val db = inMemoryDatabase()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var failNext = false
        val store = DomainPolicyStore(
            database = db,
            transactionRunner = object : TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction {
                    val result = block()
                    if (failNext) throw IOException("Policy write failed")
                    result
                }
            },
            storeScope = scope
        )
        val rows = listOf(
            BookmarkEntity("safe", "Safe", "https://rollback.invalid/a", "", createdAt = 1, updatedAt = 1, isPrivate = false),
            BookmarkEntity("adult", "Adult", "https://rollback.invalid/b", "", createdAt = 2, updatedAt = 2, isPrivate = true),
            BookmarkEntity("other", "Other", "https://other.invalid/a", "", createdAt = 3, updatedAt = 3, isPrivate = false)
        )
        try {
            db.bookmarkDao().insertAll(rows)
            store.syncDomainsAndAwait(listOf("rollback.invalid"))
            for (desired in listOf(true, false)) {
                failNext = true
                try {
                    store.setDomainPrivateAndAwait("rollback.invalid", desired)
                    fail("Policy save failure must be propagated")
                } catch (e: IOException) {
                    assertEquals("Policy write failed", e.message)
                }
                for (row in rows) assertEquals(row.withSearchFields(), db.bookmarkDao().getBookmarkById(row.id))
                assertFalse(db.domainPolicyDao().getByDomain("rollback.invalid")!!.isPrivate)
                assertFalse(store.isPrivateDomain("rollback.invalid"))
            }
            failNext = false
            store.setDomainPrivateAndAwait("rollback.invalid", true)
            assertTrue(db.domainPolicyDao().getByDomain("rollback.invalid")!!.isPrivate)
            assertTrue(store.isPrivateDomain("rollback.invalid"))
            assertTrue(db.bookmarkDao().getBookmarkById("safe")!!.isPrivate)
            assertTrue(db.bookmarkDao().getBookmarkById("adult")!!.isPrivate)
            assertFalse(db.bookmarkDao().getBookmarkById("other")!!.isPrivate)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
        }
    }
}
