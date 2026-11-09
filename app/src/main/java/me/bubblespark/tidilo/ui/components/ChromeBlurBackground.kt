package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces

/** Draws only the background; masks attenuate fallback, captured blur and tint together. */
@Composable
internal fun ChromeBlurBackground(
    shape: Shape,
    containerColor: Color,
    modifier: Modifier = Modifier,
    mask: Brush? = null,
    outline: Boolean = true
) {
    val input = rememberScreenBackdropInput() ?: return
    val dark = currentThemeSurfaces().isDark
    val tint = containerColor.copy(alpha = containerColor.alpha * if (dark) .78f else .82f)
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) .32f else .24f)
    val style = remember(containerColor) { HazeBlurStyle {
        blurRadius(20.dp)
        noiseFactor(0f)
        backgroundColor(containerColor.copy(alpha = 1f))
        colorEffects(emptyList())
        fallbackColorEffect(null)
    } }
    // Mask the completed background once, preserving the header's opacity curve.
    val clipped = if (mask == null) modifier.clip(shape) else modifier.graphicsLayer {
        this.shape = shape
        clip = true
        compositingStrategy = CompositingStrategy.Offscreen
    }.drawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
    Box(clipped
        .background(containerColor.copy(alpha = 1f))
        .hazeBlur(input = input, style = style)
        .background(tint)
        .then(if (outline) Modifier.border(1.dp, edge, shape) else Modifier))
}
