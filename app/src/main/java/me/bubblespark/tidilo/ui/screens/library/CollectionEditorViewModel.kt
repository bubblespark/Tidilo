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
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.data.CollectionDraft
import me.bubblespark.tidilo.data.CollectionField
import me.bubblespark.tidilo.data.changedFields
import me.bubblespark.tidilo.data.withFields
import me.bubblespark.tidilo.data.CollectionPolicy
import me.bubblespark.tidilo.data.CollectionRepository
import java.util.UUID

data class CollectionEditorState(
    val draft: CollectionDraft = CollectionDraft(), val loading: Boolean = false,
    val pending: Boolean = false, val missing: Boolean = false, val done: Boolean = false,
    val error: String? = null, val confirmDelete: Boolean = false
)

/**
 * Creating a collection keeps an explicit Create action. Editing an existing collection autosaves the
 * fields the user edited after a short pause; a blank title stays a recoverable draft and is never written.
 *
 * Fields the user has not edited follow the stored row; edited fields keep the user's value until a write
 * of their latest edit succeeds ([EditedFields]). Writes touch only edited fields, so another editor's change
 * to any other field is never overwritten.
 */
class CollectionEditorViewModel(
    private val saved: SavedStateHandle,
    private val repository: CollectionRepository,
    private val collectionId: String?,
    // Not cancelled with the ViewModel, so the final flush in onCleared can still start its write.
    saveScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val assignToBookmarkId: String? = null
) : ViewModel() {
    private val createId = saved.get<String>("createId") ?: UUID.randomUUID().toString().also { saved["createId"] = it }
    val createdId: String get() = createId
    private fun restored() = CollectionDraft(saved["title"] ?: "", saved["description"] ?: "",
        saved["icon"] ?: "folder", saved["symbol"], saved["color"] ?: 0)
    private val mutable = MutableStateFlow(CollectionEditorState(restored(), loading = collectionId != null,
        confirmDelete = saved["confirmDelete"] ?: false))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    /** Latest stored row; drives every field the user has not edited. */
    private var stored: CollectionDraft? = null
    private var firstLoad = true
    private val edits = EditedFields(saved.get<ArrayList<String>>("edited").orEmpty().map(CollectionField::valueOf))

    /** An autosave snapshot: the draft plus the edited fields (and their edit versions) it persists. */
    private data class FieldWrite(val draft: CollectionDraft, val edits: Map<CollectionField, Long>)

    private val autosaver: DebouncedAutosaver<FieldWrite>? = collectionId?.let { id ->
        DebouncedAutosaver(
            scope = saveScope,
            isValid = { it.draft.title.isNotBlank() },
            writeLock = CollectionRepository.editorWriteLock
        ) { write ->
            val result = repository.updateFields(id, write.draft, write.edits.keys, System.currentTimeMillis())
            if (result == null) {
                mutable.value = state.value.copy(missing = true)
            } else {
                edits.onWritten(write.edits)
                saveEdits()
                stored = result
                showStored()
            }
        }
    }

    private fun submitEdits() {
        if (edits.isEmpty) return
        autosaver?.submit(FieldWrite(state.value.draft, edits.snapshot()))
    }

    private fun saveEdits() {
        saved["edited"] = ArrayList(edits.fields.map { it.name })
    }

    /** Shows the stored value in every field the user has not edited. */
    private fun showStored() {
        val row = stored ?: return
        val display = row.withFields(state.value.draft, edits.fields)
        if (display != state.value.draft) applyDraft(display)
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
                    if (saved.get<Boolean>("submitting") == true) {
                        val outcome = CollectionRepository.editorWriteLock.withLock {
                            val exists = repository.observeCollection(createId).first() != null
                            if (!exists) {
                                null
                            } else if (assignToBookmarkId == null || createId in repository.observeMembershipIds(assignToBookmarkId).first()) {
                                "done"
                            } else if (!repository.hasBookmark(assignToBookmarkId)) {
                                "missing_bookmark"
                            } else {
                                null
                            }
                        }
                        saved["submitting"] = false
                        when (outcome) {
                            "done" -> mutable.value = mutable.value.copy(done = true)
                            "missing_bookmark" -> mutable.value = mutable.value.copy(error = "missing_bookmark")
                        }
                    }
                    mutable.value = mutable.value.copy(loading = false)
                } else {
                    repository.observeCollection(collectionId).collect { row ->
                        if (row == null) mutable.value = mutable.value.copy(loading = false, missing = true)
                        else {
                            stored = CollectionDraft(row.title, row.description, row.presetIconId, row.customSymbol, row.colorIndex)
                            showStored()
                            if (firstLoad) {
                                firstLoad = false
                                saved["loaded"] = true
                                // Edits restored after rotation or process recreation are still owed.
                                submitEdits()
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
        val before = state.value.draft
        applyDraft(draft)
        if (collectionId == null) return
        val changed = state.value.draft.changedFields(before)
        if (changed.isEmpty()) return
        edits.markEdited(changed)
        saveEdits()
        submitEdits()
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
        val targetBookmarkId = assignToBookmarkId
        write(missingError = if (targetBookmarkId != null) "missing_bookmark" else null) {
            CollectionRepository.editorWriteLock.withLock {
                val now = System.currentTimeMillis()
                if (targetBookmarkId != null) {
                    repository.createAndAssign(createId, draft, targetBookmarkId, now)
                } else {
                    repository.create(createId, draft, now)
                    true
                }
            }
        }
    }

    fun delete() {
        val id = collectionId ?: return
        if (state.value.pending || state.value.loading || state.value.missing || state.value.error == "load") return
        autosaver?.cancel()
        write { repository.delete(id) }
    }

    override fun onCleared() {
        // Closing the sheet or recreating it must not drop owed edits (this also re-sends a failed one).
        if (!state.value.done && !edits.isEmpty) {
            submitEdits()
            autosaver?.flush()
        }
    }

    private fun write(missingError: String? = null, action: suspend () -> Boolean) {
        saved["submitting"] = true
        mutable.value = state.value.copy(pending = true, error = null)
        viewModelScope.launch {
            try {
                val success = action()
                mutable.value = state.value.copy(
                    pending = false,
                    done = success,
                    missing = !success && missingError == null,
                    error = if (success) null else missingError
                )
                saved["submitting"] = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                saved["submitting"] = false
                mutable.value = state.value.copy(pending = false, error = "save")
            }
        }
    }
}
