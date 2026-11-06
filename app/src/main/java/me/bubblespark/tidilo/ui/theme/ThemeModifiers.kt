package me.bubblespark.tidilo.ui.theme

import androidx.compose.ui.graphics.Color

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
