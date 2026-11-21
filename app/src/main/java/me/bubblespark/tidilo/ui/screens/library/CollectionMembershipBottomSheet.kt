package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.components.TidiloModalBottomSheet

import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import me.bubblespark.tidilo.ui.theme.modernRaisedTextStyle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.db.AppDatabase
import java.util.UUID

@Composable
fun CollectionMembershipBottomSheet(
    bookmarkId: String,
    onDismiss: () -> Unit,
    onNewCollection: () -> Unit = {},
    modifier: Modifier = Modifier,
    model: CollectionMembershipViewModel? = null,
    scrollState: ScrollState = rememberScrollState(),
    focusedCollectionId: String? = null,
    onFocusHandled: () -> Unit = {},
    onCancelTransition: () -> Unit = {},
    sheetInstanceId: Int = 0
) {
    val context = LocalContext.current
    val resolvedModel = model ?: run {
        val session = rememberSaveable(bookmarkId) { UUID.randomUUID().toString() }
        collectionSheetModel("collection-membership-$session") { _ ->
            CollectionMembershipViewModel(CollectionRepository(AppDatabase.getInstance(context)).asMembershipStore(), bookmarkId)
        }
    }
    val state by resolvedModel.state.collectAsStateWithLifecycle()
    CollectionMembershipSheet(
        saveKey = "$bookmarkId-$sheetInstanceId",
        state = state,
        onToggle = resolvedModel::toggle,
        onRetry = resolvedModel::retry,
        onDismiss = onDismiss,
        onNewCollection = onNewCollection,
        modifier = modifier,
        scrollState = scrollState,
        focusedCollectionId = focusedCollectionId,
        onFocusHandled = onFocusHandled,
        onCancelTransition = onCancelTransition
    )
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
    saveKey: String, state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit,
    onNewCollection: () -> Unit = {}, modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    focusedCollectionId: String? = null,
    onFocusHandled: () -> Unit = {},
    onCancelTransition: () -> Unit = {}
) {
    var closing by rememberSaveable(saveKey) { mutableStateOf(false) }
    var openCreatorAfterClose by rememberSaveable(saveKey) { mutableStateOf(false) }
    CollectionMembershipContent(
        state = state,
        onToggle = onToggle,
        onRetry = onRetry,
        onDismiss = {
            if (openCreatorAfterClose) {
                openCreatorAfterClose = false
                onCancelTransition()
            }
            closing = true
        },
        onNewCollection = {
            if (!closing && !state.loading && state.error != "load" && state.pendingIds.isEmpty()) {
                openCreatorAfterClose = true
                closing = true
                onNewCollection()
            }
        },
        onClosed = {
            if (openCreatorAfterClose) {
                openCreatorAfterClose = false
            }
            onDismiss()
        },
        modifier = modifier,
        closing = closing,
        scrollState = scrollState,
        focusedCollectionId = focusedCollectionId,
        onFocusHandled = onFocusHandled,
        onCancelTransition = {
            if (openCreatorAfterClose) {
                openCreatorAfterClose = false
                onCancelTransition()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CollectionMembershipContent(
    state: CollectionMembershipState, onToggle: (String) -> Unit,
    onRetry: () -> Unit, onDismiss: () -> Unit, onNewCollection: () -> Unit = {},
    onClosed: () -> Unit, modifier: Modifier = Modifier, closing: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    focusedCollectionId: String? = null,
    onFocusHandled: () -> Unit = {},
    onCancelTransition: () -> Unit = {}
) {
    val currentOnFocusHandled by rememberUpdatedState(onFocusHandled)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val focusRequester = remember { FocusRequester() }
    val hasMatchingRow = focusedCollectionId != null && state.rows.any { it.collection.id == focusedCollectionId }
    val controller = rememberClosingSheetController(
        closing = closing,
        blockDismiss = false,
        onRequestClose = onDismiss,
        onClosed = onClosed,
        onCancelWhileClosing = onCancelTransition
    )
    LaunchedEffect(focusedCollectionId, hasMatchingRow, closing) {
        if (!closing && focusedCollectionId != null && hasMatchingRow) {
            withFrameNanos { }
            try {
                bringIntoViewRequester.bringIntoView()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            runCatching { focusRequester.requestFocus() }
            currentOnFocusHandled()
        }
    }
    TidiloModalBottomSheet(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.SHEET).containerColor,
            shape = currentThemeSurfaces().resolve(SurfaceRole.SHEET).shape,
            onDismissRequest = controller.onDismissRequest,
            sheetGesturesEnabled = !closing,
        modifier = modifier, sheetState = controller.sheetState) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Text(stringResource(R.string.collection_assign_title),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                style = modernRaisedTextStyle(MaterialTheme.typography.titleLarge))
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) CircularProgressIndicator()
                else if (state.rows.isEmpty() && state.error == null) {
                    Text(stringResource(R.string.collection_assign_empty))
                }
                val canCreateCollection = !closing && !state.loading && state.error != "load" && state.pendingIds.isEmpty()
                Button(
                    onClick = onNewCollection,
                    enabled = canCreateCollection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.collection_new_action),
                        style = modernRaisedTextStyle(LocalTextStyle.current, canCreateCollection)
                    )
                }
                state.rows.forEach { row ->
                    key(row.collection.id) {
                        val isTarget = row.collection.id == focusedCollectionId
                        val rowModifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .then(
                                if (isTarget) {
                                    Modifier
                                        .bringIntoViewRequester(bringIntoViewRequester)
                                        .focusRequester(focusRequester)
                                } else {
                                    Modifier
                                }
                            )
                        Row(
                            rowModifier
                                .toggleable(
                                    value = row.collection.id in state.selectedIds,
                                    enabled = row.collection.id !in state.pendingIds && !closing,
                                    role = Role.Checkbox,
                                    onValueChange = { onToggle(row.collection.id) }
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
