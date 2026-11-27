package me.bubblespark.tidilo.data.db

import android.util.Log
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.entitySkeletonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun rewindPrivateColumnsToLegacyNsfw(sql: SupportSQLiteDatabase, includeDomainPolicies: Boolean) {
        rewindPrivateColumnsToLegacyNsfw(sql::execSQL, includeDomainPolicies)
    }

    private suspend fun results(db: AppDatabase, query: String, showPrivate: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showPrivate)).load(
            PagingSource.LoadParams.Refresh(null, 30, false)
        )
        assertTrue(page.toString(), page is PagingSource.LoadResult.Page)
        return (page as PagingSource.LoadResult.Page).data
    }

    private fun legacy(name: String, version: Int, feedMetadataVariant: Boolean = false): SupportSQLiteOpenHelper {
        val schema = instrumentation.context.assets.open("schema-v6.sql").bufferedReader().use { it.readText() }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
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
                            if (version < 4 && sql.contains("CREATE TABLE IF NOT EXISTS `import_tasks`")) {
                                sql = sql.replace(", `ogImageUrl` TEXT", "")
                            }
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
                            } else {
                                AppDatabase.MIGRATION_10_11.migrate(db)
                            }
                        }
                        if (version >= 12) AppDatabase.MIGRATION_11_12.migrate(db)
                        if (version >= 13) AppDatabase.MIGRATION_12_13.migrate(db)
                        if (version >= 14) AppDatabase.MIGRATION_13_14.migrate(db)
                        if (version in 4..5) db.execSQL("CREATE TABLE og_images (id TEXT PRIMARY KEY)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected legacy upgrade")
                }).build()
        )
    }

    @Test
    fun versionTwentyOneRenamesNsfwColumnsAndIndexToPrivate() = runBlocking {
        val name = "private-rename-migration-${UUID.randomUUID()}.db"
        try {
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
            val after = openMigratedDatabase(context, name)
            try {
                val readable = after.openHelper.readableDatabase
                assertEquals(24, readable.version)
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
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionTwentyPreservesBookmarksAndCreatesDomainPolicies() = runBlocking {
        val name = "domain-policy-migration-${UUID.randomUUID()}.db"
        try {
            val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                before.bookmarkDao().insertOrUpdate(
                    BookmarkEntity("kept", "Keep", "https://kept.invalid/article", "", createdAt = 1, updatedAt = 1, isPrivate = true)
                )
                val sql = before.openHelper.writableDatabase
                rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
                sql.version = 20
            } finally { before.close() }
            val after = openMigratedDatabase(context, name)
            try {
                assertEquals(24, after.openHelper.readableDatabase.version)
                val kept = after.bookmarkDao().getBookmarkById("kept")
                assertNotNull(kept)
                assertTrue(kept!!.isPrivate)
                assertEquals(listOf("domain", "isPrivate", "displayName"), columns(after.openHelper.readableDatabase, "domain_policies"))
                after.domainPolicyDao().upsert(DomainPolicyEntity("kept.invalid", isPrivate = true, displayName = "Kept"))
                val stored = after.domainPolicyDao().getByDomain("kept.invalid")
                assertNotNull(stored)
                assertTrue(stored!!.isPrivate)
                assertEquals("Kept", stored.displayName)
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionNineteenPreservesImageDeletionJobsAndAllowsMultipleDomains() = runBlocking {
        val name = "image-cleanup-migration-${UUID.randomUUID()}.db"
        try {
            val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                before.bookmarkDao().insertOrUpdate(
                    BookmarkEntity("kept", "Keep", "https://first.invalid/article", "", createdAt = 1, updatedAt = 1)
                )
                val sql = before.openHelper.writableDatabase
                sql.execSQL("DROP TABLE rss_image_cleanup")
                sql.execSQL("CREATE TABLE rss_image_cleanup (imageUrl TEXT NOT NULL PRIMARY KEY, articleUrl TEXT NOT NULL)")
                sql.execSQL("INSERT INTO rss_image_cleanup VALUES ('https://images.invalid/shared.webp', 'https://first.invalid/article')")
                rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
                sql.version = 19
            } finally { before.close() }
            val after = openMigratedDatabase(context, name)
            try {
                assertEquals(24, after.openHelper.readableDatabase.version)
                assertNotNull(after.bookmarkDao().getBookmarkById("kept"))
                assertEquals(1, after.rssDao().cleanupItems().size)
                after.rssDao().enqueueCleanup(RssImageCleanupEntity("https://images.invalid/shared.webp", "https://second.invalid/article"))
                assertEquals(2, after.rssDao().cleanupItems().size)
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun versionSixteenQueuedImportSurvivesClassificationMigration() = runBlocking {
        val name = "classification-migration-${UUID.randomUUID()}.db"
        val queued = ImportTaskEntity(
            id = "queued", url = "https://example.com/page", title = "Queued", status = "READY",
            statusText = "Ready", assignedTags = listOf("Existing"), createdAt = 1, articlePrepared = true
        )
        try {
            val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                before.importTaskDao().insertOrUpdate(queued)
                val sql = before.openHelper.writableDatabase
                removeStandardClassificationColumns(sql::execSQL)
                restoreLegacyAiSkippedColumns(sql::execSQL)
                rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
                sql.version = 16
            } finally { before.close() }
            val after = openMigratedDatabase(context, name)
            try {
                assertEquals(24, after.openHelper.readableDatabase.version)
                assertEquals(queued.copy(status = "QUEUED", statusText = ""), after.importTaskDao().getTaskById(queued.id))
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun prereleaseEngagementVersionFourteenPreservesSignalsAndBuildsEntityIndex() = runBlocking {
        val name = "issue165-prerelease-${UUID.randomUUID()}.db"
        try {
            val helper = legacy(name, 13)
            try {
                val old = helper.writableDatabase
                for (table in listOf("bookmarks", "trashed_bookmarks")) {
                    for (column in listOf("isLiked", "browserOpenCount", "detailViewCount", "summaryPeekCount")) {
                        old.execSQL("ALTER TABLE $table ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
                    }
                    old.execSQL("ALTER TABLE $table ADD COLUMN lastInteractedAt INTEGER")
                }
                old.execSQL("INSERT INTO bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,isLiked,browserOpenCount,detailViewCount,summaryPeekCount,lastInteractedAt) VALUES('kept','Title','https://example.com/a','Summary','[\"Tag\"]',1,2,'Note',0,0,1,2,3,4,1234)")
                old.version = 14
            } finally { helper.close() }
            val db = openMigratedDatabase(context, name)
            try {
                val item = db.bookmarkDao().getBookmarkById("kept")!!
                assertTrue(item.isLiked)
                assertEquals(1234L, item.lastInteractedAt)
                val events = db.engagementDao().eventsFor("kept")
                assertEquals(
                    mapOf(
                        EngagementType.BROWSER_OPEN.code to 2, EngagementType.DETAIL.code to 3,
                        EngagementType.PEEK.code to 4, EngagementType.LIKE.code to 1
                    ),
                    events.groupingBy { it.type }.eachCount()
                )
                assertTrue(events.all { it.at == 1234L && it.surface == EngagementSurface.MIGRATED.code })
                assertTrue(columns(db.openHelper.readableDatabase, "bookmark_entities").contains("skeletonKey"))
                assertEquals(listOf("Tag"), item.entities)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun legacyVersionsPreserveRowsAndDefaultEngagement() = runBlocking {
        for (version in listOf(13, 14)) {
            val name = "issue165-${UUID.randomUUID()}.db"
            try {
                val helper = legacy(name, version)
                try {
                    val old = helper.writableDatabase
                    old.execSQL("INSERT INTO bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped) VALUES('active','Active','https://example.com/a','Summary','[]',1,2,'Note',0,0)")
                    old.execSQL("INSERT INTO trashed_bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) VALUES('trash','Trash','https://example.com/t','Summary','[]',1,2,'Trash note',0,0,3)")
                    old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Feed','DIRECT',100,0,4,0)")
                } finally { helper.close() }
                val db = openMigratedDatabase(context, name)
                try {
                    for (table in listOf("bookmarks", "trashed_bookmarks")) {
                        db.openHelper.readableDatabase.query("SELECT isLiked,lastInteractedAt,note FROM $table").use {
                            assertTrue(it.moveToFirst())
                            assertEquals(0, it.getInt(0))
                            assertTrue(it.isNull(1))
                            assertEquals(if (table == "bookmarks") "Note" else "Trash note", it.getString(2))
                        }
                    }
                    assertEquals("Feed", db.rssDao().feed("feed")!!.title)
                    assertTrue("Default engagement seeds no events", db.engagementDao().eventsFor("active").isEmpty())
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test
    fun migration22To23SeedsEventsAndDropsCounters() = runBlocking {
        val name = "engagement-v23-${UUID.randomUUID()}.db"
        try {
            val before = openMigratedDatabase(context, name)
            try {
                before.bookmarkDao().insertAll(
                    listOf(
                        BookmarkEntity("a", "A", "https://example.com/a", "", createdAt = 50, updatedAt = 50, isLiked = true, lastInteractedAt = 500),
                        BookmarkEntity("b", "B", "https://example.com/b", "", createdAt = 100, updatedAt = 100)
                    )
                )
                before.trashDao().insert(
                    TrashedBookmarkEntity(
                        "t", "T", "https://example.com/t", "", createdAt = 1, updatedAt = 1,
                        note = "", isPrivate = false, lastInteractedAt = 900, trashedAt = 950
                    )
                )
            } finally { before.close() }
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, 0).use { sql ->
                restoreEngagementCounters(sql::execSQL)
                sql.execSQL("UPDATE bookmarks SET summaryPeekCount = 2, detailViewCount = 7 WHERE id = 'a'")
                sql.execSQL("UPDATE bookmarks SET detailViewCount = 1 WHERE id = 'b'")
                sql.execSQL("UPDATE trashed_bookmarks SET browserOpenCount = 3 WHERE id = 't'")
                sql.version = 22
            }
            val after = openMigratedDatabase(context, name)
            try {
                val events = after.engagementDao()
                assertEquals(
                    mapOf(EngagementType.PEEK.code to 2, EngagementType.DETAIL.code to 5, EngagementType.LIKE.code to 1),
                    events.eventsFor("a").groupingBy { it.type }.eachCount()
                )
                assertTrue(events.eventsFor("a").all { it.at == 500L && it.surface == EngagementSurface.MIGRATED.code })
                assertEquals(listOf(EngagementType.DETAIL.code to 100L), events.eventsFor("b").map { it.type to it.at })
                assertEquals(List(3) { EngagementType.BROWSER_OPEN.code to 900L }, events.eventsFor("t").map { it.type to it.at })
                val sql = after.openHelper.readableDatabase
                for (table in listOf("bookmarks", "trashed_bookmarks")) {
                    assertTrue(columns(sql, table).none { it in setOf("browserOpenCount", "detailViewCount", "summaryPeekCount") })
                }
                assertTrue(after.bookmarkDao().getBookmarkById("a")!!.isLiked)
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun legacyEntitySpellingsUpgradeOnceWithIndexedOccurrenceParity() = runBlocking {
        for (version in listOf(6, 13)) {
            val name = "issue164-v$version-${UUID.randomUUID()}.db"
            try {
                val helper = legacy(name, version)
                val before: String
                val keptColumns: List<String>
                val trashBefore: String
                val trashColumns: List<String>
                try {
                    val old = helper.writableDatabase
                    val converter = Converters()
                    fun seed(id: String, createdAt: Long, values: List<String>, hidden: Boolean = false) {
                        old.execSQL(
                            "INSERT INTO bookmarks(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,ogImageUrl) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(
                                id, "Title $id", "https://example.com/$id", "Description", "Article", "Tech",
                                converter.fromStringList(values), createdAt, 99L, "Saved note", if (hidden) 1 else 0, 0, "https://example.com/image.png"
                            )
                        )
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
                    keptColumns = columns(old, "bookmarks").filterNot { it == "entities" || it == "domain" || it.startsWith("search") || it == "isAiSkipped" }
                    before = fingerprint(old, "bookmarks", keptColumns)
                    trashColumns = columns(old, "trashed_bookmarks").filterNot { it == "isAiSkipped" }
                    trashBefore = fingerprint(old, "trashed_bookmarks", trashColumns)
                } finally { helper.close() }
                var db = openMigratedDatabase(context, name)
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(24, sql.version)
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
                                assertEquals(entitySkeletonKey(values[index]), cursor.getString(3))
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
                        db = openMigratedDatabase(context, name)
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
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test
    fun bothVersionElevenSchemasUpgradeWithoutLosingSavedData() = runBlocking {
        for (feedMetadataVariant in listOf(false, true)) {
            val name = "issue108-v11-${UUID.randomUUID()}.db"
            try {
                val helper = legacy(name, 11, feedMetadataVariant)
                try {
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
                } finally { helper.close() }
                val db = openMigratedDatabase(context, name)
                try {
                    assertEquals(24, db.openHelper.readableDatabase.version)
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
                    db.importNotificationDao().insert(
                        ImportNotificationEntity(
                            type = "INFO", domain = "example.com",
                            url = "https://example.com/new", message = "New notification"
                        )
                    )
                    assertEquals(if (feedMetadataVariant) 1 else 2, db.importNotificationDao().observeUnreadCount().first())
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test
    fun legacyFeedsGainNullableMetadataWithoutLosingSubscriptionOrArticles() = runBlocking {
        val name = "issue99-migration-${UUID.randomUUID()}.db"
        try {
            val helper = legacy(name, 10)
            try {
                helper.writableDatabase.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Kept feed','DIRECT',100,1,4,0)")
            } finally { helper.close() }
            val db = openMigratedDatabase(context, name)
            try {
                db.openHelper.readableDatabase.query("SELECT iconUrl,siteUrl,title,deleted,enabled FROM rss_feeds WHERE id='feed'").use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
                    assertEquals("Kept feed", it.getString(2)); assertEquals(1, it.getInt(3)); assertEquals(0, it.getInt(4))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun schemaNinePreservesXmlTasksAndRequiresHtmlBeforeSaving() = runBlocking {
        val name = "issue96-migration-${UUID.randomUUID()}.db"
        try {
            val helper = legacy(name, 9)
            try {
                val old = helper.writableDatabase
                old.execSQL("INSERT INTO rss_feeds(id,url,title,mode,retentionLimit,deleted,revision,enabled) VALUES('feed','https://example.com/feed','Feed','AI',100,0,4,0)")
                for (status in listOf("READY", "ERROR", "PROCESSING_AI")) {
                    old.execSQL(
                        "INSERT INTO import_tasks(id,url,title,status,statusText,assignedTags,createdAt,isNsfw,siteName,description,bodySnippet,rssFeedId) VALUES(?,?,? ,?,'Legacy','[]',1,0,'Feed','XML description','XML body','feed')",
                        arrayOf(status, "https://example.com/$status", "XML title", status)
                    )
                }
            } finally { helper.close() }
            val db = openMigratedDatabase(context, name)
            try {
                val tasks = db.importTaskDao().getAllTasks()
                assertEquals(3, tasks.size)
                assertTrue(tasks.all { !it.articlePrepared && it.bodySnippet == "XML body" && it.intendedMode == "DIRECT" })
                assertEquals(Long.MAX_VALUE, tasks.single { it.status == "ERROR" }.acquisitionRetryAt)
                assertEquals("QUEUED", db.importTaskDao().getTaskById("PROCESSING_AI")!!.status)
                assertEquals(0, db.importTaskDao().claimForDirectSave("PROCESSING_AI", "Not yet prepared"))
                assertFalse(db.rssDao().feed("feed")!!.enabled)
                assertEquals(100, db.rssDao().feed("feed")!!.retentionLimit)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun upgradeFromSeventeenRequeuesOldAiImportsWithoutLosingPreparedContent() = runBlocking {
        val name = "direct-import-migration-${UUID.randomUUID()}.db"
        try {
            val before = openMigratedDatabase(context, name)
            try {
                before.rssDao().putFeed(RssFeedEntity("legacy", "https://example.com/feed", "Feed", mode = "AI"))
                before.importTaskDao().insertAll(
                    listOf("READY", "WAITING_AI", "PROCESSING_AI", "ERROR").mapIndexed { index, state ->
                        ImportTaskEntity(
                            state, "https://example.com/$index", "Title", state, "Old status",
                            createdAt = 1L, description = "Prepared description", articlePrepared = index != 1,
                            rssFeedId = "legacy", intendedMode = "AI", acquisitionRetryAt = 123L
                        )
                    }
                )
                val sql = before.openHelper.writableDatabase
                restoreLegacyAiSkippedColumns(sql::execSQL)
                rewindPrivateColumnsToLegacyNsfw(sql, includeDomainPolicies = false)
                sql.version = 17
            } finally { before.close() }
            val after = openMigratedDatabase(context, name)
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
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun earliestSchemasPreserveBookmarksAndTrash() = runBlocking {
        for (version in listOf(1, 2)) {
            val name = "issue85-early-$version-${UUID.randomUUID()}.db"
            try {
                val helper = legacy(name, version)
                val snapshots = mutableMapOf<String, Pair<List<String>, String>>()
                try {
                    val old = helper.writableDatabase
                    for (table in listOf("bookmarks", "trashed_bookmarks")) {
                        val trash = if (table == "trashed_bookmarks") ",trashedAt" else ""
                        val trashValue = if (trash.isNotEmpty()) ",99" else ""
                        val flag = if (version == 2) ",isAiSkipped" else ""
                        val flagValue = if (version == 2) ",1" else ""
                        old.execSQL("INSERT INTO $table(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw$flag$trash) VALUES('kept','Title','https://example.com','Description','Article','Tech','[\"Kotlin\"]',11,22,'Important',1$flagValue$trashValue)")
                        val names = columns(old, table).filterNot { it == "isAiSkipped" }
                        snapshots[table] = names to fingerprint(old, table, names)
                    }
                } finally { helper.close() }
                val db = openMigratedDatabase(context, name)
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
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test
    fun supportedUpgradePathsPreserveDataAndBoundedBackfillCompletesBeforeReads() = runBlocking {
        for ((version, count) in listOf(3 to 3, 4 to 3, 5 to 3, 6 to 100, 6 to 600, 7 to 100, 8 to 100, 9 to 100)) {
            val name = "issue81-migration-${UUID.randomUUID()}.db"
            try {
                val helper = legacy(name, version)
                val snapshots = mutableMapOf<String, Pair<List<String>, String>>()
                try {
                    val old = helper.writableDatabase
                    old.beginTransaction()
                    try {
                        repeat(count) { index ->
                            val id = index.toString().padStart(8, '0')
                            val url = if (index == 0) "not a valid URL" else "https://www.example${index % 20}.com/$index"
                            old.execSQL(
                                "INSERT INTO bookmarks(id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                                arrayOf<Any>(
                                    id, "İstanbul $index", url, "description",
                                    if (index == 0) " #Article " else "Article", " #Tech ",
                                    """[" #Kotlin "," ","Kotlin"]""", index, index, "preserved", 0, 0
                                )
                            )
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
                    } finally { old.endTransaction() }
                } finally { helper.close() }
                val db = openMigratedDatabase(context, name)
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
                        assertEquals(
                            "Original data changed in $table from v$version", snapshot.second,
                            fingerprint(sql, table, afterColumns)
                        )
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
                    if (count == 600) {
                        for (query in listOf(
                            "SELECT * FROM bookmarks ORDER BY createdAt DESC,id DESC LIMIT 60",
                            "SELECT * FROM bookmarks WHERE domain='example1.com' ORDER BY createdAt DESC,id DESC LIMIT 60"
                        )) {
                            val plan = sql.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                                buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("; ")
                            }
                            Log.i("Issue81QueryPlan", plan)
                            assertTrue(plan, plan.contains("INDEX index_bookmarks_"))
                        }
                    }
                    assertEquals(count, db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first().sumOf { it.count })
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
