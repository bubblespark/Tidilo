package me.bubblespark.tidilo.ui.screens.trash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.db.TrashMutationResult

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModel(
    private val store: TrashStore,
    private val savedStateHandle: SavedStateHandle,
    private val visibility: StateFlow<Boolean>,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {
    private val scope = coroutineScope ?: viewModelScope
    private val mutable = MutableStateFlow(TrashUiState(confirmation = savedStateHandle.get<String>("target")?.let { id ->
        when (savedStateHandle.get<String>("action")) {
            "restore" -> TrashConfirmation(id, TrashAction.Restore)
            "delete" -> TrashConfirmation(id, TrashAction.Delete)
            else -> null
        }
    }))
    val uiState = mutable.asStateFlow()
    private var observation: Job? = null

    init { observe() }

    private fun observe() {
        observation?.cancel()
        mutable.value = mutable.value.copy(loading = true, error = null, readFailed = false)
        observation = scope.launch {
            try {
                visibility.flatMapLatest(store::observe).collect { rows ->
                    mutable.value = mutable.value.copy(loading = false, rows = rows)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(loading = false, error = error, readFailed = true) }
        }
    }

    fun requestAction(id: String, action: TrashAction) {
        val state = mutable.value
        if (state.loading || state.busy || state.confirmation != null || state.error != null) return
        val row = state.rows.find { it.item.id == id } ?: return
        if (action == TrashAction.Restore && row.duplicateInLibrary) return
        setConfirmation(TrashConfirmation(id, action))
    }

    private fun setConfirmation(target: TrashConfirmation?) {
        savedStateHandle["target"] = target?.id
        savedStateHandle["action"] = target?.action?.let { if (it == TrashAction.Restore) "restore" else "delete" }
        val state = mutable.value
        mutable.value = state.copy(confirmation = target, error = state.error.takeIf { state.readFailed })
    }

    fun dismissConfirmation() {
        if (!mutable.value.busy) setConfirmation(null)
    }

    fun confirmAction() {
        val state = mutable.value
        val target = state.confirmation ?: return
        if (state.busy || state.loading || state.readFailed) return
        val row = state.rows.find { it.item.id == target.id }
        val rejected = when {
            row == null -> TrashMutationResult.Missing
            target.action == TrashAction.Restore && row.duplicateInLibrary -> TrashMutationResult.DuplicateInLibrary
            else -> null
        }
        if (rejected != null) {
            setConfirmation(null)
            mutable.value = mutable.value.copy(actionResult = rejected)
            return
        }
        mutable.value = state.copy(busy = true, error = null, actionResult = null)
        scope.launch {
            try {
                val result = if (target.action == TrashAction.Restore) store.restore(target.id) else store.delete(target.id)
                setConfirmation(null)
                mutable.value = mutable.value.copy(busy = false, actionResult = result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, error = error) }
        }
    }

    fun retry() {
        if (mutable.value.busy) return
        store.retry()
        observe()
    }

    fun clearActionResult() { mutable.value = mutable.value.copy(actionResult = null) }
}

internal class RepositoryTrashStore(private val repository: BookmarkRepository) : TrashStore {
    override fun observe(showNsfw: Boolean) = repository.observeTrash(showNsfw)
    override suspend fun restore(id: String) = repository.restoreFromTrash(id)
    override suspend fun delete(id: String) = repository.deleteFromTrash(id)
    override fun retry() = repository.retryInitialization()
}
