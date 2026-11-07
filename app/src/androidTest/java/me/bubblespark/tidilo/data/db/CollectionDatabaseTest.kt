package me.bubblespark.tidilo.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import me.bubblespark.tidilo.data.removeStandardClassificationColumns
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CollectionDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun collection(id: String) = CollectionEntity(id, id, "", "folder", null, 0, 1, 1)
    private fun bookmark(id: String, nsfw: Boolean = false, feed: String? = null) = BookmarkEntity(
        id, id, "https://example.com/$id", "", createdAt = 1, updatedAt = 1,
        isNsfw = nsfw, rssFeedId = feed, isLiked = true)

    @Test fun emptyCollectionsRemainVisible() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.collectionDao().insert(collection("empty"))
            assertEquals(0, db.collectionDao().observeCollections(false).first().single().count)
        } finally { db.close() }
    }

    @Test fun countsAndPagesAgreeAcrossSourcesAndVisibility() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.collectionDao()
            dao.insert(collection("a")); dao.insert(collection("b"))
            db.bookmarkDao().insertAll(listOf(bookmark("manual"), bookmark("rss", feed = "feed"), bookmark("hidden", true), bookmark("other")))
            listOf("manual", "rss", "hidden").forEach { dao.insertMembership(BookmarkCollectionCrossRef(it, "a")) }
            dao.insertMembership(BookmarkCollectionCrossRef("manual", "b"))
            assertEquals(2, dao.observeCount("a", false).first())
            assertEquals(3, dao.observeCount("a", true).first())
            assertEquals(mapOf("a" to 2, "b" to 1), dao.observeCollections(false).first().associate { it.collection.id to it.count })
            val page = dao.bookmarks("a", false).load(PagingSource.LoadParams.Refresh(null, 30, false))
            assertEquals(listOf("rss", "manual"), (page as PagingSource.LoadResult.Page).data.map { it.id })
        } finally { db.close() }
    }

    @Test fun foreignKeysRejectOrphansAndCascadeOnDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.collectionDao()
            dao.insert(collection("a")); db.bookmarkDao().insertOrUpdate(bookmark("one"))
            var rejected = false
            try { dao.insertMembership(BookmarkCollectionCrossRef("missing", "a")) } catch (_: android.database.sqlite.SQLiteConstraintException) { rejected = true }
            assertTrue(rejected)
            dao.insertMembership(BookmarkCollectionCrossRef("one", "a"))
            dao.delete("a")
            assertTrue(dao.observeMembershipIds("one").first().isEmpty())
            assertNotNull(db.bookmarkDao().getBookmarkById("one"))
            dao.insert(collection("b")); dao.insertMembership(BookmarkCollectionCrossRef("one", "b"))
            db.bookmarkDao().deleteById("one")
            assertEquals(0, dao.observeCount("b", true).first())
        } finally { db.close() }
    }

    @Test fun migration15To16PreservesExistingTables() = runBlocking {
        val name = "collection-migration-${UUID.randomUUID()}.db"
        val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        before.bookmarkDao().insertOrUpdate(bookmark("kept"))
        val sql = before.openHelper.writableDatabase
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('collections','bookmark_collection_cross_ref')").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val counts = tables.associateWith { table -> sql.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) } }
        sql.execSQL("DROP TABLE bookmark_collection_cross_ref")
        sql.execSQL("DROP TABLE collections")
        removeStandardClassificationColumns(sql::execSQL)
        me.bubblespark.tidilo.data.restoreLegacyAiSkippedColumns(sql::execSQL)
        sql.version = 15
        before.close()
        val after = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19).build()
        try {
            assertTrue(after.bookmarkDao().getBookmarkById("kept")?.isLiked == true)
            assertTrue(after.collectionDao().observeCollections(true).first().isEmpty())
            counts.filterKeys { it != "room_master_table" }.forEach { (table, count) ->
                assertEquals(table, count, after.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) })
            }
            assertEquals(0, after.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count })
        } finally { after.close(); context.deleteDatabase(name) }
    }
}
