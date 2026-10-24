package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.CollectionCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDaoBulkTest {

    private class RecordingBookmarkDao : BookmarkDao {
        val insertedRowsCalls = mutableListOf<List<BookmarkEntity>>()
        val deletedOccurrencesCalls = mutableListOf<List<String>>()
        val insertedOccurrencesCalls = mutableListOf<List<BookmarkEntityOccurrence>>()

        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) {
            insertedRowsCalls.add(bookmarks)
        }

        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) {
            deletedOccurrencesCalls.add(bookmarkIds)
        }

        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) {
            insertedOccurrencesCalls.add(rows)
        }

        override suspend fun insertRow(bookmark: BookmarkEntity) {}
        override suspend fun deleteOccurrences(id: String) {}
        override fun observeCollectionCounts(): Flow<CollectionCounts> = emptyFlow()
        override fun observeCount(query: SupportSQLiteQuery): Flow<Int> = emptyFlow()
        override suspend fun getBookmarkById(id: String): BookmarkEntity? = null
        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? = null
        override suspend fun updateNote(id: String, note: String, updatedAt: Long) {}
        override suspend fun deleteById(id: String) {}
        override suspend fun deleteByIdOrUrl(id: String, url: String) {}
        override suspend fun getCount(): Int = 0
        override suspend fun deleteAll() {}
        override fun timeline(showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun byDomain(domain: String, showNsfw: Boolean): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun search(query: SupportSQLiteQuery): PagingSource<Int, BookmarkEntity> = error("unused")
        override fun tagCounts(query: SupportSQLiteQuery): Flow<List<TagCount>> = emptyFlow()
        override fun observeById(id: String): Flow<BookmarkEntity?> = emptyFlow()
        override fun domainCounts(showNsfw: Boolean, source: Int): Flow<List<DomainCount>> = emptyFlow()
        override suspend fun distinctDomains(): List<String> = emptyList()
        override fun observeDistinctDomains(): Flow<List<String>> = emptyFlow()
        override suspend fun updateDomainNsfw(domain: String, isNsfw: Boolean) {}
    }

    @Test
    fun insertAllChunksOperationsInBatchesOf500() = runBlocking {
        val dao = RecordingBookmarkDao()
        val totalCount = 1250
        val bookmarks = (1..totalCount).map { i ->
            BookmarkEntity(
                id = "bm-$i",
                title = "Title $i",
                url = "https://example.com/$i",
                description = "Desc $i",
                entities = listOf("TagA", "TagB"),
                createdAt = 1000L,
                updatedAt = 1000L
            )
        }

        dao.insertAll(bookmarks)

        // 1250 chunked by 500 results in 3 chunks: 500, 500, 250
        assertEquals(3, dao.insertedRowsCalls.size)
        assertEquals(500, dao.insertedRowsCalls[0].size)
        assertEquals(500, dao.insertedRowsCalls[1].size)
        assertEquals(250, dao.insertedRowsCalls[2].size)

        assertEquals(3, dao.deletedOccurrencesCalls.size)
        assertEquals(500, dao.deletedOccurrencesCalls[0].size)
        assertEquals(500, dao.deletedOccurrencesCalls[1].size)
        assertEquals(250, dao.deletedOccurrencesCalls[2].size)

        // Each bookmark has 2 entities, so 1000, 1000, 500 occurrences
        assertEquals(3, dao.insertedOccurrencesCalls.size)
        assertEquals(1000, dao.insertedOccurrencesCalls[0].size)
        assertEquals(1000, dao.insertedOccurrencesCalls[1].size)
        assertEquals(500, dao.insertedOccurrencesCalls[2].size)

        // All chunks are <= 500 bookmarks
        assertTrue(dao.insertedRowsCalls.all { it.size <= 500 })
        assertTrue(dao.deletedOccurrencesCalls.all { it.size <= 500 })
    }

    @Test
    fun insertAllEmptyListDoesNothing() = runBlocking {
        val dao = RecordingBookmarkDao()
        dao.insertAll(emptyList())

        assertTrue(dao.insertedRowsCalls.isEmpty())
        assertTrue(dao.deletedOccurrencesCalls.isEmpty())
        assertTrue(dao.insertedOccurrencesCalls.isEmpty())
    }
}
