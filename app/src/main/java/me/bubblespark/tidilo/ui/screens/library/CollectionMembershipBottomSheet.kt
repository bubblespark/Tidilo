package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.modernRaisedTextStyle
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
    val model = collectionSheetModel("collection-membership-$session") { _ ->
        CollectionMembershipViewModel(CollectionRepository(AppDatabase.getInstance(context)).asMembershipStore(), bookmarkId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    CollectionMembershipSheet(bookmarkId, state, model::toggle, model::retry,
        onDismiss, onOpenCollections, modifier)
}

internal fun CollectionRepository.asMembershipStore(): MembershipStore = object : MembershipStore {
    override fun observeCollections() = this@asMembershipStore.observeCollections(true)
    override fun observeMembershipIds(bookmarkId: String) = this@asMembershipStore.observeMembershipIds(bookmarkId)
    override suspend fun setMembership(bookmarkId: String, collectionId: String, member: Boolean) =
        if (member) addMembership(bookmarkId, collectionId) else removeMembership(bookmarkId, collectionId)
}

/** Checkbox changes persist as they are made, so every close path simply hides the sheet. */
@Composable
internal fun CollectionMembershipSheet(
    bookmarkId: String, state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit,
    onOpenCollections: () -> Unit, modifier: Modifier = Modifier
) {
    var closing by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    var openCollectionsAfterClose by rememberSaveable(bookmarkId) { mutableStateOf(false) }
    CollectionMembershipContent(state, onToggle, onRetry,
        onDismiss = { closing = true },
        onOpenCollections = { openCollectionsAfterClose = true; closing = true },
        onClosed = {
            if (openCollectionsAfterClose) onOpenCollections() else onDismiss()
        }, modifier = modifier, closing = closing)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionMembershipContent(
    state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit, onOpenCollections: () -> Unit,
    onClosed: () -> Unit, modifier: Modifier = Modifier, closing: Boolean = false
) {
    val isClosing by rememberUpdatedState(closing)
    val requestClose by rememberUpdatedState(onDismiss)
    val currentOnClosed by rememberUpdatedState(onClosed)
    var closeNotified by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && !isClosing) {
                requestClose()
                false
            } else true
        })
    fun notifyClosed() {
        if (!sheetState.isVisible && !closeNotified) {
            closeNotified = true
            currentOnClosed()
        }
    }
    LaunchedEffect(closing, sheetState) {
        if (closing) {
            sheetState.hide()
            notifyClosed()
        }
    }
    TidiloModalBottomSheet(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.SHEET).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.SHEET).shape,onDismissRequest = { if (closing) notifyClosed() else onDismiss() },
        modifier = modifier, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Text(stringResource(R.string.collection_assign_title),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = modernRaisedTextStyle(MaterialTheme.typography.titleLarge))
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) CircularProgressIndicator()
                else if (state.rows.isEmpty() && state.error == null) {
                    Text(stringResource(R.string.collection_assign_empty))
                    val canOpenCollections = !closing
                    Button(onClick = onOpenCollections, enabled = canOpenCollections, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.collection_open_library),
                            style = modernRaisedTextStyle(LocalTextStyle.current, canOpenCollections)
                        )
                    }
                }
                state.rows.forEach { row ->
                    key(row.collection.id) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .toggleable(value = row.collection.id in state.selectedIds,
                                enabled = row.collection.id !in state.pendingIds && !closing,
                                role = Role.Checkbox, onValueChange = { onToggle(row.collection.id) }).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.collection.id in state.selectedIds, onCheckedChange = null)
                            Text(row.collection.title, modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (state.error != null) {
                    Text(stringResource(if (state.error == "load") R.string.collection_load_failed else R.string.collection_save_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, enabled = state.pendingIds.isEmpty() && !closing) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}
