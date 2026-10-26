package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 14-stop fine-grained Hermite / Smootherstep (6t^5 - 15t^4 + 10t^3) decay curve
// Both first and second derivatives are zero at start and end to eliminate Mach bands
private val SMOOTHERSTEP_CURVE = listOf(
    0.00f to 1.000f,
    0.08f to 0.994f,
    0.16f to 0.970f,
    0.25f to 0.916f,
    0.35f to 0.835f,
    0.45f to 0.720f,
    0.55f to 0.582f,
    0.65f to 0.437f,
    0.75f to 0.297f,
    0.84f to 0.178f,
    0.91f to 0.093f,
    0.96f to 0.035f,
    0.99f to 0.008f,
    1.00f to 0.000f
)

/**
 * Translucent frosted header container with organic, fine-grained gradient dissolution.
 *
 * Eliminates harsh boundary lines in Edge-to-Edge screens by applying a neutral
 * translucent frosted background (default alpha 0.85f) and a 14-stop smootherstep
 * attenuation curve that begins right at the bottom edge of the header text and melts
 * seamlessly into the underlying screen content.
 */
@Composable
fun FrostedHeaderContainer(
    modifier: Modifier = Modifier,
    contentAlpha: Float = 0.85f,
    fadeHeight: Dp = HeaderDefaults.FadeHeight,
    decayOverlap: Dp = HeaderDefaults.DecayOverlap,
    content: @Composable () -> Unit
) {
    val bgColor = MaterialTheme.colorScheme.background

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawWithCache {
                val totalHeight = size.height
                if (totalHeight <= 0f) {
                    onDrawBehind {}
                } else {
                    val fadeHeightPx = fadeHeight.toPx()
                    val decayOverlapPx = decayOverlap.toPx()
                    val contentHeight = totalHeight - fadeHeightPx

                    // Point where attenuation begins (grazing / touching the header text)
                    val decayStartY = (contentHeight - decayOverlapPx).coerceAtLeast(0f)
                    val decayDistance = totalHeight - decayStartY

                    if (decayDistance > 0f) {
                        val startProgress = (decayStartY / totalHeight).coerceIn(0f, 1f)
                        val remainingProgress = 1f - startProgress

                        val bgStops = ArrayList<Pair<Float, Color>>(SMOOTHERSTEP_CURVE.size + 1)
                        if (startProgress > 0.001f) {
                            bgStops.add(0.0f to bgColor.copy(alpha = contentAlpha))
                        }

                        for ((t, factor) in SMOOTHERSTEP_CURVE) {
                            val p = (startProgress + t * remainingProgress).coerceIn(0f, 1f)
                            bgStops.add(p to bgColor.copy(alpha = contentAlpha * factor))
                        }

                        // Theme background frost with fine-grained smootherstep decay
                        val bgBrush = Brush.verticalGradient(
                            colorStops = bgStops.toTypedArray(),
                            startY = 0f,
                            endY = totalHeight
                        )
                        onDrawBehind {
                            drawRect(brush = bgBrush)
                        }
                    } else {
                        onDrawBehind {}
                    }
                }
            }
    ) {
        // Content container
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }

        // Dissolve zone spacer allowing smooth attenuation without intercepting touches
        Spacer(modifier = Modifier.height(fadeHeight))
    }
}
