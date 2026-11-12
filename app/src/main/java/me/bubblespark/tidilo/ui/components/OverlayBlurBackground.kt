package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces

/** Only captured lower sources are blurred; tint and foreground are drawn locally afterward. */
@Composable
internal fun Modifier.overlayBlurBackground(layer: OverlayBlurLayer?, shape: Shape, containerColor: Color): Modifier {
    if (layer == null) return this
    val dark = currentThemeSurfaces().isDark
    val tint = containerColor.copy(alpha = containerColor.alpha * if (dark) 0.78f else 0.82f)
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.32f else 0.24f)
    val style = remember(containerColor) { HazeBlurStyle {
        blurRadius(20.dp)
        noiseFactor(0f)
        backgroundColor(containerColor.copy(alpha = 1f))
        colorEffects(emptyList())
        fallbackColorEffect(null)
    } }
    return then(layer.sourceModifier).clip(shape)
        .background(containerColor.copy(alpha = 1f))
        .hazeBlur(input = layer.input, style = style)
        .drawWithContent { drawRect(tint); drawContent() }
        .border(1.dp, outline, shape)
}
