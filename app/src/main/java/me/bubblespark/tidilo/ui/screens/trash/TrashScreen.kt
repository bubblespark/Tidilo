package me.bubblespark.tidilo.ui.screens.trash

import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu

import me.bubblespark.tidilo.ui.components.TidiloAlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.TrashMutationResult
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.screenActionInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val trashDate = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

@Composable
private fun trashViewModel(): TrashViewModel {
    val factory = remember { viewModelFactory { initializer {
        TrashViewModel(RepositoryTrashStore(BookmarkRepository.getInstance()), createSavedStateHandle(), AppConfig.isNsfwContentVisible)
    } } }
    return viewModel(factory = factory)
}

@Composable
fun TrashScreen(onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier, viewModel: TrashViewModel = trashViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resultText = state.actionResult?.let { result -> stringResource(when (result) {
        TrashMutationResult.Restored -> R.string.trash_restored
        TrashMutationResult.Deleted -> R.string.trash_deleted
        TrashMutationResult.DuplicateInLibrary -> R.string.trash_duplicate
        TrashMutationResult.ImportPending -> R.string.trash_import_pending
        TrashMutationResult.IdentityConflict -> R.string.trash_identity_conflict
        TrashMutationResult.Missing -> R.string.trash_missing
    }) }
    LaunchedEffect(resultText) {
        resultText?.let { snackbar.showSnackbar(it); viewModel.clearActionResult() }
    }
    Box(modifier.fillMaxSize()) {
        TrashContent(state, onNavigateBack, viewModel::requestAction,
            viewModel::dismissConfirmation, viewModel::confirmAction, viewModel::retry)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).screenActionInsets())
    }
}

@Composable
fun TrashContent(state: TrashUiState, onBack: () -> Unit,
    onRequestAction: (String, TrashAction) -> Unit, onDismissConfirmation: () -> Unit,
    onConfirm: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(), header = { TidiloPageHeader(stringResource(R.string.trash_title), onBack, raisedTitle = true) },
        modifier = modifier, bottomContentPadding = 24.dp) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.confirmation == null -> Column(
                Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.trash_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
            }
            state.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(state.rows, key = { it.item.id }, contentType = { "trash" }) { row ->
                    TrashRow(row, state.busy, zone, onRequestAction)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    state.confirmation?.let { target ->
        val row = state.rows.find { it.item.id == target.id }
        val restore = target.action == TrashAction.Restore
        val canConfirm = !state.loading && !state.busy && !state.readFailed && row != null && (!restore || !row.duplicateInLibrary)
        val style = currentThemeSurfaces().resolve(SurfaceRole.DIALOG)
        TidiloAlertDialog(onDismissRequest = { if (!state.busy) onDismissConfirmation() },
            containerColor = style.containerColor, shape = style.shape,
            title = { Text(stringResource(if (restore) R.string.trash_restore_title else R.string.trash_delete_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                row?.let { Text(it.item.title, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                Text(stringResource(if (restore) R.string.trash_restore_message else R.string.trash_delete_message))
                if (row == null) Text(stringResource(R.string.trash_missing), color = MaterialTheme.colorScheme.error)
                else if (restore && row.duplicateInLibrary) Text(stringResource(R.string.trash_duplicate), color = MaterialTheme.colorScheme.error)
                if (state.error != null) Text(stringResource(if (state.readFailed) R.string.trash_load_failed else R.string.common_save_failed),
                    color = MaterialTheme.colorScheme.error)
                if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            } },
            confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.testTag("trash_confirm")) {
                Text(stringResource(if (restore) R.string.trash_restore else R.string.common_delete),
                    color = if (canConfirm && !restore) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            } },
            dismissButton = { TextButton(onClick = onDismissConfirmation, enabled = !state.busy) { Text(stringResource(R.string.common_cancel)) } })
    }
}

@Composable
private fun TrashRow(row: TrashBookmarkRow, busy: Boolean, zone: ZoneId,
    onRequestAction: (String, TrashAction) -> Unit) {
    var menu by remember(row.item.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.item.title, color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.item.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.duplicateInLibrary) Text(stringResource(R.string.trash_duplicate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(trashDate.format(Instant.ofEpochMilli(row.item.trashedAt).atZone(zone)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy,
                modifier = Modifier.size(48.dp).testTag("trash_actions:${row.item.id}")) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.trash_more_actions), tint = MaterialTheme.colorScheme.onBackground)
            }
            val style = currentThemeSurfaces().resolve(SurfaceRole.MENU)
            TidiloDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = style.containerColor, shape = style.shape) {
                DropdownMenuItem(text = { Text(stringResource(R.string.trash_restore)) }, enabled = !busy && !row.duplicateInLibrary,
                    onClick = { menu = false; onRequestAction(row.item.id, TrashAction.Restore) })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, enabled = !busy,
                    onClick = { menu = false; onRequestAction(row.item.id, TrashAction.Delete) })
            }
        }
    }
}
