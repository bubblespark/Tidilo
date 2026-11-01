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
    var closeRequested by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    var openCollectionsAfterSave by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    fun requestClose(openCollections: Boolean = false) {
        if (model.state.value.pending || model.state.value.done || closeRequested) return
        openCollectionsAfterSave = openCollectionsAfterSave || openCollections
        closeRequested = true
    }
    LaunchedEffect(closeRequested, state.loading, state.error) {
        if (closeRequested && !state.loading && state.error != "load") {
            closeRequested = false
            model.save()
        }
    }
    LaunchedEffect(state.done) {
        if (state.done) {
            if (openCollectionsAfterSave) onOpenCollections() else onDismiss()
        }
    }
    CollectionMembershipContent(state, model::toggle,
        { if (model.state.value.error == "load") model.retry() else requestClose() },
        { requestClose() }, { requestClose(openCollections = true) }, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionMembershipContent(
    state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit, onOpenCollections: () -> Unit, modifier: Modifier = Modifier
) {
    val pending by rememberUpdatedState(state.pending)
    val done by rememberUpdatedState(state.done)
    val requestClose by rememberUpdatedState(onDismiss)
    ModalBottomSheet(onDismissRequest = { if (!pending) onDismiss() }, modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { target ->
                if (target == SheetValue.Hidden && !done) {
                    if (!pending) requestClose()
                    false
                } else true
            })) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Text(stringResource(R.string.collection_assign_title),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge)
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
                    TextButton(onClick = onRetry, enabled = !state.pending) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}
