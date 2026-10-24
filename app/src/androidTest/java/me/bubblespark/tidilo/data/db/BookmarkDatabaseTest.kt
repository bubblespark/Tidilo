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
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.security.MessageDigest

class BookmarkDatabaseTest {
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

    @Test fun schemaNinePreservesXmlTasksAndRequiresHtmlBeforeInference() = runBlocking {
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
            assertTrue(tasks.all { !it.articlePrepared && it.bodySnippet == "XML body" && it.intendedMode == "AI" })
            assertEquals(Long.MAX_VALUE, tasks.single { it.status == "ERROR" }.acquisitionRetryAt)
            assertEquals("PROCESSING_AI", db.importTaskDao().getTaskById("PROCESSING_AI")!!.status)
            assertEquals(0, db.importTaskDao().claimForAi("PROCESSING_AI", "Not yet prepared"))
            assertFalse(db.rssDao().feed("feed")!!.enabled)
            assertEquals(100, db.rssDao().feed("feed")!!.retentionLimit)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun database(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11)
        .addCallback(AppDatabase.DERIVED_DATA_CALLBACK).build()

    private suspend fun results(db: AppDatabase, query: String, showNsfw: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showNsfw)).load(
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
    fun rawLegacyTagsMatchRenderedCardsWithoutChangingOriginalValues() = runBlocking {
        val name = "issue81-raw-tags-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            val rows = listOf(
                BookmarkEntity("none", "No tags", "https://example.com/n", "", createdAt = 1, updatedAt = 1),
                BookmarkEntity("skipped", "Skipped", "https://example.com/s", "", createdAt = 2, updatedAt = 2, isAiSkipped = true),
                BookmarkEntity("raw", "Raw tags", "https://example.com/r", "", " #Article ", " #Tech ",
                    listOf(" #Kotlin ", " ", "Kotlin"), 3, 3),
                BookmarkEntity("clean", "Clean tags", "https://example.com/c", "", "Article", "Life", createdAt = 4, updatedAt = 4),
                BookmarkEntity("blank", "Blank tags", "https://example.com/b", "", " # ", " ", createdAt = 5, updatedAt = 5)
            )
            db.bookmarkDao().insertAll(rows)
            for (query in listOf("#None", "#Kotlin", "#Article", "#Tech", "#記事", "#技術", "#Kotlin #Tech")) {
                assertEquals(query, LibraryViewModel.filterBookmarks(rows.map { it.toBookmarkItem() }, query).map { it.id }.toSet(),
                    results(db, query).map { it.id }.toSet())
            }
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), true, TagKind.ENTITY)).first()
            assertEquals(listOf(TagCount("Kotlin", 2)), counts)
            val categoryCounts = normalizeTagCounts(db.bookmarkDao().tagCounts(
                BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first(), TagKind.CATEGORY)
            assertEquals(mapOf("None" to 2, "Article" to 2), categoryCounts.associate { it.value to it.count })
            assertEquals(" #Article ", db.bookmarkDao().getBookmarkById("raw")?.category)
            assertEquals(listOf(" #Kotlin ", " ", "Kotlin"), db.bookmarkDao().getBookmarkById("raw")?.entities)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun prereleaseDerivedFieldsAreRepairedOnceWithoutChangingOriginalColumns() = runBlocking {
        val name = "issue81-derived-repair-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("raw", "Kept", "https://example.com", "",
                " #Article ", " #Tech ", listOf(" #Kotlin ", " ", "Kotlin"), 1, 1, note = "preserved"))
            val sql = db.openHelper.writableDatabase
            val originalColumns = columns(sql, "bookmarks").filterNot { it == "domain" || it.startsWith("search") }
            val before = fingerprint(sql, "bookmarks", originalColumns)
            sql.execSQL("UPDATE bookmarks SET searchCategory=' #article ',searchSubcategory=' #tech '")
            sql.execSQL("UPDATE bookmark_entities SET value=' #Kotlin ',searchValue=' #kotlin '")
            sql.execSQL("PRAGMA application_id=0")
            db.close()
            db = database(name)
            assertEquals(listOf("raw"), results(db, "#記事 #技術 #Kotlin").map { it.id })
            assertEquals(listOf(TagCount("Kotlin", 2)), db.bookmarkDao().tagCounts(
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
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("off-page", "Original", "https://example.com", "kept",
                entities = listOf("Retained"), createdAt = 1, updatedAt = 1, note = "important"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trash BEFORE INSERT ON trashed_bookmarks BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            try {
                db.moveBookmarkToTrash("off-page", 10, 100)
                fail("Failed insertion must not report success")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals("important", db.bookmarkDao().getBookmarkById("off-page")?.note)
                assertEquals(listOf("off-page"), results(db, "#Retained").map { it.id })
                assertEquals(0, db.trashDao().getCount())
            }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_trash")
            assertTrue(db.moveBookmarkToTrash("off-page", 11, 100))
            assertNull(db.bookmarkDao().getBookmarkById("off-page"))
            assertEquals("important", db.trashDao().getAllTrash().single().note)
            assertFalse(db.moveBookmarkToTrash("missing", 12, 100))
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
                BookmarkEntity("c", "istanbul", "not a valid URL", "記事", "Article", "Tech", listOf("Kotlin"), 2, 2, isNsfw = true)
            )
            db.bookmarkDao().insertAll(rows)
            val queries = listOf("", "#", "Kotlin", "#Kotlin", "#記事", "#技術", "İSTANBUL", "istanbul",
                "100%_", "%", "_", "\uD801\uDC00", "\uD801\uDC28", "#σ", "#Kotlin\u3000École", "#Article #Tech", "#lin", "#Kot #lin")
            for (showNsfw in listOf(false, true)) {
                for (query in queries) {
                    val visible = rows.filter { showNsfw || !it.isNsfw }.map { it.toBookmarkItem() }
                    val expected = LibraryViewModel.filterBookmarks(visible, query).map { it.id }.toSet()
                    assertEquals("query=$query visible=$showNsfw", expected, results(db, query, showNsfw).map { it.id }.toSet())
                    assertEquals("Count must include off-page and domainless results", expected.size,
                        db.bookmarkDao().observeCount(BookmarkQueries.count(query, showNsfw)).first())
                }
            }
            assertEquals(listOf("c", "b", "a"), results(db, "").map { it.id })
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), false, TagKind.ENTITY)).first()
            assertEquals(2, counts.single { it.value == "Kotlin" }.count)
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

    private fun legacy(name: String, version: Int): SupportSQLiteOpenHelper {
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
    fun domainPolicyPersistenceCompletesBeforeReturnAndReportsWriteFailure() = runBlocking {
        val folder = java.io.File(context.cacheDir, "issue85-policy-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): java.io.File = folder
        }
        try {
            me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfwAndPersist("issue85.invalid", true, isolated)
            val file = java.io.File(folder, "domain_policies.json")
            val policies = org.json.JSONArray(file.readText())
            val saved = (0 until policies.length()).map { policies.getJSONObject(it) }.single { it.getString("domain") == "issue85.invalid" }
            assertTrue(saved.getBoolean("isNsfw"))
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            try {
                me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfwAndPersist("issue85.invalid", false, isolated)
                fail("Storage failure must reach the initiating caller")
            } catch (_: java.io.IOException) {
                assertTrue(me.bubblespark.tidilo.data.DomainPolicyManager.isNsfwDomain("issue85.invalid"))
                assertTrue(me.bubblespark.tidilo.data.DomainPolicyManager.policies.value.single { it.domain == "issue85.invalid" }.isNsfw)
            }
            try {
                me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfwAndPersist("new-issue85.invalid", true, isolated)
                fail("New policy must not be published after failure")
            } catch (_: java.io.IOException) {
                assertFalse(me.bubblespark.tidilo.data.DomainPolicyManager.isNsfwDomain("new-issue85.invalid"))
                assertTrue(me.bubblespark.tidilo.data.DomainPolicyManager.policies.value.none { it.domain == "new-issue85.invalid" })
            }
            assertTrue(file.delete())
            me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfwAndPersist("issue85.invalid", false, isolated)
            assertFalse(me.bubblespark.tidilo.data.DomainPolicyManager.isNsfwDomain("issue85.invalid"))
            assertTrue(file.isFile)
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun failedPolicySaveRollsBackMixedBookmarkFlagsAndSuccessfulRetryCommits() = runBlocking {
        val name = "issue85-domain-rollback-${UUID.randomUUID()}.db"
        val db = database(name)
        val rows = listOf(
            BookmarkEntity("safe", "Safe", "https://rollback.invalid/a", "", createdAt = 1, updatedAt = 1, isNsfw = false),
            BookmarkEntity("adult", "Adult", "https://rollback.invalid/b", "", createdAt = 2, updatedAt = 2, isNsfw = true),
            BookmarkEntity("other", "Other", "https://other.invalid/a", "", createdAt = 3, updatedAt = 3, isNsfw = false)
        )
        try {
            db.bookmarkDao().insertAll(rows)
            for (desired in listOf(true, false)) {
                val failure = java.io.IOException("Policy write failed")
                try {
                    db.updateDomainNsfwWithPolicy("rollback.invalid", desired) {
                        assertEquals(desired, db.bookmarkDao().getBookmarkById("safe")!!.isNsfw)
                        assertEquals(desired, db.bookmarkDao().getBookmarkById("adult")!!.isNsfw)
                        throw failure
                    }
                    fail("Policy save failure must be propagated")
                } catch (e: java.io.IOException) { assertSame(failure, e) }
                for (row in rows) assertEquals(row.withSearchFields(), db.bookmarkDao().getBookmarkById(row.id))
            }
            var persisted = false
            db.updateDomainNsfwWithPolicy("rollback.invalid", true) { persisted = true }
            assertTrue(persisted)
            assertTrue(db.bookmarkDao().getBookmarkById("safe")!!.isNsfw)
            assertTrue(db.bookmarkDao().getBookmarkById("adult")!!.isNsfw)
            assertFalse(db.bookmarkDao().getBookmarkById("other")!!.isNsfw)
        } finally { db.close(); context.deleteDatabase(name) }
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
                val names = columns(old, table)
                snapshots[table] = names to fingerprint(old, table, names)
            }
            helper.close()
            val db = database(name)
            try {
                assertEquals(1, db.bookmarkDao().getCount())
                assertEquals(1, db.trashDao().getCount())
                assertEquals(0, db.importTaskDao().getCount())
                val sql = db.openHelper.readableDatabase
                for ((table, snapshot) in snapshots) assertEquals(snapshot.second, fingerprint(sql, table, snapshot.first))
                assertEquals(version == 2, db.bookmarkDao().getBookmarkById("kept")!!.isAiSkipped)
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
                    val names = columns(old, table)
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
                val sql = db.openHelper.readableDatabase
                for ((table, snapshot) in snapshots) {
                    assertEquals("Original data changed in $table from v$version", snapshot.second,
                        fingerprint(sql, table, snapshot.first))
                }
                sql.query("SELECT COUNT(*) FROM bookmarks WHERE note='preserved' AND searchTitle<>''").use { assertTrue(it.moveToFirst()); assertEquals(count, it.getInt(0)) }
                sql.query("SELECT COUNT(*) FROM bookmark_entities").use { it.moveToFirst(); assertEquals(count * 2, it.getInt(0)) }
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
