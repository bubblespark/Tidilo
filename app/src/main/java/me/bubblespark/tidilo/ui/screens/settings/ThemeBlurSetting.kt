package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.theme.OverlayBlurSetting

@Composable
fun ThemeBlurSetting(setting: OverlayBlurSetting, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val currentSetting by rememberUpdatedState(setting)
    Row(modifier.fillMaxWidth().toggleable(setting.enabled, enabled = setting.userEditable,
        role = Role.Switch, onValueChange = { if (currentSetting.userEditable) onCheckedChange(it) })
        .padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_blur_effects), style = MaterialTheme.typography.bodyLarge)
            if (!setting.userEditable) Text(stringResource(if (setting.enabled) R.string.settings_blur_fixed_on
                else R.string.settings_blur_fixed_off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = setting.enabled, onCheckedChange = null, enabled = setting.userEditable)
    }
}
