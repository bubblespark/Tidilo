package me.bubblespark.tidilo.data

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.CollectionMembershipViewModel
import me.bubblespark.tidilo.ui.screens.library.asMembershipStore
import org.junit.Assert.*
import org.junit.Test

class CollectionAssignmentTest {
    private fun model(store: ViewModelStore, db: AppDatabase) =
        ViewModelProvider(store, viewModelFactory { initializer {
            CollectionMembershipViewModel(CollectionRepository(db).asMembershipStore(), "one")
        } })[CollectionMembershipViewModel::class.java]

    @Test fun eachToggleIsSavedImmediatelyAndRecreationReadsStoredMembership() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionMembershipViewModel
        try {
            listOf("a", "b").forEach { db.collectionDao().insert(CollectionEntity(it, it, "", "folder", null, 0, 1, 1)) }
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1, isLiked = true, note = "Keep"))
            host.runOnMainSync { vm = model(store, db) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            host.runOnMainSync { vm.toggle("a"); vm.toggle("b") }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first().size != 2) delay(20) }
            host.runOnMainSync { store.clear(); vm = model(store, db) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertEquals(setOf("a", "b"), vm.state.value.selectedIds)
            host.runOnMainSync { vm.toggle("a") }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first() != listOf("b")) delay(20) }
            assertTrue(requireNotNull(db.bookmarkDao().getBookmarkById("one")).isLiked)
            assertEquals("Keep", requireNotNull(db.bookmarkDao().getBookmarkById("one")).note)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun failedToggleRevertsAndRetryReappliesIt() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionMembershipViewModel
        try {
            db.collectionDao().insert(CollectionEntity("a", "Alpha", "", "folder", null, 0, 1, 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            host.runOnMainSync { vm = model(store, db) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_assignment BEFORE INSERT ON bookmark_collection_cross_ref BEGIN SELECT RAISE(ABORT,'injected'); END")
            host.runOnMainSync { vm.toggle("a") }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals("save", vm.state.value.error)
            assertTrue("A failed change reverts to the stored membership", vm.state.value.selectedIds.isEmpty())
            assertTrue(db.collectionDao().observeMembershipIds("one").first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_assignment")
            host.runOnMainSync { vm.retry() }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first() != listOf("a")) delay(20) }
            withTimeout(5000) { while (vm.state.value.error != null) delay(20) }
            db.collectionDao().delete("a")
            withTimeout(5000) { while (vm.state.value.rows.isNotEmpty()) delay(20) }
            assertTrue(vm.state.value.selectedIds.isEmpty())
            assertNull(db.collectionDao().getById("a"))
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun everyUnresolvedFailureStaysRetryableUntilRetried() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionMembershipViewModel
        try {
            listOf("a", "b", "c").forEach { db.collectionDao().insert(CollectionEntity(it, it, "", "folder", null, 0, 1, 1)) }
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            host.runOnMainSync { vm = model(store, db) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_ab BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.collectionId IN ('a','b') BEGIN SELECT RAISE(ABORT,'injected'); END")
            host.runOnMainSync { vm.toggle("a"); vm.toggle("b") }
            withTimeout(5000) { while (vm.state.value.pendingIds.isNotEmpty()) delay(20) }
            assertEquals("save", vm.state.value.error)
            // A successful change to another row neither hides the error nor drops the failures.
            host.runOnMainSync { vm.toggle("c") }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first() != listOf("c")) delay(20) }
            withTimeout(5000) { while (vm.state.value.pendingIds.isNotEmpty()) delay(20) }
            assertEquals("save", vm.state.value.error)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_ab")
            host.runOnMainSync { vm.retry() }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first().toSet() != setOf("a", "b", "c")) delay(20) }
            withTimeout(5000) { while (vm.state.value.error != null) delay(20) }
            assertEquals(setOf("a", "b", "c"), vm.state.value.selectedIds)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun retryRestartsAFailedObservationAlongWithFailedWrites() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionMembershipViewModel
        try {
            listOf("a", "b").forEach { db.collectionDao().insert(CollectionEntity(it, it, "", "folder", null, 0, 1, 1)) }
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            host.runOnMainSync { vm = model(store, db) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_a BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.collectionId = 'a' BEGIN SELECT RAISE(ABORT,'injected'); END")
            host.runOnMainSync { vm.toggle("a") }
            withTimeout(5000) { while (vm.state.value.error != "save") delay(20) }
            // Break the observation: the next re-query of the renamed table fails.
            sql.execSQL("ALTER TABLE collections RENAME TO unavailable_collections")
            sql.execSQL("INSERT INTO bookmark_collection_cross_ref (bookmarkId, collectionId) VALUES ('one', 'b')")
            db.invalidationTracker.refreshVersionsAsync()
            withTimeout(5000) { while (vm.state.value.error != "load") delay(20) }
            sql.execSQL("ALTER TABLE unavailable_collections RENAME TO collections")
            sql.execSQL("DROP TRIGGER fail_a")
            host.runOnMainSync { vm.retry() }
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds("one").first().toSet() != setOf("a", "b")) delay(20) }
            // The restarted observation keeps delivering later changes.
            db.collectionDao().removeMembership("one", "b")
            withTimeout(5000) { while (vm.state.value.selectedIds != setOf("a")) delay(20) }
            assertNull(vm.state.value.error)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun createAndAssignIsAtomicBindsByIdAndRollsBackOnFailure() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val repo = CollectionRepository(db)
        val existingDupId = java.util.UUID.randomUUID().toString()
        val existingMemberId = java.util.UUID.randomUUID().toString()
        val createdId = java.util.UUID.randomUUID().toString()
        val failedCrossRefId = java.util.UUID.randomUUID().toString()
        val missingBookmarkCollectionId = java.util.UUID.randomUUID().toString()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            // Duplicate collection title already exists and is not assigned; another collection is already assigned.
            db.collectionDao().insert(CollectionEntity(existingDupId, "Read Later", "", "folder", null, 0, 1, 1))
            db.collectionDao().insert(CollectionEntity(existingMemberId, "Pinned", "", "star", null, 1, 2, 2))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("one", existingMemberId))

            // 1. Failure during cross-ref insertion rolls back the collection insert (no orphan collection).
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_assign_cross_ref BEFORE INSERT ON bookmark_collection_cross_ref " +
                    "WHEN NEW.collectionId = '$failedCrossRefId' BEGIN SELECT RAISE(ABORT,'injected cross-ref failure'); END"
            )
            val failedAttempt = runCatching {
                repo.createAndAssign(failedCrossRefId, CollectionDraft("Orphan candidate"), "one", 10)
            }
            assertTrue(failedAttempt.isFailure)
            assertNull("Failed assignment must roll back collection creation", db.collectionDao().getById(failedCrossRefId))
            assertEquals(listOf(existingMemberId), db.collectionDao().observeMembershipIds("one").first())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_assign_cross_ref")

            // 2. Retry with the same ID succeeds without duplicate creation.
            assertTrue(repo.createAndAssign(failedCrossRefId, CollectionDraft("Orphan candidate"), "one", 11))
            assertNotNull(db.collectionDao().getById(failedCrossRefId))
            assertEquals(setOf(existingMemberId, failedCrossRefId), db.collectionDao().observeMembershipIds("one").first().toSet())

            // 3. Duplicate collection title binds strictly by UUID without checking the pre-existing duplicate title.
            assertTrue(repo.createAndAssign(createdId, CollectionDraft("Read Later"), "one", 12))
            val memberships = db.collectionDao().observeMembershipIds("one").first().toSet()
            assertEquals(setOf(existingMemberId, failedCrossRefId, createdId), memberships)
            assertFalse("Existing collection with identical title must remain unchecked", existingDupId in memberships)

            // 4. Missing target bookmark returns false without creating an orphan collection.
            assertFalse(repo.createAndAssign(missingBookmarkCollectionId, CollectionDraft("No bookmark"), "deleted-bookmark", 13))
            assertNull("Missing target bookmark must not create an orphan collection", db.collectionDao().getById(missingBookmarkCollectionId))

            // 5. Re-running createAndAssign with an already committed collection ID whose membership was removed
            // reconciles the collection row (keeping original createdAt) and restores membership idempotently.
            db.collectionDao().removeMembership("one", createdId)
            assertFalse(createdId in db.collectionDao().observeMembershipIds("one").first())
            assertTrue(repo.createAndAssign(createdId, CollectionDraft("Read Later Updated"), "one", 20))
            val reconciled = requireNotNull(db.collectionDao().getById(createdId))
            assertEquals("Read Later Updated", reconciled.title)
            assertEquals(12L, reconciled.createdAt)
            assertTrue(createdId in db.collectionDao().observeMembershipIds("one").first())
        } finally { db.close() }
    }
}
