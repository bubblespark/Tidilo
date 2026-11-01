package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.db.CollectionWithCount

data class CollectionMembershipState(
    val rows: List<CollectionWithCount> = emptyList(), val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true, val pending: Boolean = false, val done: Boolean = false,
    val error: String? = null
)

class CollectionMembershipViewModel(
    private val saved: SavedStateHandle, private val repository: CollectionRepository,
    private val bookmarkId: String
) : ViewModel() {
    private val mutable = MutableStateFlow(CollectionMembershipState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    init { retry() }

    fun retry() {
        observation?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try {
                combine(repository.observeCollections(true), repository.observeMembershipIds(bookmarkId)) { rows, membership -> rows to membership }
                    .collect { (rows, membership) ->
                        val selection = if (saved.get<Boolean>("loaded") == true) saved.get<ArrayList<String>>("selected").orEmpty().toSet() else membership.toSet()
                        val valid = selection.intersect(rows.map { it.collection.id }.toSet())
                        saved["loaded"] = true; saved["selected"] = ArrayList(valid)
                        mutable.value = state.value.copy(rows = rows, selectedIds = valid, loading = false)
                    }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(loading = false, error = "load") }
        }
    }

    fun toggle(id: String) {
        if (state.value.loading || state.value.pending || state.value.rows.none { it.collection.id == id }) return
        val selected = state.value.selectedIds.let { if (id in it) it - id else it + id }
        saved["selected"] = ArrayList(selected)
        mutable.value = state.value.copy(selectedIds = selected, error = null)
    }

    fun save() {
        if (state.value.loading || state.value.pending || state.value.done) return
        val selection = state.value.selectedIds
        mutable.value = state.value.copy(pending = true, error = null)
        viewModelScope.launch {
            try {
                val saved = repository.setMemberships(bookmarkId, selection)
                mutable.value = state.value.copy(pending = false, done = saved, error = if (saved) null else "missing")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(pending = false, error = "save") }
        }
    }
}
