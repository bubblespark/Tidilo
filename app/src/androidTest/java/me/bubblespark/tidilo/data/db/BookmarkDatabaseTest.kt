package me.bubblespark.tidilo.data.db

import android.content.Context
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.removeStandardClassificationColumns
import me.bubblespark.tidilo.data.rewindPrivateColumnsToLegacyNsfw
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.security.MessageDigest

class BookmarkDatabaseTest {
    private fun rewindPrivateColumnsToLegacyNsfw(sql: SupportSQLiteDatabase, includeDomainPolicies: Boolean) {
        rewindPrivateColumnsToLegacyNsfw(sql::execSQL, includeDomainPolicies)
    }

    @Test fun versionTwentyOneRenamesNsfwColumnsAndIndexToPrivate() = runBlocking {
        val name = "private-rename-migration-${UUID.randomUUID()}.db"
        val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            before.bookmarkDao().insertAll(
                listOf(
                    BookmarkEntity("private-b", "Private", "https://private.invalid/1", "", createdAt = 1, updatedAt = 1, isPrivate = true),
                    BookmarkEntity("normal-b", "Normal", "https://normal.invalid/2", "", createdAt = 2, updatedAt = 2, isPrivate = false)
                )
            )
            before.trashDao().insert(
                TrashedBookmarkEntity("private-t", "Trashed", "https://private.invalid/trash", "", createdAt = 1, updatedAt = 1, note = "", isPrivate = true, trashedAt = 3)
            )
            before.importTaskDao().insertOrUpdate(
                ImportTaskEntity(id = "private-i", url = "https://private.invalid/import", title = "Import", status = "READY", statusText = "Ready", assignedTags = emptyList(), createdAt = 1, isPrivate = true)
            )
            before.domainPolicyDao().upsert(
                DomainPolicyEntity("private.invalid", isPrivate = true, displayName = "Private Site")
            )
            val sql = before.openHelper.writableDatabase
            rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = true)
            sql.version = 21
        } finally { before.close() }
        val after = database(name)
        try {
            val readable = after.openHelper.readableDatabase
            assertEquals(22, readable.version)
            for (table in listOf("bookmarks", "trashed_bookmarks", "import_tasks", "domain_policies")) {
                val cols = columns(readable, table)
                assertTrue("Expected isPrivate column on $table, got $cols", "isPrivate" in cols)
                assertFalse("Unexpected isNsfw column on $table, got $cols", "isNsfw" in cols)
            }
            val indexes = readable.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='bookmarks'").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue("Expected index_bookmarks_isPrivate in $indexes", "index_bookmarks_isPrivate" in indexes)
            assertFalse("Unexpected index_bookmarks_isNsfw in $indexes", "index_bookmarks_isNsfw" in indexes)

            assertTrue(after.bookmarkDao().getBookmarkById("private-b")!!.isPrivate)
            assertFalse(after.bookmarkDao().getBookmarkById("normal-b")!!.isPrivate)
            assertTrue(after.trashDao().getById("private-t")!!.isPrivate)
            assertTrue(after.importTaskDao().getTaskById("private-i")!!.isPrivate)
            assertTrue(after.domainPolicyDao().getByDomain("private.invalid")!!.isPrivate)
        } finally { after.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwentyPreservesBookmarksAndCreatesDomainPolicies() = runBlocking {
        val name = "domain-policy-migration-${UUID.randomUUID()}.db"
        val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            before.bookmarkDao().insertOrUpdate(BookmarkEntity("kept", "Keep", "https://kept.invalid/article", "",
                createdAt = 1, updatedAt = 1, isPrivate = true))
            val sql = before.openHelper.writableDatabase
            rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
            sql.version = 20
        } finally { before.close() }
        val after = database(name)
        try {
            assertEquals(22, after.openHelper.readableDatabase.version)
            val kept = after.bookmarkDao().getBookmarkById("kept")
            assertNotNull(kept)
            assertTrue(kept!!.isPrivate)
            assertEquals(listOf("domain", "isPrivate", "displayName"), columns(after.openHelper.readableDatabase, "domain_policies"))
            after.domainPolicyDao().upsert(DomainPolicyEntity("kept.invalid", isPrivate = true, displayName = "Kept"))
            val stored = after.domainPolicyDao().getByDomain("kept.invalid")
            assertNotNull(stored)
            assertTrue(stored!!.isPrivate)
            assertEquals("Kept", stored.displayName)
        } finally { after.close(); context.deleteDatabase(name) }
    }

    @Test fun versionNineteenPreservesImageDeletionJobsAndAllowsMultipleDomains() = runBlocking {
        val name = "image-cleanup-migration-${UUID.randomUUID()}.db"
        val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            before.bookmarkDao().insertOrUpdate(BookmarkEntity("kept", "Keep", "https://first.invalid/article", "",
                createdAt = 1, updatedAt = 1))
            val sql = before.openHelper.writableDatabase
            sql.execSQL("DROP TABLE rss_image_cleanup")
            sql.execSQL("CREATE TABLE rss_image_cleanup (imageUrl TEXT NOT NULL PRIMARY KEY, articleUrl TEXT NOT NULL)")
            sql.execSQL("INSERT INTO rss_image_cleanup VALUES ('https://images.invalid/shared.webp', 'https://first.invalid/article')")
            rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
            sql.version = 19
        } finally { before.close() }
        val after = database(name)
        try {
            assertEquals(22, after.openHelper.readableDatabase.version)
            assertNotNull(after.bookmarkDao().getBookmarkById("kept"))
            assertEquals(1, after.rssDao().cleanupItems().size)
            after.rssDao().enqueueCleanup(RssImageCleanupEntity("https://images.invalid/shared.webp", "https://second.invalid/article"))
            assertEquals(2, after.rssDao().cleanupItems().size)
        } finally { after.close(); context.deleteDatabase(name) }
    }

    @Test fun versionSixteenQueuedImportSurvivesClassificationMigration() = runBlocking {
        val name = "classification-migration-${UUID.randomUUID()}.db"
        val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val queued = ImportTaskEntity(
            id = "queued", url = "https://example.com/page", title = "Queued", status = "READY",
            statusText = "Ready", assignedTags = listOf("Existing"), createdAt = 1, articlePrepared = true
        )
        try {
            before.importTaskDao().insertOrUpdate(queued)
            val sql = before.openHelper.writableDatabase
            removeStandardClassificationColumns(sql::execSQL)
            me.bubblespark.tidilo.data.restoreLegacyAiSkippedColumns(sql::execSQL)
            rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
            sql.version = 16
        } finally { before.close() }
        val after = database(name)
        try {
            assertEquals(22, after.openHelper.readableDatabase.version)
            assertEquals(queued.copy(status = "QUEUED", statusText = ""), after.importTaskDao().getTaskById(queued.id))
        } finally { after.close(); context.deleteDatabase(name) }
    }

    @Test fun prereleaseEngagementVersionFourteenPreservesSignalsAndBuildsEntityIndex() = runBlocking {
        val name = "issue165-prerelease-${UUID.randomUUID()}.db"
        val helper = legacy(name, 13)
        val old = helper.writableDatabase
        for (table in listOf("bookmarks", "trashed_bookmarks")) {
            for (column in listOf("isLiked", "browserOpenCount", "detailViewCount", "summaryPeekCount")) {
                old.execSQL("ALTER TABLE $table ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
            }
            old.execSQL("ALTER TABLE $table ADD COLUMN lastInteractedAt INTEGER")
        }
        old.execSQL("INSERT INTO bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,isLiked,browserOpenCount,detailViewCount,summaryPeekCount,lastInteractedAt) VALUES('kept','Title','https://example.com/a','Summary','[\"Tag\"]',1,2,'Note',0,0,1,2,3,4,1234)")
        old.version = 14
        helper.close()
        val db = database(name)
        try {
            val item = db.bookmarkDao().getBookmarkById("kept")!!
            assertTrue(item.isLiked)
            assertEquals(2, item.browserOpenCount)
            assertEquals(3, item.detailViewCount)
            assertEquals(4, item.summaryPeekCount)
            assertEquals(1234L, item.lastInteractedAt)
            assertTrue(columns(db.openHelper.readableDatabase, "bookmark_entities").contains("skeletonKey"))
            assertEquals(listOf("Tag"), item.entities)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun engagementMutationsAreAtomicAndLikeOffPreservesTimestamp() = runBlocking {
        val name = "issue165-atomic-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(BookmarkEntity("liked", "Saved", "https://example.com", "", createdAt = 1, updatedAt = 1))
            coroutineScope {
                repeat(40) { launch(Dispatchers.IO) { dao.recordBrowserOpen("liked", 10) } }
                repeat(30) { launch(Dispatchers.IO) { dao.recordDetailView("liked", 20) } }
                repeat(20) { launch(Dispatchers.IO) { dao.recordSummaryPeek("liked", 30) } }
            }
            val counts = dao.getBookmarkById("liked")!!
            assertEquals(40, counts.browserOpenCount)
            assertEquals(30, counts.detailViewCount)
            assertEquals(20, counts.summaryPeekCount)
            dao.toggleLike("liked", 1234)
            assertTrue(dao.observeById("liked").first()!!.isLiked)
            dao.toggleLike("liked", 2345)
            val unliked = dao.getBookmarkById("liked")!!
            assertFalse(unliked.isLiked)
            assertEquals(1234L, unliked.lastInteractedAt)
            dao.recordBrowserOpen("missing", 1)
            dao.toggleLike("missing", 1)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun legacyVersionsPreserveRowsAndDefaultEngagement() = runBlocking {
      for (version in listOf(13, 14)) {
        val name = "issue165-${UUID.randomUUID()}.db"
        val helper = legacy(name, version)
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped) VALUES('active','Active','https://example.com/a','Summary','[]',1,2,'Note',0,0)")
        old.execSQL("INSERT INTO trashed_bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) VALUES('trash','Trash','https://example.com/t','Summary','[]',1,2,'Trash note',0,0,3)")
        old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Feed','DIRECT',100,0,4,0)")
        helper.close()
        val db = database(name)
        try {
            for (table in listOf("bookmarks", "trashed_bookmarks")) {
                db.openHelper.readableDatabase.query("SELECT isLiked,browserOpenCount,detailViewCount,summaryPeekCount,lastInteractedAt,note FROM $table").use {
                    assertTrue(it.moveToFirst())
                    for (column in 0..3) assertEquals(0, it.getInt(column))
                    assertTrue(it.isNull(4))
                    assertEquals(if (table == "bookmarks") "Note" else "Trash note", it.getString(5))
                }
            }
            assertEquals("Feed", db.rssDao().feed("feed")!!.title)
        } finally { db.close(); context.deleteDatabase(name) }
      }
    }
    @Test fun legacyEntitySpellingsUpgradeOnceWithIndexedOccurrenceParity() = runBlocking {
        for (version in listOf(6, 13)) {
            val name = "issue164-v$version-${UUID.randomUUID()}.db"
            val helper = legacy(name, version)
            val old = helper.writableDatabase
            val converter = Converters()
            fun seed(id: String, createdAt: Long, values: List<String>, hidden: Boolean = false) {
                old.execSQL("INSERT INTO bookmarks(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,ogImageUrl) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(id, "Title $id", "https://example.com/$id", "Description", "Article", "Tech",
                        converter.fromStringList(values), createdAt, 99L, "Saved note", if (hidden) 1 else 0, 0, "https://example.com/image.png"))
            }
            // All raw entries count, even those beyond the eventual ten-key limit.
            seed("a", 20, listOf("First", "Open AI", "Open AI") + (1..12).map { "Key$it" })
            seed("hidden", 30, (1..11).map { "Other$it" } + "Open AI", hidden = true)
            seed("repeat", 1, List(10) { "OpenAI" } + listOf("Last", " -_. "))
            seed("old", 1, listOf("Git Hub", "C D"))
            seed("new", 2, listOf("GitHub", "CD"))
            seed("tie", 1, listOf("C_D"))
            seed("prefix", 1, listOf("##C", "C"))
            if (version == 13) {
                old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision) VALUES('feed','https://example.com/feed','Saved feed','DIRECT',100,0,1)")
                old.execSQL("UPDATE bookmarks SET rssFeedId='feed',rssPublishedAt=123,ogImageKey='saved-image-key' WHERE id='a'")
            }
            old.execSQL("INSERT INTO trashed_bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) VALUES('trash','Trash','https://example.com/trash','Description','[\"OpenAI\",\"Open AI\"]',1,2,'Trash note',1,0,3)")
            val keptColumns = columns(old, "bookmarks").filterNot { it == "entities" || it == "domain" || it.startsWith("search") || it == "isAiSkipped" }
            val before = fingerprint(old, "bookmarks", keptColumns)
            val trashColumns = columns(old, "trashed_bookmarks").filterNot { it == "isAiSkipped" }
            val trashBefore = fingerprint(old, "trashed_bookmarks", trashColumns)
            helper.close()
            var db = database(name)
            try {
                val sql = db.openHelper.writableDatabase
                assertEquals(22, sql.version)
                assertTrue(columns(sql, "bookmark_entities").contains("skeletonKey"))
                sql.query("PRAGMA index_list(bookmark_entities)").use { cursor ->
                    val names = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                    assertTrue(names.contains("index_bookmark_entities_skeletonKey"))
                }
                assertEquals(before, fingerprint(sql, "bookmarks", keptColumns.map { if (it == "isNsfw") "isPrivate" else it }))
                assertEquals(trashBefore, fingerprint(sql, "trashed_bookmarks", trashColumns.map { if (it == "isNsfw") "isPrivate" else it }))
                assertTrue(!columns(sql, "bookmarks").contains("isAiSkipped"))
                assertTrue(!columns(sql, "trashed_bookmarks").contains("isAiSkipped"))
                val expected = mapOf(
                    "a" to (listOf("First", "Open AI") + (1..8).map { "Key$it" }),
                    "hidden" to (1..10).map { "Other$it" },
                    "repeat" to listOf("Open AI", "Last"), "old" to listOf("Git Hub", "C D"),
                    "new" to listOf("Git Hub", "C D"), "tie" to listOf("C D"), "prefix" to listOf("#C", "C")
                )
                for ((id, values) in expected) {
                    assertEquals(values, db.bookmarkDao().getBookmarkById(id)?.entities)
                    sql.query("SELECT position,value,searchValue,skeletonKey FROM bookmark_entities WHERE bookmarkId=? ORDER BY position", arrayOf(id)).use { cursor ->
                        var index = 0
                        while (cursor.moveToNext()) {
                            assertEquals(index, cursor.getInt(0))
                            assertEquals(values[index], cursor.getString(1))
                            assertEquals(bookmarkSearchKey(values[index]), cursor.getString(2))
                            assertEquals(me.bubblespark.tidilo.data.entitySkeletonKey(values[index]), cursor.getString(3))
                            index++
                        }
                        assertEquals(values.size, index)
                    }
                }
                // A later manual spelling must survive onOpen, including a derived-format repair.
                sql.execSQL("UPDATE bookmarks SET entities='[\"Open_AI\"]' WHERE id='repeat'")
                sql.execSQL("PRAGMA application_id=0")
                repeat(2) {
                    db.close()
                    db = database(name)
                    assertEquals(listOf("Open_AI"), db.bookmarkDao().getBookmarkById("repeat")?.entities)
                    assertEquals(listOf("#C", "C"), db.bookmarkDao().getBookmarkById("prefix")?.entities)
                    db.openHelper.readableDatabase.query("SELECT value,skeletonKey FROM bookmark_entities WHERE bookmarkId='prefix' ORDER BY position").use {
                        assertTrue(it.moveToNext()); assertEquals("#C", it.getString(0)); assertEquals("#c", it.getString(1))
                        assertTrue(it.moveToNext()); assertEquals("C", it.getString(0)); assertEquals("c", it.getString(1))
                        assertFalse(it.moveToNext())
                    }
                    db.openHelper.readableDatabase.query("SELECT value,skeletonKey FROM bookmark_entities WHERE bookmarkId='repeat'").use {
                        assertTrue(it.moveToFirst()); assertEquals("Open_AI", it.getString(0)); assertEquals("openai", it.getString(1))
                    }
                }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun bothVersionElevenSchemasUpgradeWithoutLosingSavedData() = runBlocking {
        for (feedMetadataVariant in listOf(false, true)) {
            val name = "issue108-v11-${UUID.randomUUID()}.db"
            val helper = legacy(name, 11, feedMetadataVariant)
            val old = helper.writableDatabase
            old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Saved feed','DIRECT',100,1,4,0)")
            old.execSQL("INSERT INTO bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,rssFeedId) VALUES('kept','Article','https://example.com/article','Summary','[]',1,2,'Saved note',0,0,'feed')")
            old.execSQL("INSERT INTO rss_seen_items(feedId,itemKey,url) VALUES('feed','receipt','https://example.com/article')")
            old.execSQL("INSERT INTO import_tasks(id,url,title,status,statusText,assignedTags,createdAt,isNsfw,siteName,description,bodySnippet,rssFeedId) VALUES('task','https://example.com/task','Queued','ERROR','Kept','[]',1,0,'Feed','Description','Body','feed')")
            if (feedMetadataVariant) {
                old.execSQL("UPDATE rss_feeds SET iconUrl='https://example.com/icon.png',siteUrl='https://publisher.example/'")
            } else {
                old.execSQL("INSERT INTO import_notifications(id,type,domain,url,message,isRead,createdAt) VALUES(7,'WARNING','example.com','https://example.com/article','Saved notification',0,3)")
            }
            helper.close()
            val db = database(name)
            try {
                assertEquals(22, db.openHelper.readableDatabase.version)
                val feed = db.rssDao().feed("feed")!!
                assertEquals(if (feedMetadataVariant) "https://example.com/icon.png" else null, feed.iconUrl)
                assertEquals(if (feedMetadataVariant) "https://publisher.example/" else null, feed.siteUrl)
                assertTrue(feed.deleted); assertFalse(feed.enabled); assertEquals(100, feed.retentionLimit)
                assertEquals("Saved note", db.bookmarkDao().getBookmarkById("kept")!!.note)
                assertEquals("ERROR", db.importTaskDao().getTaskById("task")!!.status)
                assertTrue(db.rssDao().hasSeen("feed", "receipt", "https://example.com/article"))
                val notifications = db.importNotificationDao().observeNotifications().first()
                assertEquals(if (feedMetadataVariant) 0 else 1, notifications.size)
                if (!feedMetadataVariant) assertEquals("Saved notification", notifications.single().message)
                db.importNotificationDao().insert(ImportNotificationEntity(type = "INFO", domain = "example.com",
                    url = "https://example.com/new", message = "New notification"))
                assertEquals(if (feedMetadataVariant) 1 else 2, db.importNotificationDao().observeUnreadCount().first())
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun legacyFeedsGainNullableMetadataWithoutLosingSubscriptionOrArticles() = runBlocking {
        val name = "issue99-migration-${UUID.randomUUID()}.db"
        val helper = legacy(name, 10)
        helper.writableDatabase.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Kept feed','DIRECT',100,1,4,0)")
        helper.close()
        val db = database(name)
        try {
            db.openHelper.readableDatabase.query("SELECT iconUrl,siteUrl,title,deleted,enabled FROM rss_feeds WHERE id='feed'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
                assertEquals("Kept feed", it.getString(2)); assertEquals(1, it.getInt(3)); assertEquals(0, it.getInt(4))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun schemaNinePreservesXmlTasksAndRequiresHtmlBeforeSaving() = runBlocking {
        val name = "issue96-migration-${UUID.randomUUID()}.db"
        val helper = legacy(name, 9)
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Feed','AI',100,0,4,0)")
        for (status in listOf("READY", "ERROR", "PROCESSING_AI")) {
            old.execSQL("INSERT INTO import_tasks(id,url,title,status,statusText,assignedTags,createdAt,isNsfw,siteName,description,bodySnippet,rssFeedId) VALUES(?,?,? ,?,'Legacy','[]',1,0,'Feed','XML description','XML body','feed')",
                arrayOf(status, "https://example.com/$status", "XML title", status))
        }
        helper.close()
        val db = database(name)
        try {
            val tasks = db.importTaskDao().getAllTasks()
            assertEquals(3, tasks.size)
            assertTrue(tasks.all { !it.articlePrepared && it.bodySnippet == "XML body" && it.intendedMode == "DIRECT" })
            assertEquals(Long.MAX_VALUE, tasks.single { it.status == "ERROR" }.acquisitionRetryAt)
            assertEquals("QUEUED", db.importTaskDao().getTaskById("PROCESSING_AI")!!.status)
            assertEquals(0, db.importTaskDao().claimForDirectSave("PROCESSING_AI", "Not yet prepared"))
            assertFalse(db.rssDao().feed("feed")!!.enabled)
            assertEquals(100, db.rssDao().feed("feed")!!.retentionLimit)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun upgradeFromSeventeenRequeuesOldAiImportsWithoutLosingPreparedContent() = runBlocking {
        val name = "direct-import-migration-${UUID.randomUUID()}.db"
        val before = database(name)
        try {
            before.rssDao().putFeed(RssFeedEntity("legacy", "https://example.com/feed", "Feed", mode = "AI"))
            before.importTaskDao().insertAll(listOf("READY", "WAITING_AI", "PROCESSING_AI", "ERROR").mapIndexed { index, state ->
                ImportTaskEntity(state, "https://example.com/$index", "Title", state, "Old status",
                    createdAt = 1L, description = "Prepared description", articlePrepared = index != 1,
                    rssFeedId = "legacy", intendedMode = "AI", acquisitionRetryAt = 123L)
            })
            val sql = before.openHelper.writableDatabase
            me.bubblespark.tidilo.data.restoreLegacyAiSkippedColumns(sql::execSQL)
            rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
            sql.version = 17
        } finally { before.close() }
        val after = database(name)
        try {
            val tasks = after.importTaskDao().getAllTasks().associateBy { it.id }
            assertEquals(4, tasks.size)
            assertEquals("ERROR", tasks.getValue("ERROR").status)
            assertTrue(listOf("READY", "WAITING_AI", "PROCESSING_AI").all { tasks.getValue(it).status == "QUEUED" })
            assertEquals("Prepared description", tasks.getValue("READY").description)
            assertTrue(tasks.getValue("READY").articlePrepared)
            assertFalse(tasks.getValue("WAITING_AI").articlePrepared)
            assertTrue(tasks.values.all { it.intendedMode == "DIRECT" && it.acquisitionRetryAt == 123L })
            assertEquals("DIRECT", after.rssDao().feed("legacy")?.mode)
        } finally { after.close(); context.deleteDatabase(name) }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun database(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22)
        .addCallback(AppDatabase.DERIVED_DATA_CALLBACK).build()

    private suspend fun results(db: AppDatabase, query: String, showPrivate: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showPrivate)).load(
            PagingSource.LoadParams.Refresh(null, 30, false))
        assertTrue(page.toString(), page is PagingSource.LoadResult.Page)
        return (page as PagingSource.LoadResult.Page).data
    }

    @Test
    fun boundedPagesReloadInReverseAndOffPageDetailsRemainAvailable() = runBlocking {
        val name = "issue81-retention-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            db.bookmarkDao().insertAll((0 until 420).map {
                BookmarkEntity(it.toString().padStart(4, '0'), "Bookmark $it", "https://example.com/$it", "",
                    createdAt = (420 - it).toLong(), updatedAt = 1)
            })
            withContext(Dispatchers.Main) {
                val differ = AsyncPagingDataDiffer(
                    object : DiffUtil.ItemCallback<BookmarkEntity>() {
                        override fun areItemsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a.id == b.id
                        override fun areContentsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a == b
                    },
                    object : ListUpdateCallback {
                        override fun onInserted(position: Int, count: Int) = Unit
                        override fun onRemoved(position: Int, count: Int) = Unit
                        override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                        override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
                    }
                )
                val collection = launch {
                    Pager(PagingConfig(30, 10, false, 60, 150)) { db.bookmarkDao().timeline(true) }
                        .flow.collectLatest { differ.submitData(it) }
                }
                suspend fun awaitSnapshot(predicate: () -> Boolean) = withTimeout(5000) {
                    while (!predicate()) delay(10)
                }
                try {
                    awaitSnapshot { differ.itemCount >= 60 }
                    repeat(8) {
                        val last = differ.snapshot().items.last().id
                        differ.getItem(differ.itemCount - 1)
                        awaitSnapshot { differ.snapshot().items.last().id > last }
                        assertTrue("Retained pages must stay bounded", differ.itemCount <= 150)
                    }
                    assertTrue(differ.snapshot().items.first().id > "0000")
                    assertEquals("0000", db.bookmarkDao().observeById("0000").first()?.id)
                    db.bookmarkDao().updateNote("0000", "Off-page update", 2)
                    assertEquals("Off-page update", db.bookmarkDao().getBookmarkById("0000")?.note)
                    withTimeout(5000) { differ.loadStateFlow.first { it.refresh is androidx.paging.LoadState.NotLoading } }
                    var attempts = 0
                    while (differ.snapshot().items.firstOrNull()?.id != "0000" && attempts++ < 20) {
                        val first = differ.snapshot().items.first().id
                        differ.getItem(0)
                        awaitSnapshot { differ.snapshot().items.firstOrNull()?.id?.let { it < first } == true }
                    }
                    assertEquals("0000", differ.snapshot().items.first().id)
                    assertEquals("Off-page update", differ.snapshot().items.first().note)
                } finally { collection.cancelAndJoin() }
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun unicodeCaseKeysMatchTheAndroidRuntime() {
        for (point in 0..Character.MAX_CODE_POINT) {
            if (point in 0xD800..0xDFFF) continue
            val original = String(Character.toChars(point))
            for (variant in intArrayOf(Character.toUpperCase(point), Character.toLowerCase(point))) {
                val other = String(Character.toChars(variant))
                assertEquals("Unicode point $point versus $variant",
                    original.equals(other, ignoreCase = true), bookmarkSearchKey(original) == bookmarkSearchKey(other))
            }
        }
    }

    @Test
    fun cleanedActiveTagsMatchRenderedCardsAndCanonicalizeDuplicates() = runBlocking {
        val name = "issue81-raw-tags-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            val rows = listOf(
                BookmarkEntity("none", "No tags", "https://example.com/n", "", createdAt = 1, updatedAt = 1),
                BookmarkEntity("skipped", "Skipped", "https://example.com/s", "", createdAt = 2, updatedAt = 2),
                BookmarkEntity("raw", "Raw tags", "https://example.com/r", "", " #Article ", " #Tech ",
                    me.bubblespark.tidilo.data.EntityTagPolicy.normalizeEntities(listOf(" #Kotlin ", " ", "Kotlin")), 3, 3),
                BookmarkEntity("clean", "Clean tags", "https://example.com/c", "", "Article", "Life", createdAt = 4, updatedAt = 4),
                BookmarkEntity("blank", "Blank tags", "https://example.com/b", "", " # ", " ", createdAt = 5, updatedAt = 5)
            )
            db.bookmarkDao().insertAll(rows)
            for (query in listOf("#None", "#Kotlin", "#Article", "#Tech", "#記事", "#技術", "#Kotlin #Tech")) {
                assertEquals(query, LibraryViewModel.filterBookmarks(rows.map { it.toBookmarkItem() }, query).map { it.id }.toSet(),
                    results(db, query).map { it.id }.toSet())
            }
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), true, TagKind.ENTITY)).first()
            assertEquals(listOf(TagCount("Kotlin", 1)), counts)
            val categoryCounts = normalizeTagCounts(db.bookmarkDao().tagCounts(
                BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first(), TagKind.CATEGORY)
            assertEquals(mapOf("None" to 1, "Article" to 2), categoryCounts.associate { it.value to it.count })
            assertEquals(" #Article ", db.bookmarkDao().getBookmarkById("raw")?.category)
            assertEquals(listOf("Kotlin"), db.bookmarkDao().getBookmarkById("raw")?.entities)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun prereleaseDerivedFieldsAreRepairedOnceWithoutChangingOriginalColumns() = runBlocking {
        val name = "issue81-derived-repair-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("raw", "Kept", "https://example.com", "",
                " #Article ", " #Tech ", listOf("Kotlin", "Kotlin"), 1, 1, note = "preserved"))
            val sql = db.openHelper.writableDatabase
            val originalColumns = columns(sql, "bookmarks").filterNot { it == "domain" || it.startsWith("search") }
            val before = fingerprint(sql, "bookmarks", originalColumns)
            sql.execSQL("UPDATE bookmarks SET searchCategory=' #article ',searchSubcategory=' #tech '")
            sql.execSQL("UPDATE bookmark_entities SET value=' #Kotlin ',searchValue=' #kotlin '")
            sql.execSQL("PRAGMA application_id=0")
            db.close()
            db = database(name)
            assertEquals(listOf("raw"), results(db, "#記事 #技術 #Kotlin").map { it.id })
            assertEquals(listOf(TagCount("Kotlin", 1)), db.bookmarkDao().tagCounts(
                BookmarkQueries.tags(setOf("Tech"), true, TagKind.ENTITY)).first())
            assertEquals(before, fingerprint(db.openHelper.readableDatabase, "bookmarks", originalColumns))
            db.openHelper.readableDatabase.query("PRAGMA application_id").use {
                it.moveToFirst(); assertEquals(BOOKMARK_DERIVED_FORMAT, it.getInt(0))
            }
            // A valid marker skips reconstruction on subsequent opens.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_rebuild BEFORE DELETE ON bookmark_entities BEGIN SELECT RAISE(ABORT,'unexpected rebuild'); END")
            db.close()
            db = database(name)
            assertEquals(1, db.bookmarkDao().getCount())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun trashMoveRollsBackOnFailureAndWorksWithoutLoadingTheLibrary() = runBlocking {
        val name = "issue81-trash-${UUID.randomUUID()}.db"
        val db = database(name)
        val repository = BookmarkRepository(db, autoInitialize = false)
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("off-page", "Original", "https://example.com", "kept",
                entities = listOf("Retained"), createdAt = 1, updatedAt = 1, note = "important"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trash BEFORE INSERT ON trashed_bookmarks BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            try {
                repository.moveToTrash("off-page", 10, 100)
                fail("Failed insertion must not report success")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals("important", db.bookmarkDao().getBookmarkById("off-page")?.note)
                assertEquals(listOf("off-page"), results(db, "#Retained").map { it.id })
                assertEquals(0, db.trashDao().getCount())
            }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_trash")
            assertTrue(repository.moveToTrash("off-page", 11, 100))
            assertNull(db.bookmarkDao().getBookmarkById("off-page"))
            assertEquals("important", db.trashDao().getAllTrash().single().note)
            assertFalse(repository.moveToTrash("missing", 12, 100))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun sqliteSearchAndAggregationMatchExistingRulesAndWritesStaySynchronized() = runBlocking {
        val name = "issue81-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            val rows = listOf(
                BookmarkEntity("a", "İstanbul 100%_ \uD801\uDC00", "https://www.example.com/a", "記事 École", "Article", "Tech", listOf("Kotlin", "Kotlin", "σ"), 1, 1),
                BookmarkEntity("b", "Other", "https://example.com/b", "description", "Tool", "Life", listOf("Kot", "lin"), 1, 1),
                BookmarkEntity("c", "istanbul", "not a valid URL", "記事", "Article", "Tech", listOf("Kotlin"), 2, 2, isPrivate = true)
            )
            db.bookmarkDao().insertAll(rows)
            val queries = listOf("", "#", "Kotlin", "#Kotlin", "#記事", "#技術", "İSTANBUL", "istanbul",
                "100%_", "%", "_", "\uD801\uDC00", "\uD801\uDC28", "#σ", "#Kotlin\u3000École", "#Article #Tech", "#lin", "#Kot #lin")
            for (showPrivate in listOf(false, true)) {
                for (query in queries) {
                    val visible = rows.filter { showPrivate || !it.isPrivate }.map { it.toBookmarkItem() }
                    val expected = LibraryViewModel.filterBookmarks(visible, query).map { it.id }.toSet()
                    assertEquals("query=$query visible=$showPrivate", expected, results(db, query, showPrivate).map { it.id }.toSet())
                    assertEquals("Count must include off-page and domainless results", expected.size,
                        db.bookmarkDao().observeCount(BookmarkQueries.count(query, showPrivate)).first())
                }
            }
            assertEquals(listOf("c", "b", "a"), results(db, "").map { it.id })
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), false, TagKind.ENTITY)).first()
            assertEquals(1, counts.single { it.value == "Kotlin" }.count)
            assertEquals(listOf("example.com"), db.bookmarkDao().distinctDomains())
            db.bookmarkDao().updateFields("a", "New description", "Tool", "Life", listOf("Updated"), 3)
            assertTrue(results(db, "#Kotlin", false).isEmpty())
            assertEquals(listOf("a"), results(db, "#Updated New", false).map { it.id })
            db.bookmarkDao().deleteById("a")
            assertTrue(db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), false, TagKind.ENTITY)).first().none { it.value == "Updated" })
            db.bookmarkDao().insertOrUpdate(rows.first().copy(title = "Changed", entities = listOf("Restored")))
            assertEquals(listOf("a"), results(db, "Changed #Restored").map { it.id })
            assertTrue(results(db, "100%_").isEmpty())
            try {
                db.withTransaction {
                    db.bookmarkDao().insertOrUpdate(rows.first().copy(title = "Rolled back", entities = listOf("Lost")))
                    error("Injected write failure")
                }
                fail("Transaction should fail")
            } catch (_: IllegalStateException) {
                assertEquals(listOf("a"), results(db, "Changed #Restored").map { it.id })
                assertTrue(results(db, "#Lost").isEmpty())
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun legacy(name: String, version: Int, feedMetadataVariant: Boolean = false): SupportSQLiteOpenHelper {
        val schema = instrumentation.context.assets.open("schema-v6.sql").bufferedReader().use { it.readText() }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (statement in schema.split(';').filter { it.isNotBlank() }) {
                        var sql = statement
                        if (version < 3 && sql.contains("import_tasks")) continue
                        if (version == 1) {
                            if (sql.contains("CREATE INDEX") && sql.contains("isAiSkipped")) continue
                            sql = sql.replace(", `isAiSkipped` INTEGER NOT NULL", "")
                                .replace("`category` TEXT", "`category` TEXT NOT NULL")
                                .replace("`subcategory` TEXT", "`subcategory` TEXT NOT NULL")
                        }
                        if (version < 5 && (sql.contains("CREATE TABLE IF NOT EXISTS `bookmarks`") || sql.contains("CREATE TABLE IF NOT EXISTS `trashed_bookmarks`"))) {
                            sql = sql.replace(", `ogImageUrl` TEXT", "")
                        }
                        if (version < 4 && sql.contains("CREATE TABLE IF NOT EXISTS `import_tasks`")) sql = sql.replace(", `ogImageUrl` TEXT", "")
                        db.execSQL(sql)
                    }
                    if (version >= 7) AppDatabase.MIGRATION_6_7.migrate(db)
                    if (version >= 8) AppDatabase.MIGRATION_7_8.migrate(db)
                    if (version >= 9) AppDatabase.MIGRATION_8_9.migrate(db)
                    if (version >= 10) AppDatabase.MIGRATION_9_10.migrate(db)
                    if (version >= 11) {
                        if (feedMetadataVariant) {
                            db.execSQL("ALTER TABLE rss_feeds ADD COLUMN iconUrl TEXT")
                            db.execSQL("ALTER TABLE rss_feeds ADD COLUMN siteUrl TEXT")
                        } else AppDatabase.MIGRATION_10_11.migrate(db)
                    }
                    if (version >= 12) AppDatabase.MIGRATION_11_12.migrate(db)
                    if (version >= 13) AppDatabase.MIGRATION_12_13.migrate(db)
                    if (version >= 14) AppDatabase.MIGRATION_13_14.migrate(db)
                    if (version in 4..5) db.execSQL("CREATE TABLE og_images (id TEXT PRIMARY KEY)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected legacy upgrade")
            }).build())
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    /** Hashes all original columns with bounded memory, including nulls and column boundaries. */
    private fun fingerprint(db: SupportSQLiteDatabase, table: String, columns: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        db.query("SELECT ${columns.joinToString { "`$it`" }} FROM $table ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                for (index in columns.indices) {
                    val value = if (cursor.isNull(index)) "N;" else {
                        val text = cursor.getString(index)
                        "S${text.length}:$text;"
                    }
                    digest.update(value.toByteArray(Charsets.UTF_8))
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun domainPolicyStoreMigratesLegacyJsonIdempotentlyAndDeletesFilesAfterCommit() = runBlocking {
        val folder = java.io.File(context.cacheDir, "issue233-policy-${UUID.randomUUID()}").apply { mkdirs() }
        val legacyFile = java.io.File(folder, "domain_policies.json")
        val pendingFile = java.io.File(folder, "domain_policies.json.pending")
        val name = "issue233-legacy-policy-${UUID.randomUUID()}.db"
        val db = database(name)
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
            val runner = object : me.bubblespark.tidilo.data.TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction {
                    val result = block()
                    if (failFirst) throw java.io.IOException("Simulated commit failure")
                    result
                }
            }
            val store = me.bubblespark.tidilo.data.DomainPolicyStore(
                database = db,
                legacyJsonFile = legacyFile,
                transactionRunner = runner,
                storeScope = scope
            )
            try {
                store.setDomainPrivateAndAwait("safe.invalid", true)
                fail("Storage failure must reach the initiating caller")
            } catch (_: java.io.IOException) {
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
            context.deleteDatabase(name)
            folder.deleteRecursively()
        }
    }

    @Test
    fun failedPolicySaveRollsBackMixedBookmarkFlagsAndSuccessfulRetryCommits() = runBlocking {
        val name = "issue85-domain-rollback-${UUID.randomUUID()}.db"
        val db = database(name)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var failNext = false
        val store = me.bubblespark.tidilo.data.DomainPolicyStore(
            database = db,
            transactionRunner = object : me.bubblespark.tidilo.data.TransactionRunner {
                override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction {
                    val result = block()
                    if (failNext) throw java.io.IOException("Policy write failed")
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
                } catch (e: java.io.IOException) {
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
            context.deleteDatabase(name)
        }
    }

    @Test
    fun earliestSchemasPreserveBookmarksAndTrash() = runBlocking {
        for (version in listOf(1, 2)) {
            val name = "issue85-early-$version-${UUID.randomUUID()}.db"
            val helper = legacy(name, version)
            val old = helper.writableDatabase
            val snapshots = mutableMapOf<String, Pair<List<String>, String>>()
            for (table in listOf("bookmarks", "trashed_bookmarks")) {
                val trash = if (table == "trashed_bookmarks") ",trashedAt" else ""
                val trashValue = if (trash.isNotEmpty()) ",99" else ""
                val flag = if (version == 2) ",isAiSkipped" else ""
                val flagValue = if (version == 2) ",1" else ""
                old.execSQL("INSERT INTO $table(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw$flag$trash) VALUES('kept','Title','https://example.com','Description','Article','Tech','[\"Kotlin\"]',11,22,'Important',1$flagValue$trashValue)")
                val names = columns(old, table).filterNot { it == "isAiSkipped" }
                snapshots[table] = names to fingerprint(old, table, names)
            }
            helper.close()
            val db = database(name)
            try {
                assertEquals(1, db.bookmarkDao().getCount())
                assertEquals(1, db.trashDao().getCount())
                assertEquals(0, db.importTaskDao().getCount())
                val sql = db.openHelper.readableDatabase
                for ((table, snapshot) in snapshots) {
                    val afterColumns = snapshot.first.map { if (it == "isNsfw") "isPrivate" else it }
                    assertEquals(snapshot.second, fingerprint(sql, table, afterColumns))
                }
                assertTrue(!columns(sql, "bookmarks").contains("isAiSkipped"))
                assertTrue(!columns(sql, "trashed_bookmarks").contains("isAiSkipped"))
                assertEquals(listOf("kept"), results(db, "#Kotlin").map { it.id })
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test
    fun supportedUpgradePathsPreserveDataAndBoundedBackfillCompletesBeforeReads() = runBlocking {
        for ((version, count) in listOf(3 to 3, 4 to 3, 5 to 3, 6 to 1000, 6 to 10000, 7 to 1000, 8 to 1000, 9 to 1000)) {
            val name = "issue81-migration-${UUID.randomUUID()}.db"
            val helper = legacy(name, version)
            val old = helper.writableDatabase
            val snapshots = mutableMapOf<String, Pair<List<String>, String>>()
            old.beginTransaction()
            try {
                repeat(count) { index ->
                    val id = index.toString().padStart(8, '0')
                    val url = if (index == 0) "not a valid URL" else "https://www.example${index % 20}.com/$index"
                    old.execSQL("INSERT INTO bookmarks(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(id, "İstanbul $index", url, "description", if (index == 0) " #Article " else "Article", " #Tech ", """[" #Kotlin "," ","Kotlin"]""", index, index, "preserved", 0, 0))
                }
                old.execSQL("INSERT INTO trashed_bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) VALUES('trash','trash','https://example.com','desc','[]',1,2,'trash note',0,0,3)")
                old.execSQL("INSERT INTO import_tasks(id,url,title,status,statusText,assignedTags,createdAt,isNsfw,siteName,description,bodySnippet) VALUES('import','https://example.com','import','READY','Ready','[]',1,0,'site','desc','body')")
                if (version >= 7) rebuildBookmarkDerivedData(old)
                if (version == 8) old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision) VALUES('existing','https://example.com/feed','Existing','DIRECT',100,0,4)")
                for (table in listOf("bookmarks", "trashed_bookmarks", "import_tasks")) {
                    val names = columns(old, table).filterNot { column ->
                        (table == "bookmarks" && column == "entities") ||
                            column == "isAiSkipped" ||
                            (table == "import_tasks" && column in setOf("status", "statusText", "errorMessage", "intendedMode"))
                    }
                    snapshots[table] = names to fingerprint(old, table, names)
                }
                old.setTransactionSuccessful()
            } finally { old.endTransaction(); helper.close() }
            val db = database(name)
            try {
                val start = System.nanoTime()
                assertEquals(count, db.bookmarkDao().getCount())
                if (version == 8) {
                    val existing = db.rssDao().feed("existing")!!
                    assertTrue(existing.enabled)
                    assertEquals(100, existing.retentionLimit)
                    assertEquals(4L, existing.revision)
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                Log.i("Issue81Migration", "from=$version rows=$count durationMs=$elapsedMs")
                val task = db.importTaskDao().getTaskById("import")!!
                assertEquals("QUEUED", task.status)
                assertEquals("", task.statusText)
                assertEquals("DIRECT", task.intendedMode)
                val sql = db.openHelper.readableDatabase
                for ((table, snapshot) in snapshots) {
                    val afterColumns = snapshot.first.map { if (it == "isNsfw") "isPrivate" else it }
                    assertEquals("Original data changed in $table from v$version", snapshot.second,
                        fingerprint(sql, table, afterColumns))
                }
                sql.query("SELECT COUNT(*) FROM bookmarks WHERE note='preserved' AND searchTitle<>''").use { assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0)) }
                sql.query("SELECT COUNT(*) FROM bookmark_entities").use { it.moveToFirst(); assertEquals(count, it.getInt(0)) }
                sql.query("SELECT COUNT(*) FROM bookmarks WHERE entities='[\"Kotlin\"]'").use {
                    assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0))
                }
                sql.query("SELECT COUNT(*) FROM bookmark_entities WHERE value='Kotlin' AND searchValue='kotlin' AND skeletonKey='kotlin' AND position=0").use {
                    assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0))
                }
                assertNull(db.bookmarkDao().getBookmarkById("00000000")!!.domain)
                val row = db.bookmarkDao().getBookmarkById("00000001")!!
                assertEquals(DomainIconManager.extractDomain(row.url), row.domain)
                assertEquals(count, db.bookmarkDao().observeCount(BookmarkQueries.count("#記事 #技術 #Kotlin", true)).first())
                assertEquals(1, db.trashDao().getCount())
                assertEquals("trash note", db.trashDao().getAllTrash().single().note)
                assertEquals(1, db.importTaskDao().getCount())
                if (count == 10000) {
                    for (query in listOf("SELECT * FROM bookmarks ORDER BY createdAt DESC,id DESC LIMIT 60",
                        "SELECT * FROM bookmarks WHERE domain='example1.com' ORDER BY createdAt DESC,id DESC LIMIT 60")) {
                        val plan = sql.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("; ")
                        }
                        Log.i("Issue81QueryPlan", plan)
                        assertTrue(plan, plan.contains("INDEX index_bookmarks_"))
                    }
                }
                assertEquals(count, db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first().sumOf { it.count })
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
