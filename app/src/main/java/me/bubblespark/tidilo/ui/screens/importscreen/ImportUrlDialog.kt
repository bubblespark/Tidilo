package me.bubblespark.tidilo.ui.screens.importscreen

import me.bubblespark.tidilo.ui.components.TidiloAlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.ImportOutcome
import me.bubblespark.tidilo.ui.theme.*

@Composable
fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    state: ManualImportState? = null,
    onRetry: () -> Unit = {},
    onOpenSaved: () -> Unit = {}
) {
    var urlText by rememberSaveable { mutableStateOf("") }
    var submitted by remember(state) { mutableStateOf(false) }
    val resolvedUrl = remember(urlText) { ImportViewModel.resolveManualImportUrl(urlText) }
    val isDirty = urlText.isNotBlank()
    val isSaving = state == ManualImportState.Saving
    val outcome = (state as? ManualImportState.Finished)?.outcome
    val failure = outcome as? ImportOutcome.Failed
    val canRetry = failure?.retryTaskId != null
    val canOpen = outcome is ImportOutcome.BookmarkSaved || outcome is ImportOutcome.SavedDuplicate
    val isEditing = !isSaving && (outcome == null || (failure != null && !canRetry))
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val statusMessage = when (outcome) {
        is ImportOutcome.BookmarkSaved -> R.string.import_dialog_saved
        is ImportOutcome.SavedDuplicate -> R.string.import_dialog_duplicate
        is ImportOutcome.AlreadySaving -> R.string.import_dialog_already_saving
        is ImportOutcome.Failed -> R.string.import_dialog_failed
        null -> if (isSaving) R.string.import_dialog_saving else null
    }

    fun submit() {
        if (submitted || !isEditing) return
        val url = resolvedUrl ?: return
        submitted = true
        keyboard?.hide()
        onConfirm(url)
    }

    TidiloAlertDialog(
        containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isSaving, dismissOnClickOutside = !isSaving),
        shape = themeCorner(ThemeCorner.SHEET),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AddLink,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.import_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                statusMessage?.let {
                    Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (isSaving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (!isEditing) {
                    Text(urlText.ifBlank { outcome?.url.orEmpty() }, style = MaterialTheme.typography.bodySmall)
                }
                if (isEditing) {
                    if (outcome == null) Text(
                        text = stringResource(R.string.import_dialog_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text(stringResource(R.string.import_dialog_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        isError = isDirty && resolvedUrl == null,
                        supportingText = {
                            if (isDirty && resolvedUrl == null) {
                                Text(
                                    text = stringResource(R.string.import_dialog_error_invalid),
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else if (resolvedUrl != null && resolvedUrl != urlText.trim()) {
                                Text(stringResource(R.string.import_dialog_resolved_url, resolvedUrl))
                            } else {
                                Text(text = stringResource(R.string.import_dialog_supporting_text))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        clipboardScope.launch {
                            val pasted = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()
                            if (pasted != null && !submitted) urlText = pasted
                        }
                    }) {
                        Text(stringResource(R.string.import_dialog_paste))
                    }
                }
            }
        },
        confirmButton = {
            when {
                canOpen -> TextButton(onClick = onOpenSaved) { Text(stringResource(R.string.import_open_saved_page)) }
                canRetry -> TextButton(onClick = {
                    if (!submitted) {
                        submitted = true
                        onRetry()
                    }
                }, enabled = !submitted) { Text(stringResource(R.string.common_retry)) }
                isEditing -> TextButton(onClick = ::submit, enabled = resolvedUrl != null && !submitted) {
                    Text(stringResource(R.string.import_dialog_confirm), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!isSaving) TextButton(onClick = onDismiss) {
                Text(stringResource(if (outcome == null) R.string.common_cancel else R.string.common_close))
            }
        }
    )
}
