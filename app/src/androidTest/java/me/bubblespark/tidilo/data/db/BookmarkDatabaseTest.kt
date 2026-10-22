package me.bubblespark.tidilo.data.db

import android.content.Context
import android.util.Log
import androidx.paging.PagingSource
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun database(name: String): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7).build()

    private suspend fun results(db: AppDatabase, query: String, showNsfw: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showNsfw)).load(
            PagingSource.LoadParams.Refresh(null, 30, false))
        assertTrue(page.toString(), page is PagingSource.LoadResult.Page)
        return (page as PagingSource.LoadResult.Page).data
    }

    @Test
    fun sqliteSearchAndAggregationMatchExistingRulesAndWritesStaySynchronized() = runBlocking {
        val name = "issue81-${UUID.randomUUID()}.db"
        val db = database(name)
        try {
            val rows = listOf(
                BookmarkEntity("a", "İstanbul 100%_", "https://www.example.com/a", "記事 École", "Article", "Tech", listOf("Kotlin", "Kotlin", "σ"), 1, 1),
                BookmarkEntity("b", "Other", "https://example.com/b", "description", "Tool", "Life", listOf("Kot", "lin"), 1, 1),
                BookmarkEntity("c", "istanbul", "not a valid URL", "記事", "Article", "Tech", listOf("Kotlin"), 2, 2, isNsfw = true)
            )
            db.bookmarkDao().insertAll(rows)
            val queries = listOf("", "#", "Kotlin", "#Kotlin", "#記事", "#技術", "İSTANBUL", "istanbul",
                "100%_", "%", "_", "#σ", "#Kotlin\u3000École", "#Article #Tech", "#lin", "#Kot #lin")
            for (showNsfw in listOf(false, true)) {
                for (query in queries) {
                    val visible = rows.filter { showNsfw || !it.isNsfw }.map { it.toBookmarkItem() }
                    val expected = LibraryViewModel.filterBookmarks(visible, query).map { it.id }.toSet()
                    assertEquals("query=$query visible=$showNsfw", expected, results(db, query, showNsfw).map { it.id }.toSet())
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
                        if (version < 5 && (sql.contains("CREATE TABLE IF NOT EXISTS `bookmarks`") || sql.contains("CREATE TABLE IF NOT EXISTS `trashed_bookmarks`"))) {
                            sql = sql.replace(", `ogImageUrl` TEXT", "")
                        }
                        if (version < 4 && sql.contains("CREATE TABLE IF NOT EXISTS `import_tasks`")) sql = sql.replace(", `ogImageUrl` TEXT", "")
                        db.execSQL(sql)
                    }
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
    fun supportedUpgradePathsPreserveDataAndBoundedBackfillCompletesBeforeReads() = runBlocking {
        for ((version, count) in listOf(3 to 3, 4 to 3, 5 to 3, 6 to 1000, 6 to 10000)) {
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
                        arrayOf<Any>(id, "İstanbul $index", url, "description", "Article", "Tech", "[\"Kotlin\",\"Kotlin\"]", index, index, "preserved", 0, 0))
                }
                old.execSQL("INSERT INTO trashed_bookmarks(id,title,url,description,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) VALUES('trash','trash','https://example.com','desc','[]',1,2,'trash note',0,0,3)")
                old.execSQL("INSERT INTO import_tasks(id,url,title,status,statusText,assignedTags,createdAt,isNsfw,siteName,description,bodySnippet) VALUES('import','https://example.com','import','READY','Ready','[]',1,0,'site','desc','body')")
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
                assertEquals(1, db.trashDao().getCount())
                assertEquals("trash note", db.trashDao().getAllTrash().single().note)
                assertEquals(1, db.importTaskDao().getCount())
                assertEquals(count, db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first().sumOf { it.count })
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
