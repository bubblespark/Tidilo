package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.bubblespark.tidilo.data.db.*
import org.junit.Assert.*
import org.junit.Test

class PagedQueryRecoveryTest {
    private open class StubBookmarkDao : BookmarkDao() {
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
        override suspend fun getCount(): Int = 1
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
            "getCount" -> 0
            "purgeExpired" -> 0
            "toString" -> "UnusedProxy(${T::class.java.simpleName})"
            else -> error("Unexpected call: ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    private fun testDatabase(bookmarkDao: BookmarkDao): AppDatabase = object : AppDatabase() {
        private val trash = unusedProxy<TrashDao>()
        override fun collectionDao(): CollectionDao = unusedProxy()
        override fun rssDao(): RssDao = unusedProxy()
        override fun bookmarkDao(): BookmarkDao = bookmarkDao
        override fun trashDao(): TrashDao = trash
        override fun importTaskDao(): ImportTaskDao = unusedProxy()
        override fun importNotificationDao(): ImportNotificationDao = unusedProxy()
        override fun rssNotificationDao(): RssNotificationDao = unusedProxy()
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this, emptyMap(), emptyMap(), "bookmarks")
        @Deprecated("Deprecated in Java")
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): SupportSQLiteOpenHelper = error("unused")
        @Deprecated("Deprecated in Java")
        override fun clearAllTables() = Unit
    }

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
            database = testDatabase(dao),
            transactionRunner = object : TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            },
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
                while (repository.initializationFailure.value == null) delay(10)
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
