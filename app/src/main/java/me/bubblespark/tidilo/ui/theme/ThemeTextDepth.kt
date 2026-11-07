package me.bubblespark.tidilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme

@Composable
internal fun modernRaisedTextStyle(base: TextStyle, enabled: Boolean = true): TextStyle {
    val surfaces = currentThemeSurfaces()
    val depthPx = with(LocalDensity.current) { 1.dp.toPx() }
    return resolveRaisedTextStyle(base, surfaces.preset.theme, surfaces.isDark, enabled, depthPx)
}

internal fun resolveRaisedTextStyle(
    base: TextStyle,
    theme: AppTheme,
    dark: Boolean,
    enabled: Boolean,
    depthPx: Float
): TextStyle {
    if (theme != AppTheme.MODERN || !enabled) return base
    val color = if (dark) Color.Black.copy(alpha = 0.30f) else Color(0xFF513B19).copy(alpha = 0.20f)
    return base.copy(shadow = Shadow(color, Offset(0f, depthPx), depthPx))
}
