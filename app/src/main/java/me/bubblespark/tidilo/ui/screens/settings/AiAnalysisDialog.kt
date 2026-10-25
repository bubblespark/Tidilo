package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode

@Composable
internal fun AiAnalysisDialog(
    mode: AiAnalysisMode,
    onSelect: (AiAnalysisMode) -> Unit,
    onDismiss: () -> Unit,
    checkAvailable: suspend () -> Boolean
) {
    var available by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { available = checkAvailable() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ai_analysis_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AiAnalysisMode.entries.forEach { choice ->
                    val enabled = choice == AiAnalysisMode.DISABLE || available == true
                    Row(Modifier.fillMaxWidth().selectable(selected = mode == choice, enabled = enabled,
                        role = Role.RadioButton, onClick = { onSelect(choice) }).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == choice, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (choice == AiAnalysisMode.AICORE) R.string.settings_ai_analysis_aicore
                            else R.string.settings_ai_analysis_disable),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
                    }
                }
                if (available != true) Text(stringResource(if (available == null) R.string.settings_ai_analysis_checking
                    else R.string.settings_ai_analysis_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = if (available == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        shape = RoundedCornerShape(24.dp)
    )
}
