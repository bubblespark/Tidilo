package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef
import me.bubblespark.tidilo.data.db.BookmarkDao
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.BookmarkEntitySpelling
import me.bubblespark.tidilo.data.db.BookmarkEntityTagUpdate
import me.bubblespark.tidilo.data.db.BookmarkEntityTags
import me.bubblespark.tidilo.data.db.CollectionDao
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.data.db.ImportNotificationDao
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.RssDao
import me.bubblespark.tidilo.data.db.RssNotificationDao
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import me.bubblespark.tidilo.data.db.TrashDao
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity

/** Minimal [BookmarkDao] test double returning harmless defaults for unexercised queries. */
internal open class StubBookmarkDao(
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

/** Minimal [TrashDao] test double returning empty results for unexercised queries. */
internal open class StubTrashDao : TrashDao {
    override fun observeTrash(showNsfw: Boolean): Flow<List<TrashBookmarkRow>> = emptyFlow()
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

internal inline fun <reified T> unusedDaoProxy(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java)
) { _, method, _ ->
    when (method.name) {
        "toString" -> "UnusedProxy(${T::class.java.simpleName})"
        else -> error("Unexpected call: ${T::class.java.simpleName}.${method.name}")
    }
} as T

internal fun fakeAppDatabase(
    bookmarkDao: BookmarkDao = StubBookmarkDao(),
    trashDao: TrashDao = StubTrashDao()
): AppDatabase = object : AppDatabase() {
    override fun collectionDao(): CollectionDao = unusedDaoProxy()
    override fun rssDao(): RssDao = unusedDaoProxy()
    override fun bookmarkDao(): BookmarkDao = bookmarkDao
    override fun trashDao(): TrashDao = trashDao
    override fun importTaskDao(): ImportTaskDao = unusedDaoProxy()
    override fun importNotificationDao(): ImportNotificationDao = unusedDaoProxy()
    override fun rssNotificationDao(): RssNotificationDao = unusedDaoProxy()
    override fun createInvalidationTracker(): InvalidationTracker =
        InvalidationTracker(this, emptyMap(), emptyMap(), "bookmarks", "trashed_bookmarks")
    @Deprecated("Deprecated in Java")
    override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = error("unused")
    @Deprecated("Deprecated in Java")
    override fun clearAllTables() = Unit
}

internal fun directTransactionRunner(): TransactionRunner = object : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}
