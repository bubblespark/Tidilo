package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.data.rss.RssIngestionStore
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.parser.PreparedArticle
import me.bubblespark.tidilo.parser.RssEntry
import org.junit.Assert.*
import org.junit.Test

class NotificationRoomTest {
    @Test fun queueCapDiscardMarksUnstartedNotificationItemsSkipped() = runBlocking {
        val db = database()
        try {
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", "work", 100, true))
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db) { AiAnalysisMode.AICORE }
            store.stage(feed, listOf(RssEntry("old", "https://example.com/old", "Old", "", "", null, 1)), 100, "run")
            val old = db.importTaskDao().getAllTasks().single()
            store.stage(feed, (1..6).map { RssEntry("$it", "https://example.com/new/$it", "New", "", "", null, 200L + it) }, 200)
            assertNull(db.importTaskDao().getTaskById(old.id))
            assertEquals(listOf("SKIPPED"), dao.items("run").map { it.outcome })
        } finally { db.close() }
    }
    @Test fun recoveredDuplicateResolvesTheErrorWithoutCountingADirectSave() = runBlocking {
        val db = database()
        try {
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", "work", 100, true))
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db) { AiAnalysisMode.DISABLE }
            store.stage(feed, listOf(RssEntry("key", "https://example.com/post", "Post", "", "", null, null)), 100)
            val task = db.importTaskDao().getAllTasks().single()
            store.failArticle(feed, task, java.io.IOException("HTTP 503"), true, "run")
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("existing", "Existing", "https://example.com/redirect", "", createdAt = 1, updatedAt = 1))
            assertTrue(store.complete(feed, task, article("https://example.com/redirect"), runId = "run"))
            assertEquals(listOf("SKIPPED"), dao.items("run").map { it.outcome })
        } finally { db.close() }
    }

    @Test fun disabledLastFeedRecoversItsUnfinishedNotificationAfterRestart() = runBlocking {
        val db = database()
        try {
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", "work", 100, true))
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            db.rssDao().putFeed(feed)
            RssIngestionStore(db).disableAfterXmlFailure(feed, "run")
            dao.finishRunsWithoutEnabledFeeds()
            assertEquals(listOf("run"), dao.unpublishedRuns().map { it.id })
            assertEquals(listOf("RSS_ERROR"), dao.items("run").map { it.outcome })
        } finally { db.close() }
    }
    @Test fun versionTwelveUpgradePreservesBookmarksAndCreatesNotificationAccounting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "notification-migration-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("kept", "Kept", "https://example.com/kept", "Description", createdAt = 1, updatedAt = 2))
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use { sql ->
                sql.execSQL("DROP TABLE rss_notification_items")
                sql.execSQL("DROP TABLE rss_notification_runs")
                sql.execSQL("DELETE FROM room_master_table")
                sql.execSQL("DROP INDEX index_bookmark_entities_skeletonKey")
                sql.execSQL("ALTER TABLE bookmark_entities DROP COLUMN skeletonKey")
                sql.version = 12
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14).build()
            assertEquals("Description", db.bookmarkDao().getBookmarkById("kept")!!.description)
            assertEquals(14, db.openHelper.readableDatabase.version)
            db.rssNotificationDao().putRun(RssNotificationRunEntity("run", "work", 100, true))
            assertEquals("run", db.rssNotificationDao().unfinishedRun("work")?.id)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun pendingAiSnapshotSurvivesProcessingAndFinishedRunLookupPreventsReplay() = runBlocking {
        val db = database()
        try {
            val dao = db.rssNotificationDao()
            dao.putRun(RssNotificationRunEntity("run", "work", 100, true))
            db.importTaskDao().insertOrUpdate(task("pending").copy(rssFeedId = "feed"))
            dao.putItem(RssNotificationItemEntity("run", "pending", "AI"))
            dao.finish("run")
            db.importTaskDao().deleteById("pending")
            assertEquals(1, dao.runFor("work", 100)!!.pendingAi)
            assertTrue(dao.runFor("work", 100)!!.completed)
            dao.finish("run")
            assertEquals(1, dao.runFor("work", 100)!!.pendingAi)
        } finally { db.close() }
    }
    @Test fun readyCountIncludesRssButNotProcessingOrErrors() = runBlocking {
        val db = database()
        try {
            db.importTaskDao().insertAll(listOf(task("manual"), task("rss").copy(rssFeedId = "feed"),
                task("waiting").copy(status = "WAITING_AI"), task("error").copy(status = "ERROR")))
            assertEquals(2, db.importTaskDao().observeReadyNotificationCount().first())
            db.importTaskDao().updateStatus("rss", "PROCESSING_AI", "Running")
            assertEquals(1, db.importTaskDao().observeReadyNotificationCount().first())
            db.importTaskDao().deleteById("manual")
            assertEquals(0, db.importTaskDao().observeReadyNotificationCount().first())
        } finally { db.close() }
    }

    @Test fun committedRssOutcomesAreDistinctRecoverErrorsAndRollbackTogether() = runBlocking {
        val db = database()
        try {
            val events = db.rssNotificationDao()
            events.putRun(RssNotificationRunEntity("run", "work", 100, true))
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db) { AiAnalysisMode.DISABLE }
            store.stage(feed, listOf(RssEntry("key", "https://example.com/post", "Post", "", "", null, null)), 100)
            val task = db.importTaskDao().getAllTasks().single()
            store.failArticle(feed, task, java.io.IOException("HTTP 503"), true, "run")
            assertEquals(1, events.items("run").count { it.outcome == "IMPORT_ERROR" })
            assertTrue(store.complete(feed, task, article(task.url), runId = "run"))
            assertEquals(listOf("DIRECT"), events.items("run").map { it.outcome })
            assertFalse(store.complete(feed, task, article(task.url), runId = "run"))
            assertEquals(1, events.items("run").size)
            try {
                db.withTransaction {
                    events.putItem(RssNotificationItemEntity("run", "rolled-back", "DIRECT"))
                    error("Rollback")
                }
            } catch (_: IllegalStateException) { }
            assertEquals(1, events.items("run").size)
            assertEquals("run", events.unfinishedRun("work")?.id)
            events.finish("run")
            assertNull(events.unfinishedRun("work"))
            assertEquals(1, events.unpublishedRuns().size)
            events.markPublished("run")
            assertTrue(events.unpublishedRuns().isEmpty())
        } finally { db.close() }
    }

    @Test fun xmlDisableAndArticleFailureHaveDifferentOutcomeCategories() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://example.com/feed", "Feed")
            db.rssDao().putFeed(feed)
            db.rssNotificationDao().putRun(RssNotificationRunEntity("run", "work", 100, false))
            db.importTaskDao().insertOrUpdate(task("article").copy(rssFeedId = feed.id, status = "ERROR"))
            val store = RssIngestionStore(db) { AiAnalysisMode.AICORE }
            store.failArticle(feed, db.importTaskDao().getTaskById("article")!!, Exception("HTTP 404"), false, "run")
            assertTrue(db.rssDao().feed(feed.id)!!.enabled)
            assertTrue(store.disableAfterXmlFailure(feed, "run"))
            assertFalse(db.rssDao().feed(feed.id)!!.enabled)
            assertEquals(setOf("IMPORT_ERROR", "RSS_ERROR"), db.rssNotificationDao().items("run").map { it.outcome }.toSet())
        } finally { db.close() }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    private fun task(id: String) = ImportTaskEntity(id, "https://example.com/$id", id, "READY", "Ready", createdAt = 100)
    private fun article(url: String) = PreparedArticle(url, "Title", "Site", "Description", "Body", null, false)
}
