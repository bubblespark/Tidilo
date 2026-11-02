package me.bubblespark.tidilo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.theme.LocalAppThemeStyle

/**
 * Root-level centralized background layer for Tidilo.
 *
 * Renders the active theme's background layer while remaining stationary during
 * screen-to-screen slide transitions. Dynamic ambient lighting is rendered centrally
 * here so it does not slide during navigation.
 */
@Composable
fun TidiloBackground(
    currentTheme: AppTheme,
    modifier: Modifier = Modifier,
    isDestinationAllowed: Boolean = true
) {
    val themeStyle = LocalAppThemeStyle.current
    val lightingState = LocalAmbientLighting.current
    val rawColor = if (isDestinationAllowed) lightingState.effectiveActiveColor else null
    val targetColor = if (rawColor != null && rawColor != Color.Transparent) {
        themeStyle.resolveColor(MaterialTheme.colorScheme, rawColor)
    } else {
        Color.Transparent
    }
    val animatedColor = animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(350, easing = EaseInOutCubic),
        label = "ambientLightingColor"
    )

    when (currentTheme) {
        AppTheme.DEFAULT -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .drawWithCache {
                        val currentColor = animatedColor.value
                        if (currentColor.alpha > 0.001f) {
                            val ambientBeam = Brush.linearGradient(
                                colorStops = arrayOf(
                                    0.00f to currentColor.copy(alpha = currentColor.alpha * 0.32f),
                                    0.22f to currentColor.copy(alpha = currentColor.alpha * 0.22f),
                                    0.45f to currentColor.copy(alpha = currentColor.alpha * 0.13f),
                                    0.68f to currentColor.copy(alpha = currentColor.alpha * 0.05f),
                                    0.84f to currentColor.copy(alpha = currentColor.alpha * 0.015f),
                                    1.00f to Color.Transparent
                                ),
                                start = Offset(size.width * 1.05f, -size.height * 0.02f),
                                end = Offset(-size.width * 0.15f, size.height * 0.90f)
                            )
                            val cornerGlow = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.00f to currentColor.copy(alpha = currentColor.alpha * 0.22f),
                                    0.30f to currentColor.copy(alpha = currentColor.alpha * 0.13f),
                                    0.60f to currentColor.copy(alpha = currentColor.alpha * 0.05f),
                                    0.82f to currentColor.copy(alpha = currentColor.alpha * 0.012f),
                                    1.00f to Color.Transparent
                                ),
                                center = Offset(size.width, 0f),
                                radius = size.height * 0.72f
                            )
                            onDrawBehind {
                                drawRect(brush = ambientBeam)
                                drawRect(brush = cornerGlow)
                            }
                        } else {
                            onDrawBehind {}
                        }
                    }
            )
        }
    }
}
