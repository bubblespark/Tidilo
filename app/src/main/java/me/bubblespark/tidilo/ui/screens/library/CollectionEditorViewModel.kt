package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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

/**
 * Creating a collection keeps an explicit Create action. Editing an existing collection autosaves each
 * valid draft after a short pause; a blank title stays a recoverable draft and is never written.
 */
class CollectionEditorViewModel(
    private val saved: SavedStateHandle,
    private val repository: CollectionRepository,
    private val collectionId: String?,
    // Not cancelled with the ViewModel, so the final flush in onCleared can still start its write.
    saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel() {
    private val createId = saved.get<String>("createId") ?: UUID.randomUUID().toString().also { saved["createId"] = it }
    private fun restored() = CollectionDraft(saved["title"] ?: "", saved["description"] ?: "",
        saved["icon"] ?: "folder", saved["symbol"], saved["color"] ?: 0)
    private val mutable = MutableStateFlow(CollectionEditorState(restored(), loading = collectionId != null,
        confirmDelete = saved["confirmDelete"] ?: false))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var storedDraft: CollectionDraft? = null
    private val autosaver: DebouncedAutosaver<CollectionDraft>? = collectionId?.let { id ->
        DebouncedAutosaver(
            scope = saveScope,
            isValid = { it.title.isNotBlank() },
            isStored = { draft -> runCatching { CollectionPolicy.normalize(draft) }.getOrNull() == storedDraft }
        ) { draft ->
            if (!repository.update(id, draft, System.currentTimeMillis())) {
                mutable.value = state.value.copy(missing = true)
            }
        }
    }

    /** Autosave progress for an existing collection; always Idle while creating. */
    val autosaveStatus: StateFlow<AutosaveStatus> = autosaver?.status ?: MutableStateFlow(AutosaveStatus.Idle)

    init {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        mutable.value = state.value.copy(loading = true, missing = false, error = null)
        loadJob = viewModelScope.launch {
            try {
                if (collectionId == null) {
                    if (saved.get<Boolean>("submitting") == true && repository.observeCollection(createId).first() != null) {
                        mutable.value = mutable.value.copy(done = true)
                    }
                    mutable.value = mutable.value.copy(loading = false)
                } else {
                    repository.observeCollection(collectionId).collect { row ->
                        if (row == null) mutable.value = mutable.value.copy(loading = false, missing = true)
                        else {
                            val stored = CollectionDraft(row.title, row.description, row.presetIconId, row.customSymbol, row.colorIndex)
                            storedDraft = stored
                            if (saved.get<Boolean>("loaded") != true) {
                                applyDraft(stored)
                            }
                            mutable.value = mutable.value.copy(loading = false, missing = false)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(loading = false, error = "load") }
        }
    }

    fun retryLoad() {
        if (state.value.error != "load" || state.value.pending || state.value.done) return
        load()
    }

    fun edit(draft: CollectionDraft) {
        if (state.value.pending || state.value.loading || state.value.missing || state.value.error == "load") return
        applyDraft(draft)
        autosaver?.submit(state.value.draft)
    }

    /** Re-attempts a failed autosave of the current draft. */
    fun retrySave() {
        autosaver?.retry()
    }

    private fun applyDraft(draft: CollectionDraft) {
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

    /** Explicit Create for a new collection; existing collections autosave instead. */
    fun create() {
        if (collectionId != null || state.value.pending || state.value.loading || state.value.done || state.value.draft.title.isBlank()) return
        val draft = state.value.draft
        write { repository.create(createId, draft, System.currentTimeMillis()); true }
    }

    fun delete() {
        val id = collectionId ?: return
        if (state.value.pending || state.value.loading || state.value.missing || state.value.error == "load") return
        autosaver?.cancel()
        write { repository.delete(id) }
    }

    override fun onCleared() {
        // Closing the sheet or recreating it must not drop the last valid draft.
        if (!state.value.done) autosaver?.flush()
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
