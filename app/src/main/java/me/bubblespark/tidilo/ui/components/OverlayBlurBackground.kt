package me.bubblespark.tidilo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
internal fun Modifier.overlayBlurBackground(
    layer: OverlayBlurLayer?,
    shape: Shape,
    containerColor: Color,
    blurBackground: Boolean = true,
): Modifier {
    if (layer == null) return this
    val dark = currentThemeSurfaces().isDark
    val tint = containerColor.copy(alpha = containerColor.alpha * if (dark) 0.78f else 0.82f)
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.32f else 0.24f)
    val blurAlpha = remember { Animatable(if (blurBackground) 1f else 0f) }
    LaunchedEffect(blurBackground) {
        if (!blurBackground) {
            blurAlpha.snapTo(0f)
        } else if (blurAlpha.value < 1f) {
            blurAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }
    val style = remember(containerColor) { HazeBlurStyle {
        // Haze observes this state in its style resolver without recomposing the dialog.
        alpha(blurAlpha.value)
        blurRadius(20.dp)
        noiseFactor(0f)
        backgroundColor(containerColor.copy(alpha = 1f))
        colorEffects(emptyList())
        fallbackColorEffect(null)
    } }
    return then(layer.sourceModifier).clip(shape)
        .background(containerColor.copy(alpha = 1f))
        .then(if (blurBackground) Modifier.hazeBlur(input = layer.input, style = style) else Modifier)
        .drawWithContent { drawRect(tint); drawContent() }
        .border(1.dp, outline, shape)
}
