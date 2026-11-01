package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.CollectionDraft
import me.bubblespark.tidilo.data.CollectionPolicy
import me.bubblespark.tidilo.data.CollectionRepository
import java.util.UUID

data class CollectionEditorState(
    val draft: CollectionDraft = CollectionDraft(), val loading: Boolean = false,
    val pending: Boolean = false, val missing: Boolean = false, val done: Boolean = false,
    val error: String? = null, val confirmDelete: Boolean = false
)

class CollectionEditorViewModel(
    private val saved: SavedStateHandle,
    private val repository: CollectionRepository,
    private val collectionId: String?
) : ViewModel() {
    private val createId = saved.get<String>("createId") ?: UUID.randomUUID().toString().also { saved["createId"] = it }
    private fun restored() = CollectionDraft(saved["title"] ?: "", saved["description"] ?: "",
        saved["icon"] ?: "folder", saved["symbol"], saved["color"] ?: 0)
    private val mutable = MutableStateFlow(CollectionEditorState(restored(), loading = collectionId != null,
        confirmDelete = saved["confirmDelete"] ?: false))
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                if (collectionId == null) {
                    if (saved.get<Boolean>("submitting") == true && repository.observeCollection(createId).first() != null) {
                        mutable.value = mutable.value.copy(done = true)
                    }
                } else {
                    repository.observeCollection(collectionId).collect { row ->
                        if (row == null) mutable.value = mutable.value.copy(loading = false, missing = true)
                        else {
                            if (saved.get<Boolean>("loaded") != true) {
                                edit(CollectionDraft(row.title, row.description, row.presetIconId, row.customSymbol, row.colorIndex))
                            }
                            mutable.value = mutable.value.copy(loading = false, missing = false)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, error = "load") }
        }
    }

    fun edit(draft: CollectionDraft) {
        if (state.value.pending) return
        val bounded = draft.copy(title = CollectionPolicy.limitGraphemes(draft.title, 40),
            description = CollectionPolicy.limitGraphemes(CollectionPolicy.twoLines(draft.description), 100),
            customSymbol = CollectionPolicy.lastSymbol(draft.customSymbol.orEmpty()).ifBlank { null })
        saved["title"] = bounded.title; saved["description"] = bounded.description
        saved["icon"] = bounded.presetIconId; saved["symbol"] = bounded.customSymbol; saved["color"] = bounded.colorIndex
        saved["loaded"] = true
        mutable.value = state.value.copy(draft = bounded, error = null)
    }

    fun confirmDelete(value: Boolean) {
        saved["confirmDelete"] = value
        mutable.value = state.value.copy(confirmDelete = value)
    }

    fun save() {
        if (state.value.pending || state.value.loading || state.value.missing || state.value.done || state.value.draft.title.isBlank()) return
        val draft = state.value.draft
        write {
            if (collectionId == null) { repository.create(createId, draft, System.currentTimeMillis()); true }
            else repository.update(collectionId, draft, System.currentTimeMillis())
        }
    }

    fun delete() {
        val id = collectionId ?: return
        if (state.value.pending) return
        write { repository.delete(id) }
    }

    private fun write(action: suspend () -> Boolean) {
        saved["submitting"] = true
        mutable.value = state.value.copy(pending = true, error = null)
        viewModelScope.launch {
            try {
                val success = action()
                mutable.value = state.value.copy(pending = false, done = success, missing = !success)
                saved["submitting"] = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                saved["submitting"] = false
                mutable.value = state.value.copy(pending = false, error = "save")
            }
        }
    }
}
