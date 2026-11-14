package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.CollectionCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDaoUpdateFieldsTest {

    private class TestableBookmarkDao : BookmarkDao() {
        var lastInserted: BookmarkEntity? = null
        var bookmarkToReturn: BookmarkEntity? = null

        override suspend fun getBookmarkById(id: String): BookmarkEntity? = bookmarkToReturn
        override suspend fun insertRow(bookmark: BookmarkEntity) {
            lastInserted = bookmark
        }
        override suspend fun deleteOccurrences(id: String) {}
        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) {}
        override suspend fun canonicalEntityValue(skeletonKey: String): String? = null
        override suspend fun collectionsForReplacement(id: String, url: String): List<String> = emptyList()
        override suspend fun insertCollectionMembership(row: BookmarkCollectionCrossRef) {}
        override suspend fun toggleLike(id: String, now: Long) {}
        override suspend fun recordBrowserOpen(id: String, now: Long) {}
        override suspend fun recordDetailView(id: String, now: Long) {}
        override suspend fun recordSummaryPeek(id: String, now: Long) {}
        override suspend fun findSpellingsForKeys(keys: List<String>): List<BookmarkEntitySpelling> = emptyList()
        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) {}
        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) {}
        override suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<BookmarkEntityTags> = emptyList()
        override suspend fun updateEntityRows(rows: List<BookmarkEntityTagUpdate>) {}
        override fun observeCollectionCounts(): Flow<CollectionCounts> = emptyFlow()
        override fun observeCount(query: SupportSQLiteQuery): Flow<Int> = emptyFlow()
        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? = null
        override suspend fun updateNote(id: String, note: String, updatedAt: Long) {}
        override suspend fun deleteById(id: String) {}
        override suspend fun deleteByIdOrUrl(id: String, url: String) {}
        override suspend fun getCount(): Int = 0
        override suspend fun deleteAll() {}
        override suspend fun clearRssFeedId(id: String) {}
        override fun timeline(showPrivate: Boolean): PagingSource<Int, BookmarkEntity> = throw UnsupportedOperationException()
        override fun byDomain(domain: String, showPrivate: Boolean): PagingSource<Int, BookmarkEntity> = throw UnsupportedOperationException()
        override fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity> = throw UnsupportedOperationException()
        override fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> = emptyFlow()
        override fun observeById(id: String): Flow<BookmarkEntity?> = emptyFlow()
        override fun domainCounts(showPrivate: Boolean, source: Int): Flow<List<DomainCount>> = emptyFlow()
        override suspend fun distinctDomains(): List<String> = emptyList()
        override fun observeDistinctDomains(): Flow<List<String>> = emptyFlow()
        override suspend fun updateDomainPrivate(domain: String, isPrivate: Boolean) {}
    }

    private fun sampleEntity(id: String): BookmarkEntity = BookmarkEntity(
        id = id,
        title = "Title",
        url = "https://example.com",
        description = "Initial description",
        category = null,
        subcategory = null,
        entities = emptyList(),
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Test
    fun updateFields_updatesDescriptionAndTagFields(): Unit = runBlocking {
        val dao = TestableBookmarkDao()
        dao.bookmarkToReturn = sampleEntity("bm-1")

        dao.updateFields(
            id = "bm-1",
            description = "New AI summary",
            category = "Article",
            subcategory = "Tech",
            entities = listOf("AI"),
            updatedAt = 2000L
        )

        val updated = dao.lastInserted
        requireNotNull(updated)
        assertEquals("New AI summary", updated.description)
        assertEquals("Article", updated.category)
        assertEquals("Tech", updated.subcategory)
        assertEquals(listOf("AI"), updated.entities)
        assertEquals(2000L, updated.updatedAt)
    }
}
