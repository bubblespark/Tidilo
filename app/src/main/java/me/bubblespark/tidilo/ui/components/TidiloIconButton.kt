package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.theme.*

/** Material owns input and indication; shared Surface owns the optional backing plate. */
@Composable
fun TidiloIconButton(
    onClick: () -> Unit,
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val style = currentThemeSurfaces().resolve(role, state = ThemeSurfaceState(enabled = enabled))
    ResolvedTidiloSurface(style, modifier.defaultMinSize(48.dp, 48.dp)) {
        IconButton(onClick = onClick, enabled = enabled, content = content)
    }
}

@Composable
fun TidiloIconToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val style = currentThemeSurfaces().resolve(role, state = ThemeSurfaceState(enabled = enabled, selected = checked))
    ResolvedTidiloSurface(style, modifier.defaultMinSize(48.dp, 48.dp)) {
        IconToggleButton(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, content = content)
    }
}
