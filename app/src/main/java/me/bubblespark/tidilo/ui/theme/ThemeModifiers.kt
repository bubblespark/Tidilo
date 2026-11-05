package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp

// Hoisted bevel border gradient brushes for zero-allocation drawing across recompositions
private val CARD_BEVEL_BORDER_DARK = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.15f),
    1.0f to Color.Black.copy(alpha = 0.35f)
)
private val CARD_BEVEL_BORDER_LIGHT = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.90f),
    1.0f to Color.Black.copy(alpha = 0.08f)
)

private val CHIP_BEVEL_BORDER_DARK = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.16f),
    1.0f to Color.Black.copy(alpha = 0.40f)
)
private val CHIP_BEVEL_BORDER_LIGHT = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.90f),
    1.0f to Color.Black.copy(alpha = 0.08f)
)

private val BAR_BEVEL_BORDER_DARK = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.12f),
    1.0f to Color.Black.copy(alpha = 0.30f)
)
private val BAR_BEVEL_BORDER_LIGHT = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.85f),
    1.0f to Color.Black.copy(alpha = 0.06f)
)

private val PLATE_BEVEL_BORDER_DARK = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.16f),
    1.0f to Color.Black.copy(alpha = 0.40f)
)
private val PLATE_BEVEL_BORDER_LIGHT = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.90f),
    1.0f to Color.Black.copy(alpha = 0.08f)
)

/**
 * Fills the modifier bounds with a three-stop vertical gradient.
 * The [Brush] and its color list are built inside [drawWithCache] so they are only
 * rebuilt when the size or the resolved colors change, not on every recomposition.
 */
private fun Modifier.verticalGradientFill(shape: Shape, top: Color, middle: Color, bottom: Color): Modifier =
    drawWithCache {
        val brush = Brush.verticalGradient(listOf(top, middle, bottom))
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawOutline(outline, brush) }
    }

/**
 * Resolved colors for a tag chip based on category mode and hierarchy.
 */
data class ChipResolvedColors(
    val containerColor: Color,
    val contentColor: Color
)

/**
 * Resolves container and content colors for chips based on [style].
 * When [style.colorMode] is [ChipColorMode.PER_CATEGORY], uses [categoryContainerColor] and [categoryContentColor].
 * When [ChipColorMode.UNIFIED], uses [fallbackContainerColor] and [fallbackContentColor].
 */
fun resolveChipColors(
    style: ThemeChipStyle,
    categoryContainerColor: Color,
    categoryContentColor: Color,
    fallbackContainerColor: Color,
    fallbackContentColor: Color
): ChipResolvedColors {
    return when (style.colorMode) {
        ChipColorMode.PER_CATEGORY -> ChipResolvedColors(
            containerColor = categoryContainerColor,
            contentColor = categoryContentColor
        )
        ChipColorMode.UNIFIED -> ChipResolvedColors(
            containerColor = fallbackContainerColor,
            contentColor = fallbackContentColor
        )
    }
}

/**
 * Applies directional shadow and surface styling for cards based on [style].
 * Returns `this` directly with zero allocations when [style.depth] <= 0.dp.
 */
fun Modifier.themeCardSurface(
    style: ThemeCardStyle,
    palette: ThemeSurfacePalette,
    isDark: Boolean,
    fallbackShape: Shape,
    fallbackContainerColor: Color = Color.Unspecified
): Modifier {
    if (style.depth <= 0.dp) return this
    val resolvedShape = style.shape ?: fallbackShape
    val containerColor = if (fallbackContainerColor != Color.Unspecified) {
        fallbackContainerColor
    } else {
        palette.container(isDark, if (isDark) Color(0xFF22262B) else Color(0xFFFFFFFF))
    }

    // Soft expanded ambient shadow for floating card elevation
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.10f)
    val ambientColor = if (isDark) Color.Black.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.05f)

    val shadowModifier = this.shadow(
        elevation = style.depth * 2,
        shape = resolvedShape,
        clip = false,
        ambientColor = ambientColor,
        spotColor = spotColor
    )

    val bgModifier = if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = if (isDark) {
            containerColor.copy(
                red = (containerColor.red * 1.18f).coerceAtMost(1f),
                green = (containerColor.green * 1.18f).coerceAtMost(1f),
                blue = (containerColor.blue * 1.18f).coerceAtMost(1f)
            )
        } else {
            Color.White
        }
        val darkened = if (isDark) {
            containerColor.copy(
                red = containerColor.red * 0.85f,
                green = containerColor.green * 0.85f,
                blue = containerColor.blue * 0.85f
            )
        } else {
            containerColor.copy(
                red = (containerColor.red * 0.93f),
                green = (containerColor.green * 0.94f),
                blue = (containerColor.blue * 0.95f)
            )
        }
        shadowModifier.verticalGradientFill(resolvedShape, lightened, containerColor, darkened)
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }

    val cardBevelBorder = if (isDark) CARD_BEVEL_BORDER_DARK else CARD_BEVEL_BORDER_LIGHT
    return bgModifier.border(width = 1.dp, brush = cardBevelBorder, shape = resolvedShape)
}

