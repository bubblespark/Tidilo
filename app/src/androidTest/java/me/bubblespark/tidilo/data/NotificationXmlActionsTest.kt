package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssRepository
import org.junit.Assert.*
import org.junit.Test

class NotificationXmlActionsTest {
    @Test fun staleAttemptCannotDismissNewerErrorAtSameRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.rssDao().putFeed(RssFeedEntity("error", "https://example.invalid/feed", "Feed",
                revision = 4, error = "HTTP 503 again", lastAttemptAt = 200))
            val repository = RssRepository(context, db, scheduleChanged = {}, enqueueInitial = { _, _, _ -> })
            repository.dismissXmlError("error", 4, 100)
            assertEquals("HTTP 503 again", db.rssDao().feed("error")?.error)
        } finally { db.close() }
    }

    @Test fun dismissRetainsSubscriptionAndRejectsStaleRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("error", "https://example.invalid/feed", "Feed", enabled = false,
                revision = 4, error = "HTTP 503", lastAttemptAt = 123)
            db.rssDao().putFeed(feed)
            val repository = RssRepository(context, db, scheduleChanged = {}, enqueueInitial = { _, _, _ -> })
            repository.dismissXmlError(feed.id, 3, 123)
            assertEquals("HTTP 503", db.rssDao().feed(feed.id)?.error)
            repository.dismissXmlError(feed.id, 4, 123)
            val result = requireNotNull(db.rssDao().feed(feed.id))
            assertNull(result.error)
            assertFalse(result.deleted)
            assertFalse(result.enabled)
            assertEquals(123L, result.lastAttemptAt)
        } finally { db.close() }
    }

    @Test fun retrySchedulesOnlySelectedFeedWithoutBorrowedMobilePermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("selected", "https://example.invalid/feed", "Feed", enabled = false,
                revision = 4, error = "HTTP 503", lastAttemptAt = 123, acquisitionMobileOverride = true)
            db.rssDao().putFeed(feed)
            db.rssDao().putFeed(feed.copy(id = "other", url = "https://other.invalid/feed"))
            val requests = mutableListOf<Pair<String, Boolean>>()
            val repository = RssRepository(context, db, scheduleChanged = {}, enqueueInitial = { id, _, mobile -> requests += id to mobile })
            repository.retryXmlError(feed.id)
            repository.retryXmlError(feed.id)
            assertEquals(listOf("selected" to false), requests)
            val result = requireNotNull(db.rssDao().feed(feed.id))
            assertTrue(result.enabled)
            assertNotNull(result.acquisitionId)
            assertFalse(result.acquisitionMobileOverride)
            assertFalse(requireNotNull(db.rssDao().feed("other")).enabled)
        } finally { db.close() }
    }
}
