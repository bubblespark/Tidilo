package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
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
    CollectionMembershipSheet(bookmarkId, state, model::toggle, model::save, model::retry,
        onDismiss, onOpenCollections, modifier)
}

@Composable
internal fun CollectionMembershipSheet(
    bookmarkId: String, state: CollectionMembershipState, onToggle: (String) -> Unit,
    onSave: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
    onOpenCollections: () -> Unit, modifier: Modifier = Modifier
) {
    val currentState by rememberUpdatedState(state)
    var closeRequested by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    var closeWithoutSaving by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    var openCollectionsAfterClose by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    fun requestClose(openCollections: Boolean = false) {
        if (currentState.pending || currentState.done || closeRequested || closeWithoutSaving) return
        openCollectionsAfterClose = openCollectionsAfterClose || openCollections
        closeRequested = true
    }
    LaunchedEffect(closeRequested, state.loading, state.error) {
        if (closeRequested && !state.loading) {
            closeRequested = false
            if (state.error == "load") closeWithoutSaving = true else onSave()
        }
    }
    CollectionMembershipContent(state, onToggle,
        { if (currentState.error == "load") onRetry() else requestClose() },
        { requestClose() }, { requestClose(openCollections = true) },
        onClosed = {
            if (openCollectionsAfterClose) onOpenCollections() else onDismiss()
        }, modifier = modifier, closeWithoutSaving = closeWithoutSaving)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionMembershipContent(
    state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit, onOpenCollections: () -> Unit,
    onClosed: () -> Unit, modifier: Modifier = Modifier, closeWithoutSaving: Boolean = false
) {
    val pending by rememberUpdatedState(state.pending)
    val closing by rememberUpdatedState(state.done || closeWithoutSaving)
    val requestClose by rememberUpdatedState(onDismiss)
    val currentOnClosed by rememberUpdatedState(onClosed)
    var closeNotified by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && !closing) {
                if (!pending) requestClose()
                false
            } else true
        })
    fun notifyClosed() {
        if (!sheetState.isVisible && !closeNotified) {
            closeNotified = true
            currentOnClosed()
        }
    }
    LaunchedEffect(state.done || closeWithoutSaving, sheetState) {
        if (state.done || closeWithoutSaving) {
            sheetState.hide()
            notifyClosed()
        }
    }
    ModalBottomSheet(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.SHEET).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.SHEET).shape,onDismissRequest = { if (closing) notifyClosed() else if (!pending) onDismiss() },
        modifier = modifier, sheetState = sheetState, sheetGesturesEnabled = !pending && !closing) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Text(stringResource(R.string.collection_assign_title),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) CircularProgressIndicator()
                else if (state.rows.isEmpty() && state.error == null) {
                    Text(stringResource(R.string.collection_assign_empty))
                    Button(onClick = onOpenCollections, enabled = !state.pending && !closing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.collection_open_library))
                    }
                }
                state.rows.forEach { row ->
                    key(row.collection.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = row.collection.id in state.selectedIds, enabled = !state.pending && !closing,
                                role = Role.Checkbox, onValueChange = { onToggle(row.collection.id) }).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.collection.id in state.selectedIds, onCheckedChange = null)
                            Text(row.collection.title, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (state.error != null) {
                    Text(stringResource(if (state.error == "load") R.string.collection_load_failed else R.string.collection_save_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, enabled = !state.pending && !closing) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}
