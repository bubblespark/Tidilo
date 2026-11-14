package me.bubblespark.tidilo.ui.screens.trash

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.bubblespark.tidilo.data.TrashMutationResult
import me.bubblespark.tidilo.data.db.*
import org.junit.Assert.*
import org.junit.Test

class TrashViewModelTest {
    private fun row(duplicate: Boolean = false) = TrashBookmarkRow(TrashedBookmarkEntity(
        "old", "Saved", "https://example.com", "", createdAt = 1, updatedAt = 1,
        note = "memo", isPrivate = false, trashedAt = 2
    ), duplicate)

    private class Store(initial: List<TrashBookmarkRow>) : TrashStore {
        val rows = MutableStateFlow(initial)
        var restoreCalls = 0
        var deletedId: String? = null
        var deferred: CompletableDeferred<Unit>? = null
        var failWrite = false
        var failRead = false
        var retryAction: suspend () -> Unit = {}
        val readFailure = MutableStateFlow<Throwable?>(null)
        override fun observe(showPrivate: Boolean): Flow<List<TrashBookmarkRow>> = flow {
            if (failRead) throw java.io.IOException("read")
            combine(rows, readFailure) { current, error ->
                if (error != null) throw error
                current.filter { row -> showPrivate || !row.item.isPrivate }
            }.collect { emit(it) }
        }
        override suspend fun restore(id: String): TrashMutationResult {
            restoreCalls++
            deferred?.await()
            if (failWrite) throw java.io.IOException("write")
            rows.value = rows.value.filterNot { it.item.id == id }
            return TrashMutationResult.Restored
        }
        override suspend fun delete(id: String): TrashMutationResult {
            deletedId = id
            rows.value = rows.value.filterNot { it.item.id == id }
            return TrashMutationResult.Deleted
        }
        override suspend fun retry() = retryAction()
    }

    private fun fixture(block: (TrashViewModel, Store, SavedStateHandle, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(listOf(row()))
        val saved = SavedStateHandle()
        try { block(TrashViewModel(store, saved, MutableStateFlow(true), scope), store, saved, scope) }
        finally { scope.cancel() }
    }

    @Test fun requestAndCancelNeverWrite() = fixture { model, store, _, _ ->
        model.requestAction("old", TrashAction.Restore)
        assertEquals(TrashConfirmation("old", TrashAction.Restore), model.uiState.value.confirmation)
        model.dismissConfirmation()
        assertNull(model.uiState.value.confirmation)
        assertEquals(0, store.restoreCalls)
        assertEquals(1, model.uiState.value.rows.size)
    }

    @Test fun confirmUsesSavedIdOnce() = fixture { model, store, _, _ ->
        store.deferred = CompletableDeferred()
        model.requestAction("old", TrashAction.Restore)
        model.confirmAction(); model.confirmAction(); model.dismissConfirmation()
        assertTrue(model.uiState.value.busy)
        assertEquals(1, store.restoreCalls)
        store.deferred?.complete(Unit)
        assertFalse(model.uiState.value.busy)
        assertNull(model.uiState.value.confirmation)
        assertEquals(TrashMutationResult.Restored, model.uiState.value.actionResult)
        assertTrue(model.uiState.value.rows.isEmpty())
    }

    @Test fun duplicateRestoreCannotBeRequested() = fixture { model, store, _, _ ->
        store.rows.value = listOf(row(true))
        model.requestAction("old", TrashAction.Restore)
        assertNull(model.uiState.value.confirmation)
        model.requestAction("old", TrashAction.Delete); model.confirmAction()
        assertEquals("old", store.deletedId)
        assertTrue(model.uiState.value.rows.isEmpty())
    }

    @Test fun failurePreservesRetryableConfirmation() = fixture { model, store, _, _ ->
        store.failWrite = true
        model.requestAction("old", TrashAction.Restore); model.confirmAction()
        assertNotNull(model.uiState.value.error)
        assertEquals("old", model.uiState.value.confirmation?.id)
        assertFalse(model.uiState.value.busy)
        store.failWrite = false; model.confirmAction()
        assertNull(model.uiState.value.error)
        assertEquals(TrashMutationResult.Restored, model.uiState.value.actionResult)
    }

    @Test fun recreationRetainsTargetWithoutAutoSubmitting() = fixture { model, store, saved, scope ->
        model.requestAction("old", TrashAction.Delete)
        val restored = TrashViewModel(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), MutableStateFlow(true), scope)
        assertEquals(TrashConfirmation("old", TrashAction.Delete), restored.uiState.value.confirmation)
        assertNull(store.deletedId)
        restored.confirmAction()
        assertEquals("old", store.deletedId)
    }

    @Test fun targetDisappearsWhileDialogOpen() = fixture { model, store, _, _ ->
        model.requestAction("old", TrashAction.Restore)
        store.rows.value = emptyList(); model.confirmAction()
        assertEquals(0, store.restoreCalls)
        assertNull(model.uiState.value.confirmation)
        assertEquals(TrashMutationResult.Missing, model.uiState.value.actionResult)
    }

    @Test fun duplicateAppearingBeforeConfirmCannotWrite() = fixture { model, store, _, _ ->
        model.requestAction("old", TrashAction.Restore)
        store.rows.value = listOf(row(true)); model.confirmAction()
        assertEquals(0, store.restoreCalls)
        assertEquals(TrashMutationResult.DuplicateInLibrary, model.uiState.value.actionResult)
    }

    @Test fun visibilityAndRetryRestartObservation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(listOf(row(), row().let { it.copy(item = it.item.copy(id = "hidden", isPrivate = true)) }))
        val visibility = MutableStateFlow(false)
        store.failRead = true
        try {
            val model = TrashViewModel(store, SavedStateHandle(), visibility, scope)
            assertNotNull(model.uiState.value.error)
            store.failRead = false; model.retry()
            assertEquals(1, model.uiState.value.rows.size)
            assertNull(model.uiState.value.error)
            visibility.value = true
            assertEquals(2, model.uiState.value.rows.size)
        } finally { scope.cancel() }
    }

    @Test fun readFailureDuringDialogSurvivesCancelAndRemainsRetryable() = fixture { model, store, _, _ ->
        model.requestAction("old", TrashAction.Restore)
        store.readFailure.value = java.io.IOException("observation failed")
        assertNotNull(model.uiState.value.error)
        model.confirmAction()
        assertEquals(0, store.restoreCalls)
        assertNotNull(model.uiState.value.confirmation)
        model.dismissConfirmation()
        assertNull(model.uiState.value.confirmation)
        assertNotNull(model.uiState.value.error)
        store.rows.value = emptyList()
        store.readFailure.value = null
        model.retry()
        assertNull(model.uiState.value.error)
        assertTrue(model.uiState.value.rows.isEmpty())
    }

    @Test fun oneRetryRecoversAfterAsynchronousInitializationReset() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = Store(listOf(row()))
        val reset = CompletableDeferred<Unit>()
        store.failRead = true
        store.retryAction = { reset.await(); store.failRead = false }
        try {
            val model = TrashViewModel(store, SavedStateHandle(), MutableStateFlow(true), scope)
            assertNotNull(model.uiState.value.error)
            model.retry()
            assertTrue(model.uiState.value.loading)
            model.retry()
            assertTrue(model.uiState.value.loading)
            reset.complete(Unit)
            assertNull(model.uiState.value.error)
            assertEquals(listOf("old"), model.uiState.value.rows.map { it.item.id })
        } finally { scope.cancel() }
    }
}