/**
 * Applies chip shadow, matte background, and tactile extruded bevel border based on [style].
 * Returns `this` directly when [style.depth] <= 0.dp.
 */
fun Modifier.themeChipSurface(
    style: ThemeChipStyle,
    palette: ThemeSurfacePalette,
    isDark: Boolean,
    fallbackShape: Shape,
    fallbackContainerColor: Color = Color.Unspecified
): Modifier {
    if (style.depth <= 0.dp) return this
    val resolvedShape = style.shape ?: fallbackShape
    val containerColor = if (fallbackContainerColor != Color.Unspecified) {
        fallbackContainerColor
    } else {
        palette.container(isDark, if (isDark) Color(0xFF161920) else Color(0xFFFFFFFF))
    }

    val spotColor = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.12f)
    val ambientColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.05f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = resolvedShape,
        clip = false,
        ambientColor = ambientColor,
        spotColor = spotColor
    )

    val bgModifier = if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.08f).coerceAtMost(1f),
            green = (containerColor.green * 1.08f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.08f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.94f,
            green = containerColor.green * 0.94f,
            blue = containerColor.blue * 0.94f
        )
        shadowModifier.verticalGradientFill(resolvedShape, lightened, containerColor, darkened)
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }

    val chipBevelBorder = if (isDark) CHIP_BEVEL_BORDER_DARK else CHIP_BEVEL_BORDER_LIGHT
    return bgModifier.border(width = 1.dp, brush = chipBevelBorder, shape = resolvedShape)
}

/**
 * Applies floating bar shadow, background, and subtle bevel based on [style].
 * Returns `this` directly when [style.depth] <= 0.dp.
 */
fun Modifier.themeFloatingBarSurface(
    style: ThemeFloatingBarStyle,
    palette: ThemeSurfacePalette,
    isDark: Boolean,
    fallbackShape: Shape,
    fallbackContainerColor: Color = Color.Unspecified
): Modifier {
    if (style.depth <= 0.dp) return this
    val resolvedShape = fallbackShape
    val containerColor = if (fallbackContainerColor != Color.Unspecified) {
        fallbackContainerColor
    } else {
        palette.container(isDark, if (isDark) Color(0xFF22262B) else Color(0xFFFFFFFF))
    }
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.10f)
    val ambientColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.05f)

    val shadowModifier = this.shadow(
        elevation = style.depth * 2,
        shape = resolvedShape,
        clip = false,
        ambientColor = ambientColor,
        spotColor = spotColor
    )

    val bgModifier = if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.06f).coerceAtMost(1f),
            green = (containerColor.green * 1.06f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.06f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.95f,
            green = containerColor.green * 0.95f,
            blue = containerColor.blue * 0.95f
        )
        shadowModifier.verticalGradientFill(resolvedShape, lightened, containerColor, darkened)
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }

    val barBevelBorder = if (isDark) BAR_BEVEL_BORDER_DARK else BAR_BEVEL_BORDER_LIGHT
    return bgModifier.border(width = 1.dp, brush = barBevelBorder, shape = resolvedShape)
}

/**
 * Applies icon button plate styling.
 * Returns `this` directly when [style.depth] <= 0.dp or plate is not [IconButtonPlateStyle.TACTILE_CIRCULAR_3D].
 */
fun Modifier.themeIconButtonSurface(
    plate: IconButtonPlateStyle,
    style: ThemeIconButtonStyle,
    palette: ThemeSurfacePalette,
    isDark: Boolean,
    shape: Shape = CircleShape,
    fallbackContainerColor: Color = Color.Unspecified
): Modifier {
    if (style.depth <= 0.dp || plate != IconButtonPlateStyle.TACTILE_CIRCULAR_3D) return this
    val containerColor = if (fallbackContainerColor != Color.Unspecified) {
        fallbackContainerColor
    } else {
        palette.container(isDark, if (isDark) Color(0xFF22262B) else Color(0xFFFFFFFF))
    }
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.12f)
    val ambientColor = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.05f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = shape,
        clip = false,
        ambientColor = ambientColor,
        spotColor = spotColor
    )

    val bgModifier = if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.10f).coerceAtMost(1f),
            green = (containerColor.green * 1.10f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.10f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.92f,
            green = containerColor.green * 0.92f,
            blue = containerColor.blue * 0.92f
        )
        shadowModifier.verticalGradientFill(shape, lightened, containerColor, darkened)
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = shape)
    } else {
        shadowModifier
    }

    val plateBevelBorder = if (isDark) PLATE_BEVEL_BORDER_DARK else PLATE_BEVEL_BORDER_LIGHT
    return bgModifier.border(width = 1.dp, brush = plateBevelBorder, shape = shape)
}
