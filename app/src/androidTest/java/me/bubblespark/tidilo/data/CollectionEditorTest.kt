package me.bubblespark.tidilo.data

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.ui.screens.library.CollectionEditorViewModel
import org.junit.Assert.*
import org.junit.Test

class CollectionEditorTest {
    @Test fun decorationInputDuringInitialLoadCannotReplacePrefill() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val gate = java.util.concurrent.CountDownLatch(1)
        val delayed = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java)
            .setQueryExecutor { task -> executor.execute { if (delayed.get()) gate.await(); task.run() } }.build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "Alpha", "Original description", "work", null, 2, 1, 1))
            delayed.set(true)
            host.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java]
                vm.edit(vm.state.value.draft.copy(colorIndex = 7, customSymbol = "x"))
            }
            assertTrue(vm.state.value.loading)
            gate.countDown()
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertEquals("Alpha", vm.state.value.draft.title)
            assertEquals("Original description", vm.state.value.draft.description)
            assertEquals("work", vm.state.value.draft.presetIconId)
            assertEquals(2, vm.state.value.draft.colorIndex)
        } finally { gate.countDown(); host.runOnMainSync { store.clear() }; db.close(); executor.shutdownNow() }
    }
    @Test fun confirmationSurvivesRecreationAndDeleteKeepsBookmarks() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val handle = SavedStateHandle()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "Alpha", "", "folder", null, 0, 1, 1))
            db.bookmarkDao().insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity("one", "One", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
            db.collectionDao().insertMembership(me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef("one", "a"))
            fun create(saved: SavedStateHandle) { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(saved, CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            host.runOnMainSync { create(handle) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            host.runOnMainSync { vm.confirmDelete(true) }
            assertNotNull(db.collectionDao().getById("a"))
            val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }
            host.runOnMainSync { store.clear(); create(SavedStateHandle(snapshot)) }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertTrue(vm.state.value.confirmDelete)
            host.runOnMainSync { vm.delete() }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertNull(db.collectionDao().getById("a"))
            assertNotNull(db.bookmarkDao().getBookmarkById("one"))
            assertTrue(db.collectionDao().observeMembershipIds("one").first().isEmpty())
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }
    @Test fun draftsSurviveRecreationAndRepeatedCreateSavesOnlyOnce() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val repo = CollectionRepository(db)
        val store = ViewModelStore()
        val saved = SavedStateHandle()
        lateinit var vm: CollectionEditorViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(saved, repo, null) } })[CollectionEditorViewModel::class.java]
                vm.edit(CollectionDraft("a".repeat(41), "a\nb\nc", "code", "ab👍🏽", 7))
            }
            assertEquals(40, vm.state.value.draft.title.length)
            assertEquals("a\nb c", vm.state.value.draft.description)
            assertEquals("👍🏽", vm.state.value.draft.customSymbol)
            val snapshot = saved.keys().associateWith { saved.get<Any?>(it) }
            instrumentation.runOnMainSync { store.clear() }
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(snapshot), repo, null) } })[CollectionEditorViewModel::class.java]
                vm.save(); vm.save()
            }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertEquals(1, db.collectionDao().observeCollections(true).first().size)
        } finally { instrumentation.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun missingEditDoesNotRecreateAndFailureRetainsDraft() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            instrumentation.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "missing") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertTrue(vm.state.value.missing)
            instrumentation.runOnMainSync { vm.edit(CollectionDraft("Keep")); vm.save() }
            assertNull(db.collectionDao().getById("missing"))
            instrumentation.runOnMainSync { store.clear() }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_create BEFORE INSERT ON collections BEGIN SELECT RAISE(ABORT, 'injected'); END")
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), null) } })[CollectionEditorViewModel::class.java]
                vm.edit(CollectionDraft("Keep")); vm.save()
            }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals("Keep", vm.state.value.draft.title)
            assertFalse(vm.state.value.done)
        } finally { instrumentation.runOnMainSync { store.clear() }; db.close() }
    }
}
