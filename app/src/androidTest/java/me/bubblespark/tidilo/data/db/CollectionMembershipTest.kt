package me.bubblespark.tidilo.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.CollectionRepository
import org.junit.Assert.*
import org.junit.Test

class CollectionMembershipTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun fixture(block: suspend (AppDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.collectionDao().insert(CollectionEntity("a", "Alpha", "", "folder", null, 0, 1, 1))
            db.collectionDao().insert(CollectionEntity("b", "Beta", "", "star", null, 1, 1, 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1, isLiked = true, browserOpenCount = 7))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("one", "a"))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("one", "b"))
            block(db)
        } finally { db.close() }
    }

    @Test fun sameIdWritesRetainMemberships() = runBlocking { fixture { db ->
        val dao = db.bookmarkDao()
        val row = requireNotNull(dao.getBookmarkById("one"))
        dao.insertOrUpdate(row.copy(description = "Changed"))
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
        dao.insertAll(listOf(row.copy(note = "Batch")))
        dao.updateFields("one", "Edited", null, null, listOf("Tag"), 2)
        dao.replaceEntitySpellings(mapOf(me.bubblespark.tidilo.data.entitySkeletonKey("Tag") to "TAG"), 3)
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
    } }

    @Test fun urlReplacementTransfersMembershipsToSurvivingId() = runBlocking { fixture { db ->
        val row = requireNotNull(db.bookmarkDao().getBookmarkById("one"))
        db.bookmarkDao().insertReplacingUrl(row.copy(id = "two"))
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("two").first())
        assertNull(db.bookmarkDao().getBookmarkById("one"))
    } }

    @Test fun trashClearsMembershipsAndLikedAtomically() = runBlocking { fixture { db ->
        assertTrue(BookmarkRepository(db, autoInitialize = false).moveToTrash("one", 9, 100))
        assertTrue(db.collectionDao().observeMembershipIds("one").first().isEmpty())
        val trash = db.trashDao().getAllTrash().single()
        assertFalse(trash.isLiked)
        assertEquals(7, trash.browserOpenCount)
        assertEquals(0, db.collectionDao().observeCount("a", true).first())
    } }

    @Test fun membershipSetIsAtomicAndIdempotent() = runBlocking { fixture { db ->
        val repo = CollectionRepository(db)
        assertTrue(repo.setMemberships("one", setOf("b")))
        assertTrue(repo.setMemberships("one", setOf("b")))
        assertEquals(listOf("b"), db.collectionDao().observeMembershipIds("one").first())
        assertTrue(repo.addMembership("one", "b"))
        assertTrue(repo.removeMembership("one", "a"))
        assertTrue(repo.setMemberships("one", emptySet()))
        assertTrue(db.collectionDao().observeMembershipIds("one").first().isEmpty())
    } }

    @Test fun missingTargetDoesNotPartiallySave() = runBlocking { fixture { db ->
        val repo = CollectionRepository(db)
        assertFalse(repo.setMemberships("one", setOf("b", "missing")))
        assertFalse(repo.setMemberships("missing", setOf("a")))
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
    } }

    @Test fun concurrentToggleIsSerialized() = runBlocking { fixture { db ->
        val repo = CollectionRepository(db)
        coroutineScope { (1..20).map { async { repo.toggleMembership("one", "a") } }.awaitAll() }
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
    } }

    @Test fun failedWriteRollsBack() = runBlocking { fixture { db ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_membership BEFORE INSERT ON bookmark_collection_cross_ref BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        var rejected = false
        try { CollectionRepository(db).setMemberships("one", setOf("b")) } catch (_: android.database.sqlite.SQLiteException) { rejected = true }
        assertTrue(rejected)
        assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
    } }
}
