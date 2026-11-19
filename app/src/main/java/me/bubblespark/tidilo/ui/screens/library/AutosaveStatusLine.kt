package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.R

private const val SAVED_VISIBLE_MILLIS = 2_000L

/**
 * Unobtrusive autosave feedback below a directly edited field. "Saved" fades out after a short pause;
 * a failure stays visible with Retry. Changes are announced politely to accessibility services.
 */
@Composable
internal fun AutosaveStatusLine(
    status: AutosaveStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    invalidText: String? = null
) {
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        savedVisible = status == AutosaveStatus.Saved
        if (savedVisible) {
            delay(SAVED_VISIBLE_MILLIS)
            savedVisible = false
        }
    }
    val text = when (status) {
        AutosaveStatus.Saving -> stringResource(R.string.common_saving)
        AutosaveStatus.Saved -> if (savedVisible) stringResource(R.string.common_saved) else null
        AutosaveStatus.Failed -> stringResource(R.string.common_save_failed)
        AutosaveStatus.Invalid -> invalidText
        AutosaveStatus.Idle, AutosaveStatus.Pending -> null
    }
    val isError = status == AutosaveStatus.Failed || status == AutosaveStatus.Invalid
    Row(
        modifier = modifier.heightIn(min = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text != null) {
            // Weighted without filling, so wrapped text at large font scales never pushes Retry off-screen.
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (status == AutosaveStatus.Failed) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.common_retry))
            }
        }
    }
}
