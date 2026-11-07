package me.bubblespark.tidilo.ui.screens.importscreen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.theme.*

@Composable
fun ImportUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var urlText by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val isValid = remember(urlText) { ImportViewModel.isValidHttpUrl(urlText) }
    val isDirty = urlText.isNotBlank()

    AlertDialog(
            containerColor = currentThemeSurfaces().resolve(SurfaceRole.DIALOG).containerColor,
        onDismissRequest = onDismiss,
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
            Column {
                Text(
                    text = stringResource(R.string.import_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://example.com/...") },
                    singleLine = true,
                    isError = isDirty && !isValid,
                    supportingText = {
                        if (isDirty && !isValid) {
                            Text(
                                text = stringResource(R.string.import_dialog_error_invalid),
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(text = stringResource(R.string.import_dialog_supporting_text))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!submitted && isValid) { submitted = true; onConfirm(urlText) } },
                enabled = isValid && !submitted
            ) {
                Text(
                    text = stringResource(R.string.import_dialog_confirm),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
