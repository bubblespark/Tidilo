package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet

import me.bubblespark.tidilo.ui.components.TidiloAlertDialog

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.CollectionDraft
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.ui.theme.CollectionColorPalette
import java.util.UUID

@Composable
fun CollectionEditorBottomSheet(
    collectionId: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    assignToBookmarkId: String? = null,
    sessionId: String = rememberSaveable(collectionId, assignToBookmarkId) { UUID.randomUUID().toString() },
    onCreated: (String) -> Unit = { onDismiss() },
    onClosing: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val model = collectionSheetModel("collection-editor-$sessionId") { saved ->
        CollectionEditorViewModel(
            saved = saved,
            repository = CollectionRepository(AppDatabase.getInstance(context)),
            collectionId = collectionId,
            assignToBookmarkId = assignToBookmarkId
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val autosaveStatus by model.autosaveStatus.collectAsStateWithLifecycle()
    CollectionEditorSheet(
        sessionId = sessionId,
        editing = collectionId != null,
        createdId = model.createdId,
        state = state,
        autosaveStatus = autosaveStatus,
        onEdit = model::edit,
        onSave = model::create,
        onConfirmDelete = model::confirmDelete,
        onDelete = model::delete,
        onRetry = model::retryLoad,
        onRetrySave = model::retrySave,
        onDismiss = onDismiss,
        onCreated = onCreated,
        onClosing = onClosing,
        modifier = modifier
    )
}

@Composable
internal fun CollectionEditorSheet(
    sessionId: String,
    editing: Boolean,
    createdId: String,
    state: CollectionEditorState,
    autosaveStatus: AutosaveStatus,
    onEdit: (CollectionDraft) -> Unit,
    onSave: () -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onRetrySave: () -> Unit,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit = { onDismiss() },
    onClosing: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var closing by rememberSaveable(sessionId) { mutableStateOf(false) }
    var created by rememberSaveable(sessionId) { mutableStateOf(false) }
    val effectiveCreated = created || (!editing && state.done)
    fun beginClose(wasCreated: Boolean) {
        val nextCreated = created || wasCreated || (!editing && state.done)
        if (nextCreated && !created) {
            created = true
        }
        if (!closing) {
            closing = true
            onClosing(if (nextCreated) createdId else null)
        } else if (nextCreated) {
            onClosing(createdId)
        }
    }
    LaunchedEffect(state.done) {
        if (state.done) {
            beginClose(wasCreated = !editing)
        }
    }
    CollectionEditorContent(
        state = state,
        editing = editing,
        onEdit = onEdit,
        onSave = onSave,
        onConfirmDelete = onConfirmDelete,
        onDelete = onDelete,
        onDismiss = { if (!state.pending) beginClose(wasCreated = false) },
        modifier = modifier,
        onRetry = onRetry,
        autosaveStatus = autosaveStatus,
        onRetrySave = onRetrySave,
        closing = closing,
        onClosed = { if (effectiveCreated) onCreated(createdId) else onDismiss() }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CollectionEditorContent(
    state: CollectionEditorState, editing: Boolean, onEdit: (CollectionDraft) -> Unit,
    onSave: () -> Unit, onConfirmDelete: (Boolean) -> Unit, onDelete: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {},
    autosaveStatus: AutosaveStatus = AutosaveStatus.Idle, onRetrySave: () -> Unit = {},
    closing: Boolean = false, onClosed: (() -> Unit)? = null
) {
    val draft = state.draft
    val canEdit = !state.pending && !state.loading && !state.missing &&
        state.error != "load" && state.error != "missing_bookmark" && !closing
    val accent = CollectionColorPalette.getOrElse(draft.colorIndex) { CollectionColorPalette.first() }
    val sheetStyle = currentThemeSurfaces().resolve(SurfaceRole.SHEET, SurfaceVariant.EMPHASIZED)
    val errorBringIntoViewRequester = remember { BringIntoViewRequester() }
    val controller = rememberClosingSheetController(
        closing = closing,
        blockDismiss = state.pending,
        onRequestClose = onDismiss,
        onClosed = onClosed
    )
    LaunchedEffect(state.error) {
        if (state.error != null) {
            withFrameNanos { }
            try {
                errorBringIntoViewRequester.bringIntoView()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    TidiloModalBottomSheet(
        shape = sheetStyle.shape,
        onDismissRequest = controller.onDismissRequest,
        modifier = modifier,
        sheetState = controller.sheetState,
        sheetGesturesEnabled = !closing,
        containerColor = sheetStyle.containerColor) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            CollectionSheetHeader(
                title = stringResource(if (editing) R.string.collection_edit_title else R.string.collection_create_title),
                // Existing collections autosave; only creation keeps an explicit action.
                actionLabel = if (editing) null else stringResource(R.string.collection_create_action),
                enabled = draft.title.isNotBlank() && canEdit, onSave = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.scrim))
            val fieldSpacing = 16.dp
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(fieldSpacing)) {
                if (state.loading) CircularProgressIndicator()
                if (state.missing) Text(stringResource(R.string.collection_missing))
                val titleError = if (editing && autosaveStatus == AutosaveStatus.Invalid) {
                    stringResource(R.string.collection_title_required)
                } else null
                Column {
                    OutlinedTextField(
                        draft.title, { onEdit(draft.copy(title = it)) },
                        label = { Text(stringResource(R.string.collection_name_label)) },
                        singleLine = true,
                        isError = titleError != null,
                        supportingText = titleError?.let { message ->
                            { Text(message, Modifier.semantics { hideFromAccessibility() }) }
                        },
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth().semantics {
                            if (titleError != null) error(titleError)
                        }
                    )
                    // Use the existing field gap because Material reserves height for an empty supporting slot.
                    val gap = Modifier.fillMaxWidth().height(fieldSpacing)
                    Spacer(if (editing) gap.semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (titleError != null) text = AnnotatedString(titleError)
                    } else gap)
                    OutlinedTextField(draft.description, { onEdit(draft.copy(description = it)) },
                        label = { Text(stringResource(R.string.collection_desc_label)) }, minLines = 2, maxLines = 2,
                        enabled = canEdit, modifier = Modifier.fillMaxWidth())
                }
                if (editing && !state.missing) {
                    // The blank-title error is shown on the title field itself.
                    AutosaveStatusLine(
                        status = if (autosaveStatus == AutosaveStatus.Invalid) AutosaveStatus.Idle else autosaveStatus,
                        onRetry = onRetrySave,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                state.error?.let { error ->
                    val messageRes = when (error) {
                        "load" -> R.string.collection_load_failed
                        "missing_bookmark" -> R.string.collection_target_bookmark_missing
                        else -> R.string.collection_save_failed
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(errorBringIntoViewRequester),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(messageRes),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error
                        )
                        if (error == "load" || (!editing && error == "save")) {
                            TextButton(
                                onClick = if (error == "load") onRetry else onSave,
                                enabled = !state.loading && !state.pending && !closing
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.collection_icon_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollectionPresetIcon.entries.forEach { preset ->
                        val chosen = draft.customSymbol.isNullOrBlank() && preset.id == draft.presetIconId
                        val label = stringResource(when (preset) {
                            CollectionPresetIcon.FOLDER -> R.string.collection_icon_folder
                            CollectionPresetIcon.SCHEDULE -> R.string.collection_icon_schedule
                            CollectionPresetIcon.STAR -> R.string.collection_icon_star
                            CollectionPresetIcon.WORK -> R.string.collection_icon_work
                            CollectionPresetIcon.CODE -> R.string.collection_icon_code
                            CollectionPresetIcon.LIGHTBULB -> R.string.collection_icon_lightbulb
                            CollectionPresetIcon.PALETTE -> R.string.collection_icon_palette
                            CollectionPresetIcon.FAVORITE -> R.string.collection_icon_favorite
                        })
                        Surface(selected = chosen, onClick = { onEdit(draft.copy(presetIconId = preset.id, customSymbol = null)) },
                            enabled = canEdit, modifier = Modifier.size(48.dp).semantics { role = Role.RadioButton },
                            shape = MaterialTheme.shapes.medium,
                            color = if (chosen) accent.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) accent else MaterialTheme.colorScheme.outlineVariant)) {
                            Box(contentAlignment = Alignment.Center) { Icon(preset.icon, label, Modifier.size(22.dp)) }
                        }
                    }
                }
                OutlinedTextField(draft.customSymbol.orEmpty(), { onEdit(draft.copy(customSymbol = it)) },
                    label = { Text(stringResource(R.string.collection_custom_char_placeholder)) }, singleLine = true,
                    enabled = canEdit, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.collection_color_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollectionColorPalette.forEachIndexed { index, color ->
                        val label = stringResource(R.string.collection_color_option, index + 1)
                        Surface(selected = draft.colorIndex == index, onClick = { onEdit(draft.copy(colorIndex = index)) }, enabled = canEdit,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = label; role = Role.RadioButton },
                            shape = CircleShape, color = color) {
                            Box(contentAlignment = Alignment.Center) {
                                if (draft.colorIndex == index) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.scrim)
                            }
                        }
                    }
                }
                if (editing && !state.missing) TextButton(onClick = { onConfirmDelete(true) }, enabled = canEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (state.confirmDelete) TidiloAlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).shape,onDismissRequest = { onConfirmDelete(false) },
        title = { Text(stringResource(R.string.collection_delete_title)) },
        text = { Text(stringResource(R.string.collection_delete_message, draft.title)) },
        confirmButton = { TextButton(onClick = onDelete, enabled = !state.pending) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { onConfirmDelete(false) }, enabled = !state.pending) { Text(stringResource(R.string.common_cancel)) } })
}
