package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Rect
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
    val style = remember(containerColor, mask) { HazeBlurStyle {
        blurRadius(20.dp)
        noiseFactor(0f)
        backgroundColor(containerColor.copy(alpha = 1f))
        colorEffects(emptyList())
        fallbackColorEffect(null)
        mask?.let { mask(it) }
    } }
    Box(modifier.clip(shape)
        .maskedChromeColor(containerColor.copy(alpha = 1f), mask)
        .hazeBlur(input = input, style = style)
        .maskedChromeColor(tint, mask)
        .then(if (outline) Modifier.border(1.dp, edge, shape) else Modifier))
}

/** Color-filter the mask directly, avoiding another offscreen layer for header fading. */
private fun Modifier.maskedChromeColor(color: Color, mask: Brush?): Modifier = drawWithCache {
    val paint = Paint()
    if (mask != null) {
        mask.applyTo(size, paint, 1f)
        paint.colorFilter = ColorFilter.tint(color)
    } else paint.color = color
    onDrawBehind { drawIntoCanvas { it.drawRect(Rect(0f, 0f, size.width, size.height), paint) } }
}
