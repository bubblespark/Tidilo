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
    @Test fun retryAfterInitialLoadFailurePrefillsStoredCollection() = verifyLoadRetry(false)

    @Test fun retryAfterLoadFailurePreservesRestoredDraft() = verifyLoadRetry(true)

    private fun verifyLoadRetry(restored: Boolean) = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val saved = if (restored) SavedStateHandle(mapOf("loaded" to true, "title" to "Unsaved draft",
            "description" to "Draft description", "icon" to "code", "color" to 7)) else SavedStateHandle()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "Stored", "Stored description", "work", null, 2, 1, 1))
            // Make the real Room query fail, then restore the table before retrying.
            db.openHelper.writableDatabase.execSQL("ALTER TABLE collections RENAME TO unavailable_collections")
            host.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(saved, CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java]
            }
            withTimeout(5000) { while (vm.state.value.error != "load") delay(20) }
            host.runOnMainSync { vm.edit(CollectionDraft("Blocked")); vm.delete() }
            assertFalse(vm.state.value.pending)
            assertFalse(vm.state.value.done)
            db.openHelper.writableDatabase.execSQL("ALTER TABLE unavailable_collections RENAME TO collections")
            host.runOnMainSync { vm.retryLoad(); vm.retryLoad() }
            withTimeout(5000) { while (vm.state.value.error != null || vm.state.value.loading) delay(20) }
            assertFalse(vm.state.value.missing)
            assertEquals(if (restored) "Unsaved draft" else "Stored", vm.state.value.draft.title)
            assertEquals(if (restored) "Draft description" else "Stored description", vm.state.value.draft.description)
            assertEquals(if (restored) "code" else "work", vm.state.value.draft.presetIconId)
            assertEquals(if (restored) 7 else 2, vm.state.value.draft.colorIndex)
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "Saved after retry")) }
            withTimeout(5000) { while (db.collectionDao().getById("a")?.title != "Saved after retry") delay(20) }
            assertFalse("Autosave keeps the editor open", vm.state.value.done)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

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
                vm.create(); vm.create()
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
            instrumentation.runOnMainSync { vm.edit(CollectionDraft("Keep")) }
            delay(800)
            assertNull(db.collectionDao().getById("missing"))
            instrumentation.runOnMainSync { store.clear() }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_create BEFORE INSERT ON collections BEGIN SELECT RAISE(ABORT, 'injected'); END")
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), null) } })[CollectionEditorViewModel::class.java]
                vm.edit(CollectionDraft("Keep")); vm.create()
            }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals("Keep", vm.state.value.draft.title)
            assertFalse(vm.state.value.done)
        } finally { instrumentation.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun existingCollectionAutosavesValidDraftsAndKeepsBlankTitleAsDraft() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "Stored", "", "folder", null, 0, 1, 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "Renamed", colorIndex = 3)) }
            withTimeout(5000) { while (db.collectionDao().getById("a")?.title != "Renamed") delay(20) }
            assertEquals(3, db.collectionDao().getById("a")?.colorIndex)
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "")) }
            delay(1000)
            assertEquals("A blank title is never written", "Renamed", db.collectionDao().getById("a")?.title)
            assertEquals("The blank draft stays recoverable", "", vm.state.value.draft.title)
            assertEquals(me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Invalid, vm.autosaveStatus.value)
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "Pending at close")) }
            // Closing before the pause must still persist the last valid draft.
            host.runOnMainSync { store.clear() }
            withTimeout(5000) { while (db.collectionDao().getById("a")?.title != "Pending at close") delay(20) }
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun recreatedEditorResubmitsRestoredDraft() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        fun create(saved: SavedStateHandle) = host.runOnMainSync {
            vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(saved, CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java]
        }
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "Stored", "", "folder", null, 0, 1, 1))
            // A blank title restored after recreation keeps its recoverable-draft error.
            create(SavedStateHandle(mapOf("loaded" to true, "title" to "", "description" to "", "icon" to "folder", "color" to 0)))
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            withTimeout(5000) { while (vm.autosaveStatus.value != me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Invalid) delay(20) }
            assertEquals("Stored", db.collectionDao().getById("a")?.title)
            host.runOnMainSync { store.clear() }
            // A valid unsaved draft restored after recreation is written without further input.
            create(SavedStateHandle(mapOf("loaded" to true, "title" to "Restored", "description" to "", "icon" to "folder", "color" to 0)))
            withTimeout(5000) { while (db.collectionDao().getById("a")?.title != "Restored") delay(20) }
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun laterEmissionsNeverResubmitTheInitialDraft() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "A", "", "folder", null, 0, 1, 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            // Another editor renames the collection while this one is open without edits.
            CollectionRepository(db).update("a", CollectionDraft("B"), 2)
            delay(1500)
            assertEquals("An untouched editor must not write its initial draft back", "B", db.collectionDao().getById("a")?.title)
            assertEquals(me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Idle, vm.autosaveStatus.value)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }
}
