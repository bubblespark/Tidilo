package me.bubblespark.tidilo.data

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.*
import org.junit.Test

class CollectionAssignmentTest {
    @Test fun selectionIsDraftUntilSaveAndRecreationRetainsIt() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val saved = SavedStateHandle()
        lateinit var vm: CollectionMembershipViewModel
        try {
            listOf("a", "b").forEach { db.collectionDao().insert(CollectionEntity(it, it, "", "folder", null, 0, 1, 1)) }
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1, isLiked = true, note = "Keep"))
            fun create(handle: SavedStateHandle) { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionMembershipViewModel(handle, CollectionRepository(db), "one") } })[CollectionMembershipViewModel::class.java] }
            host.runOnMainSync { create(saved) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            host.runOnMainSync { vm.toggle("a"); vm.toggle("b") }
            assertTrue(db.collectionDao().observeMembershipIds("one").first().isEmpty())
            val snapshot = saved.keys().associateWith { saved.get<Any?>(it) }
            host.runOnMainSync { store.clear(); create(SavedStateHandle(snapshot)) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertEquals(setOf("a", "b"), vm.state.value.selectedIds)
            host.runOnMainSync { vm.save(); vm.save() }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertEquals(listOf("a", "b"), db.collectionDao().observeMembershipIds("one").first())
            assertTrue(requireNotNull(db.bookmarkDao().getBookmarkById("one")).isLiked)
            assertEquals("Keep", requireNotNull(db.bookmarkDao().getBookmarkById("one")).note)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun deletionAndFailedSaveKeepDatabaseConsistent() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionMembershipViewModel
        try {
            db.collectionDao().insert(CollectionEntity("a", "Alpha", "", "folder", null, 0, 1, 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionMembershipViewModel(SavedStateHandle(), CollectionRepository(db), "one") } })[CollectionMembershipViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            host.runOnMainSync { vm.toggle("a") }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_assignment BEFORE INSERT ON bookmark_collection_cross_ref BEGIN SELECT RAISE(ABORT,'injected'); END")
            host.runOnMainSync { vm.save() }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals(setOf("a"), vm.state.value.selectedIds)
            assertFalse(vm.state.value.done)
            db.collectionDao().delete("a")
            withTimeout(5000) { while (vm.state.value.rows.isNotEmpty()) delay(20) }
            assertTrue(vm.state.value.selectedIds.isEmpty())
            assertNull(db.collectionDao().getById("a"))
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }
}
