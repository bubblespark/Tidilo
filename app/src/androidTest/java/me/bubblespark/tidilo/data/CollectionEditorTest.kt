package me.bubblespark.tidilo.data

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import kotlinx.coroutines.launch
import androidx.room.withTransaction
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
        val saved = if (restored) SavedStateHandle(mapOf("loaded" to true, "edited" to arrayListOf("TITLE", "DESCRIPTION", "ICON", "COLOR"), "title" to "Unsaved draft",
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
            create(SavedStateHandle(mapOf("loaded" to true, "edited" to arrayListOf("TITLE"), "title" to "", "description" to "", "icon" to "folder", "color" to 0)))
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            withTimeout(5000) { while (vm.autosaveStatus.value != me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Invalid) delay(20) }
            assertEquals("Stored", db.collectionDao().getById("a")?.title)
            host.runOnMainSync { store.clear() }
            // A valid unsaved draft restored after recreation is written without further input.
            create(SavedStateHandle(mapOf("loaded" to true, "edited" to arrayListOf("TITLE"), "title" to "Restored", "description" to "", "icon" to "folder", "color" to 0)))
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

    @Test fun editingOneFieldKeepsAnotherEditorsChangeToUntouchedFields() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "A", "Old description", "folder", null, 0, 1, 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            CollectionRepository(db).update("a", CollectionDraft("B", "Old description"), 2)
            withTimeout(5000) { while (vm.state.value.draft.title != "B") delay(20) }
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(description = "New description")) }
            withTimeout(5000) { while (db.collectionDao().getById("a")?.description != "New description") delay(20) }
            assertEquals("The untouched title keeps the other editor's change", "B", db.collectionDao().getById("a")?.title)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun pendingEditPicksUpAnotherEditorsChangeBeforeItIsWritten() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "A", "Old description", "folder", null, 0, 1, 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            // The description edit is still waiting for its typing pause when another editor renames the collection.
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(description = "New description")) }
            CollectionRepository(db).update("a", CollectionDraft("B", "Old description"), 2)
            withTimeout(5000) { while (db.collectionDao().getById("a")?.description != "New description") delay(20) }
            assertEquals("The pending write must carry the adopted title", "B", db.collectionDao().getById("a")?.title)
            assertEquals("B", vm.state.value.draft.title)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun autosaveWritesOnlyEditedFieldsOverAConcurrentCommit() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "A", "Old description", "folder", null, 0, 1, 1))
            val repository = CollectionRepository(db)
            // Another editor commits a new title before this editor's description-only write runs.
            repository.update("a", CollectionDraft("B", "Old description"), 2)
            val stored = repository.updateFields("a", CollectionDraft("A", "New description"),
                setOf(me.bubblespark.tidilo.data.CollectionField.DESCRIPTION), 3)
            assertEquals(CollectionDraft("B", "New description"), stored)
            val row = requireNotNull(db.collectionDao().getById("a"))
            assertEquals("The unedited title keeps the concurrent change", "B", row.title)
            assertEquals("New description", row.description)
            assertNull(repository.updateFields("missing", CollectionDraft("X"), setOf(me.bubblespark.tidilo.data.CollectionField.TITLE), 4))
        } finally { db.close() }
    }

    @Test fun revertDuringAnInFlightWriteIsPersistedAndShown() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.collectionDao().insert(me.bubblespark.tidilo.data.db.CollectionEntity("a", "A", "", "folder", null, 0, 1, 1))
            host.runOnMainSync { vm = ViewModelProvider(store, viewModelFactory { initializer { CollectionEditorViewModel(SavedStateHandle(), CollectionRepository(db), "a") } })[CollectionEditorViewModel::class.java] }
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            // Hold the database so the next write stays in flight.
            val blocker = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { db.withTransaction { gate.await() } }
            delay(200)
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "B")) }
            withTimeout(5000) { while (vm.autosaveStatus.value != me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Saving) delay(20) }
            host.runOnMainSync { vm.edit(vm.state.value.draft.copy(title = "A")) }
            gate.complete(Unit)
            blocker.join()
            withTimeout(5000) { while (vm.autosaveStatus.value != me.bubblespark.tidilo.ui.screens.library.AutosaveStatus.Saved) delay(20) }
            delay(300)
            assertEquals("The revert is stored", "A", db.collectionDao().getById("a")?.title)
            assertEquals("The revert is shown", "A", vm.state.value.draft.title)
        } finally { gate.complete(Unit); host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun createWithBookmarkAssignmentRollsBackOnFailureRetriesWithoutDuplicatesAndRejectsMissingBookmark() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val repo = CollectionRepository(db)
        val store = ViewModelStore()
        val saved = SavedStateHandle()
        lateinit var vm: CollectionEditorViewModel
        try {
            db.bookmarkDao().insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity("bm-1", "One", "https://example.com/1", "", createdAt = 1, updatedAt = 1))
            // Fail on cross-ref insert to verify rollback + retry with the same createId.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_cross_ref BEFORE INSERT ON bookmark_collection_cross_ref BEGIN SELECT RAISE(ABORT, 'injected'); END"
            )
            host.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory {
                    initializer { CollectionEditorViewModel(saved, repo, null, assignToBookmarkId = "bm-1") }
                })[CollectionEditorViewModel::class.java]
                vm.edit(CollectionDraft("Assigned Collection", "From Detail", "star", null, 3))
                vm.create()
            }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals("save", vm.state.value.error)
            assertFalse(vm.state.value.done)
            assertFalse(vm.state.value.missing)
            assertEquals("Assigned Collection", vm.state.value.draft.title)
            assertNull("Rollback must leave no orphan collection", db.collectionDao().getById(vm.createdId))
            assertTrue(db.collectionDao().observeMembershipIds("bm-1").first().isEmpty())

            // Drop trigger and retry create(): must reuse the same createdId and assign atomically.
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cross_ref")
            val expectedId = vm.createdId
            host.runOnMainSync { vm.create(); vm.create() }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertEquals(expectedId, vm.createdId)
            assertEquals(1, db.collectionDao().observeCollections(true).first().size)
            assertEquals("Assigned Collection", db.collectionDao().getById(expectedId)?.title)
            assertEquals(listOf(expectedId), db.collectionDao().observeMembershipIds("bm-1").first())

            // Missing target bookmark sets error = "missing_bookmark" and creates no orphan collection.
            host.runOnMainSync { store.clear() }
            val missingSaved = SavedStateHandle()
            host.runOnMainSync {
                vm = ViewModelProvider(store, viewModelFactory {
                    initializer { CollectionEditorViewModel(missingSaved, repo, null, assignToBookmarkId = "missing-bm") }
                })[CollectionEditorViewModel::class.java]
                vm.edit(CollectionDraft("Should Not Exist"))
                vm.create()
            }
            withTimeout(5000) { while (vm.state.value.error == null) delay(20) }
            assertEquals("missing_bookmark", vm.state.value.error)
            assertFalse(vm.state.value.done)
            assertFalse(vm.state.value.missing)
            assertNull(db.collectionDao().getById(vm.createdId))
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun recreatedAssignedEditorRecoversCommittedUncommittedRemovedMembershipAndMissingBookmark() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(host.targetContext, AppDatabase::class.java).build()
        val repo = CollectionRepository(db)
        val store = ViewModelStore()
        lateinit var vm: CollectionEditorViewModel
        fun open(saved: SavedStateHandle, bookmarkId: String = "bm-rec") = host.runOnMainSync {
            vm = ViewModelProvider(store, viewModelFactory {
                initializer { CollectionEditorViewModel(saved, repo, null, assignToBookmarkId = bookmarkId) }
            })[CollectionEditorViewModel::class.java]
        }
        try {
            db.bookmarkDao().insertOrUpdate(me.bubblespark.tidilo.data.db.BookmarkEntity("bm-rec", "Rec", "https://example.com/rec", "", createdAt = 1, updatedAt = 1))

            // 1. Committed create-and-assign while submitting=true: recreation marks done=true without duplicating.
            val committedId = java.util.UUID.randomUUID().toString()
            assertTrue(repo.createAndAssign(committedId, CollectionDraft("Committed"), "bm-rec", 10))
            val committedHandle = SavedStateHandle(mapOf("createId" to committedId, "submitting" to true, "title" to "Committed"))
            open(committedHandle)
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertTrue(vm.state.value.done)
            assertNull(vm.state.value.error)
            host.runOnMainSync { vm.create() }
            assertEquals(1, db.collectionDao().observeCollections(true).first().size)
            assertEquals(listOf(committedId), db.collectionDao().observeMembershipIds("bm-rec").first())
            host.runOnMainSync { store.clear() }

            // 2. Uncommitted submitting=true: recreation clears submitting cleanly (not done, no error), retry succeeds with same createId.
            val pendingId = java.util.UUID.randomUUID().toString()
            val pendingHandle = SavedStateHandle(mapOf("createId" to pendingId, "submitting" to true, "title" to "Retried Pending"))
            open(pendingHandle)
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertFalse(vm.state.value.done)
            assertNull(vm.state.value.error)
            host.runOnMainSync { vm.create() }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertEquals(pendingId, vm.createdId)
            assertTrue(pendingId in db.collectionDao().observeMembershipIds("bm-rec").first())
            host.runOnMainSync { store.clear() }

            // 3. Collection exists, bookmark exists, but membership was removed before recovery: retry reconciles cleanly without PK conflict.
            val detachedId = java.util.UUID.randomUUID().toString()
            assertTrue(repo.createAndAssign(detachedId, CollectionDraft("Detached"), "bm-rec", 20))
            db.collectionDao().removeMembership("bm-rec", detachedId)
            val detachedHandle = SavedStateHandle(mapOf("createId" to detachedId, "submitting" to true, "title" to "Detached"))
            open(detachedHandle)
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertFalse(vm.state.value.done)
            assertNull(vm.state.value.error)
            host.runOnMainSync { vm.create() }
            withTimeout(5000) { while (!vm.state.value.done) delay(20) }
            assertEquals(detachedId, vm.createdId)
            assertTrue(detachedId in db.collectionDao().observeMembershipIds("bm-rec").first())
            host.runOnMainSync { store.clear() }

            // 4. Collection exists, target bookmark was deleted before recovery: surfaces missing_bookmark immediately.
            val orphanCreatorHandle = SavedStateHandle(mapOf("createId" to detachedId, "submitting" to true, "title" to "Detached"))
            db.bookmarkDao().deleteById("bm-rec")
            open(orphanCreatorHandle, bookmarkId = "bm-rec")
            withTimeout(5000) { while (vm.state.value.loading) delay(20) }
            assertEquals("missing_bookmark", vm.state.value.error)
            assertFalse(vm.state.value.done)
        } finally { host.runOnMainSync { store.clear() }; db.close() }
    }
}
