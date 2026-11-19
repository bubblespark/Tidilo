package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.db.CollectionWithCount

data class CollectionMembershipState(
    val rows: List<CollectionWithCount> = emptyList(), val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true, val pendingIds: Set<String> = emptySet(),
    val error: String? = null
)

/** Storage seam for single-membership writes; each call is one atomic add or remove. */
internal interface MembershipStore {
    fun observeCollections(): kotlinx.coroutines.flow.Flow<List<CollectionWithCount>>
    fun observeMembershipIds(bookmarkId: String): kotlinx.coroutines.flow.Flow<List<String>>
    suspend fun setMembership(bookmarkId: String, collectionId: String, member: Boolean): Boolean
}

/**
 * Persists each checkbox change as it is made. The sheet holds no unsaved selection, so closing it
 * only ends the interaction. A failed change reverts to the stored membership and offers Retry.
 */
class CollectionMembershipViewModel internal constructor(
    private val store: MembershipStore,
    private val bookmarkId: String
) : ViewModel() {
    private val mutable = MutableStateFlow(CollectionMembershipState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null
    private var stored: Set<String> = emptySet()
    /** Optimistic values for toggles that are in flight or not yet reflected by Room. */
    private val overrides = mutableMapOf<String, Boolean>()
    /** Unresolved failed changes per collection, kept until retried or superseded by a new toggle of that row. */
    private val failedChanges = linkedMapOf<String, Boolean>()

    init { retry() }

    /** Reloads after a read failure, or re-applies every unresolved failed membership change. */
    fun retry() {
        // A failed read stops the observation; restart it even when failed writes are also retried.
        // With nothing to re-apply, Retry keeps its previous meaning: reload.
        if (observation?.isActive != true || failedChanges.isEmpty()) observe()
        if (failedChanges.isNotEmpty()) {
            val changes = failedChanges.toMap()
            failedChanges.clear()
            changes.forEach { (id, member) -> apply(id, member) }
        }
    }

    private fun observe() {
        observation?.cancel()
        mutable.value = state.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try {
                combine(store.observeCollections(), store.observeMembershipIds(bookmarkId)) { rows, membership -> rows to membership }
                    .collect { (rows, membership) ->
                        stored = membership.toSet()
                        overrides.entries.removeAll { (id, member) ->
                            id !in state.value.pendingIds && (id in stored) == member
                        }
                        val validIds = rows.map { it.collection.id }.toSet()
                        mutable.value = state.value.copy(rows = rows, selectedIds = selection().intersect(validIds),
                            loading = false, error = state.value.error.takeUnless { it == "load" })
                    }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = state.value.copy(loading = false, error = "load") }
        }
    }

    fun toggle(id: String) {
        val current = state.value
        if (current.loading || current.error == "load" || id in current.pendingIds ||
            current.rows.none { it.collection.id == id }) return
        failedChanges.remove(id)
        apply(id, id !in current.selectedIds)
    }

    private fun apply(id: String, member: Boolean) {
        overrides[id] = member
        mutable.value = state.value.copy(selectedIds = selection(), pendingIds = state.value.pendingIds + id,
            error = if (failedChanges.isNotEmpty()) "save" else null)
        viewModelScope.launch {
            val error = try {
                if (store.setMembership(bookmarkId, id, member)) null else "missing"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "save" }
            if (error != null) {
                overrides.remove(id)
                if (error == "save") failedChanges[id] = member
            } else if ((id in stored) == member) {
                // Room may already have emitted this change while the write was pending.
                overrides.remove(id)
            }
            mutable.value = state.value.copy(selectedIds = selection(), pendingIds = state.value.pendingIds - id,
                error = when {
                    error == "missing" -> "missing"
                    failedChanges.isNotEmpty() -> "save"
                    else -> state.value.error.takeUnless { it == "save" }
                })
        }
    }

    private fun selection(): Set<String> =
        stored.filterNot { overrides[it] == false }.toSet() + overrides.filterValues { it }.keys
}
