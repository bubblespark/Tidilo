package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.bubblespark.tidilo.data.db.*
import org.junit.Assert.*
import org.junit.Test

class TrashQueryTest {
    private open class StubBookmarkDao(
        private val onGetCount: suspend () -> Int = { 1 }
    ) : BookmarkDao() {
        override suspend fun toggleLike(id: String, now: Long) = Unit
        override suspend fun recordBrowserOpen(id: String, now: Long) = Unit
        override suspend fun recordDetailView(id: String, now: Long) = Unit
        override suspend fun recordSummaryPeek(id: String, now: Long) = Unit
        override fun observeCollectionCounts(): Flow<CollectionCounts> = emptyFlow()
        override fun observeCount(query: SupportSQLiteQuery): Flow<Int> = emptyFlow()
        override suspend fun getBookmarkById(id: String): BookmarkEntity? = null
        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? = null
        override suspend fun clearRssFeedId(id: String) = Unit
        override suspend fun insertRow(bookmark: BookmarkEntity) = Unit
        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) = Unit
        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) = Unit
        override suspend fun deleteOccurrences(id: String) = Unit
        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) = Unit
        override suspend fun canonicalEntityValue(skeletonKey: String): String? = null
        override suspend fun findSpellingsForKeys(keys: List<String>): List<BookmarkEntitySpelling> = emptyList()
        override suspend fun collectionsForReplacement(id: String, url: String): List<String> = emptyList()
        override suspend fun insertCollectionMembership(row: BookmarkCollectionCrossRef) = Unit
        override suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<BookmarkEntityTags> = emptyList()
        override suspend fun updateEntityRows(rows: List<BookmarkEntityTagUpdate>) = Unit
        override suspend fun updateNote(id: String, note: String, updatedAt: Long) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun deleteByIdOrUrl(id: String, url: String) = Unit
        override suspend fun getCount(): Int = onGetCount()
        override suspend fun deleteAll() = Unit
        override fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> = emptyFlow()
        override fun observeById(id: String): Flow<BookmarkEntity?> = emptyFlow()
        override fun domainCounts(showNsfw: Boolean, source: Int): Flow<List<DomainCount>> = emptyFlow()
        override suspend fun distinctDomains(): List<String> = emptyList()
        override fun observeDistinctDomains(): Flow<List<String>> = emptyFlow()
        override suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean) = Unit
    }

    private inline fun <reified T> unusedProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "UnusedProxy(${T::class.java.simpleName})"
            else -> error("Unexpected call: ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    private fun testDatabase(bookmarkDao: BookmarkDao, trashDao: TrashDao): AppDatabase = object : AppDatabase() {
        override fun collectionDao(): CollectionDao = unusedProxy()
        override fun rssDao(): RssDao = unusedProxy()
        override fun bookmarkDao(): BookmarkDao = bookmarkDao
        override fun trashDao(): TrashDao = trashDao
        override fun importTaskDao(): ImportTaskDao = unusedProxy()
        override fun importNotificationDao(): ImportNotificationDao = unusedProxy()
        override fun rssNotificationDao(): RssNotificationDao = unusedProxy()
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, emptyMap(), emptyMap(), "bookmarks", "trashed_bookmarks")
        @Deprecated("Deprecated in Java")
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = error("unused")
        @Deprecated("Deprecated in Java")
        override fun clearAllTables() = Unit
    }

    private fun trashDao(
        onObserve: (Boolean) -> Flow<List<TrashBookmarkRow>>
    ): TrashDao = object : TrashDao {
        override fun observeTrash(showNsfw: Boolean): Flow<List<TrashBookmarkRow>> = onObserve(showNsfw)
        override suspend fun getById(id: String): TrashedBookmarkEntity? = null
        override suspend fun deleteByIdCounted(id: String): Int = 0
        override suspend fun hasConflictingImport(url: String): Boolean = false
        override suspend fun getAllTrash(): List<TrashedBookmarkEntity> = emptyList()
        override suspend fun insert(item: TrashedBookmarkEntity) = Unit
        override suspend fun insertAll(items: List<TrashedBookmarkEntity>) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun purgeExpired(cutoff: Long): Int = 0
        override suspend fun enforceCapacity(maxCapacity: Int) = Unit
        override suspend fun getCount(): Int = 0
        override suspend fun deleteAll() = Unit
    }

    private val directTx = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = block()
    }

    private fun sampleRow(id: String = "t1") = TrashBookmarkRow(
        item = TrashedBookmarkEntity(id, "Saved", "https://example.com/$id", "", createdAt = 1, updatedAt = 1, note = "", isNsfw = false, trashedAt = 2),
        duplicateInLibrary = false
    )

    @Test fun initializationFailureReachesConsumerInsteadOfEndlessLoading(): Unit = runBlocking {
        val failure = IOException("initialization")
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = BookmarkRepository(
                database = testDatabase(StubBookmarkDao { throw failure }, trashDao { flowOf(listOf(sampleRow())) }),
                transactionRunner = directTx,
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
                database = testDatabase(StubBookmarkDao(), trashDao { flow { throw failure } }),
                transactionRunner = directTx,
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
                database = testDatabase(StubBookmarkDao(), trashDao {
                    queried = true
                    flowOf(expected)
                }),
                transactionRunner = directTx,
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
