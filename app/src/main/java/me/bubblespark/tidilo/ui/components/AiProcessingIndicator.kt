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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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

    // Breathing pulse scale for ambient halo
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aiPulseScale"
    )

    // Breathing pulse alpha for ambient halo
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aiPulseAlpha"
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

    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center
    ) {
        // 1. Ambient outer glow halo
        Box(
            modifier = Modifier
                .size(buttonSize + 16.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = pulseAlpha
                    rotationZ = rotationAngle
                }
                .blur(14.dp)
                .background(
                    brush = Brush.sweepGradient(AiRainbowColors),
                    shape = CircleShape
                )
        )

        // 2. Button surface container
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
                // Rotating rainbow border ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotationAngle }
                        .border(
                            width = if (buttonSize > 60.dp) 3.5.dp else 2.5.dp,
                            brush = Brush.sweepGradient(AiRainbowColors),
                            shape = CircleShape
                        )
                )

                // Inner radial glow highlight
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
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
                // Title row with AI sparkling icon
                Row(
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
