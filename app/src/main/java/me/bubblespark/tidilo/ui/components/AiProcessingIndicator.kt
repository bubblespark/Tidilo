package me.bubblespark.tidilo.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.AiProcessingProgress

/**
 * Aurora / AI Chromatic palette for the rotating sweep gradient and ambient halo.
 */
val AiRainbowColors = listOf(
    Color(0xFF8B5CF6), // Purple / Violet
    Color(0xFF3B82F6), // Blue
    Color(0xFF06B6D4), // Cyan
    Color(0xFF10B981), // Emerald
    Color(0xFFF59E0B), // Warm Amber
    Color(0xFFEC4899), // Pink
    Color(0xFF8B5CF6)  // Seamless loop closure
)

/**
 * Floating AI Processing Status Indicator button with rotating rainbow sweep gradient,
 * ambient aura glow, and synchronized breathing pulse.
 */
@Composable
fun AiProcessingIndicatorButton(
    progress: AiProcessingProgress,
    buttonSize: Dp,
    iconSize: Dp,
    buttonElevation: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "aiRainbowTransition")

    // Continuous 360-degree sweep gradient rotation
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aiRainbowRotation"
    )

    // Subtle scale breathing for center AI icon
    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aiIconPulse"
    )

    val rainbowSweepBrush = remember { Brush.sweepGradient(AiRainbowColors) }
    val auroraFillColors = remember { AiRainbowColors.map { it.copy(alpha = 0.22f) } }
    val auroraFillBrush = remember { Brush.sweepGradient(auroraFillColors) }

    // Ambient glow pulse (breathing intensity synchronized with AI processing)
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.60f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aiGlowPulse"
    )

    Box(
        modifier = modifier
            .size(buttonSize)
            .drawBehind {
                // Idea 1: Ambient Aura Glow
                // Mathematical circular concentric sweeps radiating outward, strictly circular
                // with no rectangular artifacts, rotated in lockstep with the outer ring.
                val radius = size.minDimension / 2f
                val strokeOuter = if (buttonSize > 60.dp) 7.dp.toPx() else 5.dp.toPx()
                val strokeMid = if (buttonSize > 60.dp) 4.5.dp.toPx() else 3.5.dp.toPx()
                val strokeInner = if (buttonSize > 60.dp) 3.dp.toPx() else 2.dp.toPx()

                val offsetOuter = if (buttonSize > 60.dp) 6.dp.toPx() else 4.5.dp.toPx()
                val offsetMid = if (buttonSize > 60.dp) 3.dp.toPx() else 2.2.dp.toPx()
                val offsetInner = if (buttonSize > 60.dp) 1.dp.toPx() else 0.8.dp.toPx()

                rotate(rotationAngle) {
                    // Outer aura layer 3 (softest ambient halo)
                    drawCircle(
                        brush = rainbowSweepBrush,
                        radius = radius + offsetOuter,
                        style = Stroke(width = strokeOuter),
                        alpha = 0.12f * glowPulse
                    )
                    // Outer aura layer 2 (mid halo)
                    drawCircle(
                        brush = rainbowSweepBrush,
                        radius = radius + offsetMid,
                        style = Stroke(width = strokeMid),
                        alpha = 0.24f * glowPulse
                    )
                    // Outer aura layer 1 (closest rim glow)
                    drawCircle(
                        brush = rainbowSweepBrush,
                        radius = radius + offsetInner,
                        style = Stroke(width = strokeInner),
                        alpha = 0.40f * glowPulse
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Button surface container
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.primary,
            shadowElevation = buttonElevation
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = MaterialTheme.colorScheme.primary, bounded = true),
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Synchronized rotating elements: Rainbow border + Idea 3: Inner Aurora Core
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotationAngle }
                ) {
                    // Idea 3: Inner Aurora Core (soft swirling rainbow gradient inside)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(auroraFillBrush)
                    )

                    // Rotating rainbow border ring
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = if (buttonSize > 60.dp) 3.5.dp else 2.5.dp,
                                brush = rainbowSweepBrush,
                                shape = CircleShape
                            )
                    )
                }

                // Inner radial depth spotlight (highlights the center AI sparkle icon)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Center AI sparkle icon
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = stringResource(R.string.bottom_bar_ai_processing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            scaleX = iconPulse
                            scaleY = iconPulse
                        }
                )
            }
        }
    }
}

/**
 * Elevated speech bubble floating directly above the AI processing button,
 * featuring progress status, active task title, progress indicator, and tap navigation.
 */
@Composable
fun AiProcessingSpeechBubble(
    progress: AiProcessingProgress,
    buttonSize: Dp,
    onNavigateToImport: () -> Unit,
    onDismiss: () -> Unit,
    onCancelProcessing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val bubbleWidth = 270.dp
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Column(
        modifier = modifier.width(bubbleWidth)
    ) {
        // Main bubble card body
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = surfaceColor,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(AiRainbowColors.map { it.copy(alpha = 0.55f) })
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = MaterialTheme.colorScheme.primary),
                    onClick = {
                        onNavigateToImport()
                        onDismiss()
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Title row with AI sparkling icon and Cancel '×' button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )

                        val titleText = if (progress.totalCount > 0) {
                            stringResource(
                                R.string.ai_indicator_progress_format,
                                (progress.completedCount + 1).coerceAtMost(progress.totalCount),
                                progress.totalCount
                            )
                        } else {
                            stringResource(R.string.ai_indicator_processing_title)
                        }

                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Cancel '×' button (disabled and grayed out once clicked while remaining task finishes)
                    val isCancelling = progress.isCancelPending
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        IconButton(
                            onClick = onCancelProcessing,
                            enabled = !isCancelling,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.ai_indicator_cancel_button),
                                tint = if (!isCancelling) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Currently processing web page title / URL
                val currentTitle = progress.currentTaskTitle
                if (!currentTitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Progress bar
                if (progress.totalCount > 0) {
                    val progressRatio by animateFloatAsState(
                        targetValue = ((progress.completedCount + 1).toFloat() / progress.totalCount.toFloat()).coerceIn(0f, 1f),
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "aiBubbleProgressRatio"
                    )
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action hint row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_indicator_tap_to_view),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // Downward pointing arrow tail centered above the button
        Canvas(
            modifier = Modifier
                .padding(start = (buttonSize / 2) - 7.dp)
                .size(width = 14.dp, height = 8.dp)
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width / 2f, size.height)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path = path, color = surfaceColor)
            drawLine(
                color = borderColor,
                start = Offset(0f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 2f
            )
            drawLine(
                color = borderColor,
                start = Offset(size.width / 2f, size.height),
                end = Offset(size.width, 0f),
                strokeWidth = 2f
            )
        }
    }
}
