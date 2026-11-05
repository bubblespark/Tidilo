package me.bubblespark.tidilo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModifiersTest {
    @Test
    fun resolveChipColors_perCategory_returnsCategoryColors() {
        val style = ThemeChipStyle(colorMode = ChipColorMode.PER_CATEGORY)
        val catContainer = Color(0xFF112233)
        val catContent = Color(0xFF445566)
        val fallbackContainer = Color(0xFF778899)
        val fallbackContent = Color(0xFFAABBCC)

        val resolved = resolveChipColors(
            style = style,
            categoryContainerColor = catContainer,
            categoryContentColor = catContent,
            fallbackContainerColor = fallbackContainer,
            fallbackContentColor = fallbackContent
        )

        assertEquals(catContainer, resolved.containerColor)
        assertEquals(catContent, resolved.contentColor)
    }

    @Test
    fun resolveChipColors_unified_returnsFallbackColors() {
        val style = ThemeChipStyle(colorMode = ChipColorMode.UNIFIED)
        val catContainer = Color(0xFF112233)
        val catContent = Color(0xFF445566)
        val fallbackContainer = Color(0xFF778899)
        val fallbackContent = Color(0xFFAABBCC)

        val resolved = resolveChipColors(
            style = style,
            categoryContainerColor = catContainer,
            categoryContentColor = catContent,
            fallbackContainerColor = fallbackContainer,
            fallbackContentColor = fallbackContent
        )

        assertEquals(fallbackContainer, resolved.containerColor)
        assertEquals(fallbackContent, resolved.contentColor)
    }
}
