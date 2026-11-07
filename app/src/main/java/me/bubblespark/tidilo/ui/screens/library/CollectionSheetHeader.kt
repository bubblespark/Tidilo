package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.theme.modernRaisedTextStyle

@Composable
internal fun CollectionSheetHeader(
    title: String, actionLabel: String, enabled: Boolean, onSave: () -> Unit,
    colors: ButtonColors, modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = modernRaisedTextStyle(MaterialTheme.typography.titleLarge),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(onClick = onSave, enabled = enabled, colors = colors) {
            Text(actionLabel, style = modernRaisedTextStyle(LocalTextStyle.current, enabled))
        }
    }
}
