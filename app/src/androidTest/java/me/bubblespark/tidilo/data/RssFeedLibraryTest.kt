package me.bubblespark.tidilo.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.*
import org.junit.Assert.*
import org.junit.Test

class RssFeedLibraryTest {
    @Test fun newestFirstScopesUseSavedTimeWithStableTies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.rssDao().putFeed(RssFeedEntity("feed", "https://feed.example/rss", "Feed"))
            fun row(id: String, createdAt: Long, feed: String?, isPrivate: Boolean = false) = BookmarkEntity(id, id,
                "https://article.example/$id", "", createdAt = createdAt, updatedAt = createdAt,
                rssFeedId = feed, rssPublishedAt = feed?.let { 1L }, isPrivate = isPrivate)
            db.bookmarkDao().insertAll(listOf(
                row("m1", 10, null), row("r2", 10, "feed"), row("r1", 20, "feed"), row("p", 30, "feed", isPrivate = true)
            ))
            suspend fun ids(source: PagingSource<Int, BookmarkEntity>) =
                (source.load(PagingSource.LoadParams.Refresh(null, 30, false)) as PagingSource.LoadResult.Page).data.map { it.id }
            // Combined scope orders by saved time, never publication time, with the id tie-break.
            assertEquals(listOf("r1", "r2", "m1"), ids(db.bookmarkDao().timeline(false)))
            assertEquals(listOf("p", "r1", "r2", "m1"), ids(db.bookmarkDao().timeline(true)))
            assertEquals(listOf("m1"), ids(db.bookmarkDao().search(BookmarkQueries.results("", false, source = BookmarkSource.MANUAL))))
            assertEquals(3, db.bookmarkDao().observeCount(BookmarkQueries.count("", false, source = BookmarkSource.ALL)).first())
            assertEquals(1, db.bookmarkDao().observeCount(BookmarkQueries.count("", false, source = BookmarkSource.MANUAL)).first())
            assertEquals(4, db.bookmarkDao().observeCount(BookmarkQueries.count("", true, source = BookmarkSource.ALL)).first())
        } finally { db.close() }
    }

    @Test fun groupsAndPagesUseFeedIdentityVisibleCountsAndRetainDeletedSubscriptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.rssDao().putFeed(RssFeedEntity("a", "https://same.example/a", "Alpha", deleted = true))
            db.rssDao().putFeed(RssFeedEntity("b", "https://same.example/b", "Beta", enabled = false))
            db.rssDao().putFeed(RssFeedEntity("empty", "https://same.example/empty", "Empty"))
            fun row(id: String, feed: String?, isPrivate: Boolean = false) = BookmarkEntity(id, id,
                "https://article.example/$id", "", createdAt = 1, updatedAt = 1, rssFeedId = feed, isPrivate = isPrivate)
            db.bookmarkDao().insertAll(listOf(row("one", "a"), row("two", "a", true), row("three", "b", true), row("manual", null)))
            val visible = db.rssDao().observeLibraryGroups(false).first()
            assertEquals(listOf("a"), visible.map { it.feed.id })
            assertEquals(1, visible.single().count)
            assertTrue(visible.single().feed.deleted)
            assertEquals(mapOf("a" to 2, "b" to 1), db.rssDao().observeLibraryGroups(true).first().associate { it.feed.id to it.count })
            val query = BookmarkQueries.results("", false, source = BookmarkSource.RSS, rssFeedId = "a")
            val page = db.bookmarkDao().search(query).load(PagingSource.LoadParams.Refresh(null, 30, false))
            assertEquals(listOf("one"), (page as PagingSource.LoadResult.Page).data.map { it.id })
            assertEquals(1, db.bookmarkDao().observeCount(BookmarkQueries.count("", false, source = BookmarkSource.RSS, rssFeedId = "a")).first())
            db.bookmarkDao().deleteById("one")
            assertTrue(db.rssDao().observeLibraryGroups(false).first().isEmpty())
        } finally { db.close() }
    }
}
