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
        override suspend fun toggleLike(id: String, now: Long) = Unit
        override suspend fun recordBrowserOpen(id: String, now: Long) = Unit
        override suspend fun recordDetailView(id: String, now: Long) = Unit
        override suspend fun recordSummaryPeek(id: String, now: Long) = Unit
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
        override suspend fun clearRssFeedId(id: String) {}
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

    @Test
    fun insertAllDeduplicatesByIdKeepingFinalBookmarkInChunk() = runBlocking {
        val dao = RecordingBookmarkDao()
        val first = BookmarkEntity(
            id = "bm-1",
            title = "Initial Title",
            url = "https://example.com/1",
            description = "Initial Desc",
            entities = listOf("TagA", "TagB"),
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val second = BookmarkEntity(
            id = "bm-1",
            title = "Final Title",
            url = "https://example.com/1",
            description = "Final Desc",
            entities = listOf("TagC"),
            createdAt = 1000L,
            updatedAt = 2000L
        )

        dao.insertAll(listOf(first, second))

        // Only the final bookmark row is inserted
        assertEquals(1, dao.insertedRowsCalls.size)
        assertEquals(1, dao.insertedRowsCalls[0].size)
        assertEquals("Final Title", dao.insertedRowsCalls[0][0].title)

        // Only the final bookmark's occurrences are inserted (TagC at position 0)
        assertEquals(1, dao.insertedOccurrencesCalls.size)
        val persistedOccurrences = dao.insertedOccurrencesCalls[0]
        assertEquals(1, persistedOccurrences.size)
        assertEquals(0, persistedOccurrences[0].position)
        assertEquals("TagC", persistedOccurrences[0].value)
    }
}
