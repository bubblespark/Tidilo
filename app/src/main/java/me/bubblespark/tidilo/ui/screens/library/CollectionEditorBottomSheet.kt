package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.CollectionDraft
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.ui.theme.CollectionColorPalette
import java.util.UUID

@Composable
fun CollectionEditorBottomSheet(collectionId: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = rememberSaveable(collectionId) { UUID.randomUUID().toString() }
    val model = collectionSheetModel("collection-editor-$session") { saved ->
        CollectionEditorViewModel(saved, CollectionRepository(AppDatabase.getInstance(context)), collectionId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    CollectionEditorContent(state, collectionId != null, model::edit, model::save,
        model::confirmDelete, model::delete, { if (!state.pending) onDismiss() }, modifier, onRetry = model::retryLoad)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CollectionEditorContent(
    state: CollectionEditorState, editing: Boolean, onEdit: (CollectionDraft) -> Unit,
    onSave: () -> Unit, onConfirmDelete: (Boolean) -> Unit, onDelete: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {}
) {
    val draft = state.draft
    val pending by rememberUpdatedState(state.pending)
    val canEdit = !state.pending && !state.loading && !state.missing && state.error != "load"
    val accent = CollectionColorPalette.getOrElse(draft.colorIndex) { CollectionColorPalette.first() }
    ModalBottomSheet(onDismissRequest = { if (!pending) onDismiss() }, modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !pending }),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            CollectionSheetHeader(
                title = stringResource(if (editing) R.string.collection_edit_title else R.string.collection_create_title),
                actionLabel = stringResource(if (editing) R.string.collection_edit_action else R.string.collection_create_action),
                enabled = draft.title.isNotBlank() && canEdit, onSave = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = MaterialTheme.colorScheme.scrim))
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.loading) CircularProgressIndicator()
                if (state.missing) Text(stringResource(R.string.collection_missing))
                OutlinedTextField(draft.title, { onEdit(draft.copy(title = it)) },
                    label = { Text(stringResource(R.string.collection_name_label)) }, singleLine = true,
                    enabled = canEdit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.description, { onEdit(draft.copy(description = it)) },
                    label = { Text(stringResource(R.string.collection_desc_label)) }, minLines = 2, maxLines = 2,
                    enabled = canEdit, modifier = Modifier.fillMaxWidth())
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
                state.error?.let { error ->
                    Text(stringResource(if (error == "load") R.string.collection_load_failed else R.string.collection_save_failed),
                        color = MaterialTheme.colorScheme.error)
                    if (error == "load") TextButton(onClick = onRetry, enabled = !state.loading && !state.pending) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
                if (editing && !state.missing) TextButton(onClick = { onConfirmDelete(true) }, enabled = canEdit, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (state.confirmDelete) AlertDialog(onDismissRequest = { onConfirmDelete(false) },
        title = { Text(stringResource(R.string.collection_delete_title)) },
        text = { Text(stringResource(R.string.collection_delete_message, draft.title)) },
        confirmButton = { TextButton(onClick = onDelete, enabled = !state.pending) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { onConfirmDelete(false) }, enabled = !state.pending) { Text(stringResource(R.string.common_cancel)) } })
}
