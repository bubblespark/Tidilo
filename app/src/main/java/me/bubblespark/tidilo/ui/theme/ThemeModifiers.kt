package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

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
 * If [style.emphasizeEntityChips] is true and [isEntity] is true under UNIFIED mode, containerColor may use higher prominence.
 */
fun resolveChipColors(
    style: ThemeChipStyle,
    isSubcategory: Boolean,
    isEntity: Boolean,
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
    val containerColor = palette.container(isDark, fallbackContainerColor)
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.45f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = resolvedShape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = spotColor
    )

    return if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.12f).coerceAtMost(1f),
            green = (containerColor.green * 1.12f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.12f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.92f,
            green = containerColor.green * 0.92f,
            blue = containerColor.blue * 0.92f
        )
        shadowModifier.background(
            brush = Brush.linearGradient(
                colors = listOf(lightened, containerColor, darkened),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape = resolvedShape
        )
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }
}

/**
 * Applies chip shadow and background based on [style].
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
    val containerColor = palette.container(isDark, fallbackContainerColor)
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.35f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = resolvedShape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = spotColor
    )

    return if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.10f).coerceAtMost(1f),
            green = (containerColor.green * 1.10f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.10f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.93f,
            green = containerColor.green * 0.93f,
            blue = containerColor.blue * 0.93f
        )
        shadowModifier.background(
            brush = Brush.linearGradient(
                colors = listOf(lightened, containerColor, darkened),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape = resolvedShape
        )
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }
}

/**
 * Applies floating bar shadow and background based on [style].
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
    val containerColor = palette.container(isDark, fallbackContainerColor)
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.40f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = resolvedShape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = spotColor
    )

    return if (style.useGradientFill && containerColor != Color.Unspecified) {
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
        shadowModifier.background(
            brush = Brush.linearGradient(
                colors = listOf(lightened, containerColor, darkened),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape = resolvedShape
        )
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = resolvedShape)
    } else {
        shadowModifier
    }
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
    val containerColor = palette.container(isDark, fallbackContainerColor)
    val spotColor = if (isDark) Color.Black.copy(alpha = 0.60f) else Color.Black.copy(alpha = 0.40f)

    val shadowModifier = this.shadow(
        elevation = style.depth,
        shape = shape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = spotColor
    )

    return if (style.useGradientFill && containerColor != Color.Unspecified) {
        val lightened = containerColor.copy(
            red = (containerColor.red * 1.15f).coerceAtMost(1f),
            green = (containerColor.green * 1.15f).coerceAtMost(1f),
            blue = (containerColor.blue * 1.15f).coerceAtMost(1f)
        )
        val darkened = containerColor.copy(
            red = containerColor.red * 0.90f,
            green = containerColor.green * 0.90f,
            blue = containerColor.blue * 0.90f
        )
        shadowModifier.background(
            brush = Brush.linearGradient(
                colors = listOf(lightened, containerColor, darkened),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape = shape
        )
    } else if (containerColor != Color.Unspecified) {
        shadowModifier.background(containerColor, shape = shape)
    } else {
        shadowModifier
    }
}
