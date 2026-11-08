package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.data.maintenance.LibraryMaintenanceRepository
import org.junit.Assert.*
import org.junit.Test

class LibraryMaintenanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L

    @Test fun expiryProtectsFavoritesCollectionsAndManualBookmarks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.bookmarkDao()
            fun row(id: String) = BookmarkEntity(id, id, "https://fixture.invalid/$id", "",
                createdAt = day, updatedAt = day, rssFeedId = "deleted-feed")
            dao.insertAll(listOf(row("unread"), row("recent").copy(lastInteractedAt = 35 * day),
                row("old-read").copy(lastInteractedAt = 10 * day), row("liked").copy(isLiked = true),
                row("collected"), row("manual").copy(rssFeedId = null)))
            db.collectionDao().insert(CollectionEntity("collection", "Keep", "", "folder", null, 0, day, day))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("collected", "collection"))
            LibraryMaintenanceRepository(context, db).expireRssBookmarks(40 * day)
            assertNull(dao.getBookmarkById("unread"))
            assertNull(dao.getBookmarkById("old-read"))
            for (id in listOf("recent", "liked", "collected", "manual")) assertNotNull(id, dao.getBookmarkById(id))
            db.collectionDao().delete("collection")
            dao.toggleLike("liked", 40 * day) // Removing a favorite does not renew interest.
            LibraryMaintenanceRepository(context, db).expireRssBookmarks(40 * day)
            assertNull(dao.getBookmarkById("liked"))
            assertNull(dao.getBookmarkById("collected"))
        } finally { db.close() }
    }

    @Test fun engagementRenewalAndDeletionOutboxAreDurable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(BookmarkEntity("rss", "RSS", "https://fixture.invalid/rss", "",
                createdAt = day, updatedAt = day, rssFeedId = "feed", ogImageUrl = "/image.webp"))
            assertNull(dao.getBookmarkById("rss")!!.lastInteractedAt)
            dao.recordDetailView("rss", 10 * day)
            dao.recordBrowserOpen("rss", 39 * day)
            val maintenance = LibraryMaintenanceRepository(context, db)
            maintenance.expireRssBookmarks(40 * day)
            assertNotNull(dao.getBookmarkById("rss"))
            maintenance.expireRssBookmarks(69 * day)
            assertNull(dao.getBookmarkById("rss"))
            assertEquals("/image.webp", db.rssDao().cleanupItems().single().imageUrl)
            maintenance.expireRssBookmarks(69 * day)
            assertEquals(1, db.rssDao().cleanupItems().size)
        } finally { db.close() }
    }

    @Test fun failedDeletionRollsBackEveryBookmarkAndOutboxWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (id in listOf("a", "z")) db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, id,
                "https://fixture.invalid/$id", "", createdAt = day, updatedAt = day,
                rssFeedId = "feed", ogImageUrl = "/$id.webp"))
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_retention BEFORE DELETE ON bookmarks
                WHEN OLD.id = 'z' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
            try {
                LibraryMaintenanceRepository(context, db).expireRssBookmarks(40 * day)
                fail("Deletion must fail")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(2, db.bookmarkDao().getCount())
            assertTrue(db.rssDao().cleanupItems().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_retention")
            LibraryMaintenanceRepository(context, db).expireRssBookmarks(40 * day)
            assertEquals(0, db.bookmarkDao().getCount())
            assertEquals(2, db.rssDao().cleanupItems().size)
        } finally { db.close() }
    }
}
