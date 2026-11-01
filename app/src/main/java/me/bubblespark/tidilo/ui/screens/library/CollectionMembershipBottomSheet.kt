package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.db.AppDatabase
import java.util.UUID

@Composable
fun CollectionMembershipBottomSheet(bookmarkId: String, onDismiss: () -> Unit, onOpenCollections: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = rememberSaveable(bookmarkId) { UUID.randomUUID().toString() }
    val model = collectionSheetModel("collection-membership-$session") { saved ->
        CollectionMembershipViewModel(saved, CollectionRepository(AppDatabase.getInstance(context)), bookmarkId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDismiss() }
    CollectionMembershipContent(state, model::toggle, model::save, model::retry,
        { if (!state.pending) onDismiss() }, onOpenCollections, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionMembershipContent(
    state: CollectionMembershipState, onToggle: (String) -> Unit, onSave: () -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit, onOpenCollections: () -> Unit, modifier: Modifier = Modifier
) {
    val pending by rememberUpdatedState(state.pending)
    ModalBottomSheet(onDismissRequest = { if (!pending) onDismiss() }, modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !pending })) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            CollectionSheetHeader(stringResource(R.string.collection_assign_title), stringResource(R.string.common_save),
                enabled = !state.loading && !state.pending && state.error != "load", onSave = onSave,
                colors = ButtonDefaults.buttonColors())
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) CircularProgressIndicator()
                else if (state.rows.isEmpty() && state.error == null) {
                    Text(stringResource(R.string.collection_assign_empty))
                    Button(onClick = onOpenCollections, enabled = !state.pending, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.collection_open_library))
                    }
                }
                state.rows.forEach { row ->
                    key(row.collection.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = row.collection.id in state.selectedIds, enabled = !state.pending,
                                role = Role.Checkbox, onValueChange = { onToggle(row.collection.id) }).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.collection.id in state.selectedIds, onCheckedChange = null)
                            Text(row.collection.title, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (state.error != null) {
                    Text(stringResource(R.string.collection_save_failed), color = MaterialTheme.colorScheme.error)
                    if (state.error == "load") TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
                }
            }
        }
    }
}
