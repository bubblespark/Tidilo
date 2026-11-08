package me.bubblespark.tidilo.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TrashPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    private fun bookmark(id: String = "old", url: String = "https://example.com/page") = BookmarkEntity(
        id, "Saved title", url, "Saved summary", category = "Article", subcategory = "Tech",
        entities = listOf("Kotlin"), createdAt = 11, updatedAt = 22, note = "saved memo", isLiked = true,
        browserOpenCount = 3, detailViewCount = 4, summaryPeekCount = 5, lastInteractedAt = 18,
        ogImageUrl = "https://example.com/image.png", rssFeedId = "removed-feed", rssPublishedAt = 7
    )

    private suspend fun trash(db: AppDatabase, row: BookmarkEntity = bookmark(), at: Long = 30) {
        db.bookmarkDao().insertOrUpdate(row)
        assertTrue(db.moveBookmarkToTrash(row.id, at, 100))
    }

    @Test fun restorePreservesSnapshotAndSearchIndex() = runBlocking { fixture { db ->
        db.collectionDao().insert(CollectionEntity("collection", "Saved", "", "folder", null, 0, 1, 1))
        db.bookmarkDao().insertOrUpdate(bookmark())
        db.collectionDao().insertMembership(BookmarkCollectionCrossRef("old", "collection"))
        assertTrue(db.moveBookmarkToTrash("old", 30, 100))
        assertEquals(TrashMutationResult.Restored, db.restoreTrashedBookmark("old"))
        val restored = requireNotNull(db.bookmarkDao().getBookmarkById("old"))
        assertEquals(bookmark().copy(isLiked = false).toBookmarkItem(), restored.toBookmarkItem())
        assertNull(db.trashDao().getById("old"))
        assertTrue(db.collectionDao().observeMembershipIds("old").first().isEmpty())
        db.openHelper.readableDatabase.query("SELECT value FROM bookmark_entities WHERE bookmarkId='old'").use {
            assertTrue(it.moveToFirst()); assertEquals("Kotlin", it.getString(0))
        }
    } }

    @Test fun duplicateCannotOverwriteHiddenOrRssBookmark() = runBlocking { fixture { db ->
        trash(db)
        val existing = bookmark("existing").copy(isNsfw = true, note = "new memo")
        db.bookmarkDao().insertOrUpdate(existing)
        assertEquals(TrashMutationResult.DuplicateInLibrary, db.restoreTrashedBookmark("old"))
        assertEquals("new memo", db.bookmarkDao().getBookmarkById("existing")?.note)
        assertNotNull(db.trashDao().getById("old"))
        assertTrue(db.trashDao().observeTrash(false).first().single().duplicateInLibrary)
    } }

    @Test fun sameUrlTrashRowsRestoreOnlyOne() = runBlocking { fixture { db ->
        trash(db); trash(db, bookmark("second"), 31)
        assertEquals(listOf("second", "old"), db.trashDao().observeTrash(true).first().map { it.item.id })
        val results = coroutineScope { listOf("old", "second").map { async { db.restoreTrashedBookmark(it) } }.awaitAll() }
        assertEquals(1, results.count { it == TrashMutationResult.Restored })
        assertEquals(1, results.count { it == TrashMutationResult.DuplicateInLibrary })
        assertEquals(1, db.bookmarkDao().getCount())
        assertEquals(1, db.trashDao().getCount())
    } }

    @Test fun pendingImportBlocksRestore() = runBlocking { fixture { db ->
        trash(db)
        db.importTaskDao().insertOrUpdate(ImportTaskEntity(id = "complete", url = bookmark().url, status = "COMPLETED", statusText = "", title = "", createdAt = 1))
        for (status in listOf("QUEUED", "PROCESSING", "ERROR")) {
            db.importTaskDao().insertOrUpdate(ImportTaskEntity(id = "pending", url = bookmark().url, status = status, statusText = "", title = "", createdAt = 2))
            assertEquals(TrashMutationResult.ImportPending, db.restoreTrashedBookmark("old"))
            assertNotNull(db.trashDao().getById("old"))
        }
        db.importTaskDao().deleteById("pending")
        assertEquals(TrashMutationResult.Restored, db.restoreTrashedBookmark("old"))
    } }

    @Test fun restoreDeleteRaceNeverDeletesActiveRow() = runBlocking { fixture { db ->
        trash(db)
        val results = coroutineScope { listOf(async { db.restoreTrashedBookmark("old") }, async { db.deleteTrashedBookmark("old") }).awaitAll() }
        assertTrue(results.contains(TrashMutationResult.Missing))
        assertEquals(0, db.trashDao().getCount())
        if (results.contains(TrashMutationResult.Restored)) assertNotNull(db.bookmarkDao().getBookmarkById("old"))
    } }

    @Test fun idCollisionAndMissingTargetAreSafe() = runBlocking { fixture { db ->
        trash(db)
        db.bookmarkDao().insertOrUpdate(bookmark(url = "https://example.com/different"))
        assertEquals(TrashMutationResult.IdentityConflict, db.restoreTrashedBookmark("old"))
        assertEquals(TrashMutationResult.Missing, db.restoreTrashedBookmark("absent"))
        assertEquals(TrashMutationResult.Deleted, db.deleteTrashedBookmark("old"))
        assertEquals(TrashMutationResult.Missing, db.deleteTrashedBookmark("old"))
        assertEquals("https://example.com/different", db.bookmarkDao().getBookmarkById("old")?.url)
    } }

    @Test fun failedInsertRollsBackTrashRemoval() = runBlocking { fixture { db ->
        trash(db)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON bookmarks BEGIN SELECT RAISE(ABORT, 'injected'); END")
        var failed = false
        try { db.restoreTrashedBookmark("old") } catch (_: android.database.sqlite.SQLiteException) { failed = true }
        assertTrue(failed)
        assertNotNull(db.trashDao().getById("old"))
        assertEquals(0, db.bookmarkDao().getCount())
    } }

    @Test fun liveDuplicateProjectionTracksBothTables() = runBlocking { fixture { db ->
        trash(db)
        assertFalse(db.trashDao().observeTrash(true).first().single().duplicateInLibrary)
        db.bookmarkDao().insertOrUpdate(bookmark("existing"))
        withTimeout(5_000) { assertTrue(db.trashDao().observeTrash(true).first { it.single().duplicateInLibrary }.single().duplicateInLibrary) }
        db.bookmarkDao().deleteById("existing")
        withTimeout(5_000) { assertFalse(db.trashDao().observeTrash(true).first { !it.single().duplicateInLibrary }.single().duplicateInLibrary) }
        db.deleteTrashedBookmark("old")
        assertTrue(db.trashDao().observeTrash(true).first().isEmpty())
    } }

    @Test fun capacityOrderingAndVisibilityRemainBounded() = runBlocking { fixture { db ->
        for (index in 0..100) trash(db, bookmark("item-%03d".format(index), "https://example.com/$index").copy(isNsfw = index == 100), index.toLong())
        assertEquals(100, db.trashDao().observeTrash(true).first().size)
        assertEquals("item-100", db.trashDao().observeTrash(true).first().first().item.id)
        assertEquals(99, db.trashDao().observeTrash(false).first().size)
        assertNull(db.trashDao().getById("item-000"))
    } }
}
