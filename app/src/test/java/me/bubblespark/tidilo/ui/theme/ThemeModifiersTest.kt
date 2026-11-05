package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeModifiersTest {

    private val testShape = RoundedCornerShape(12.dp)
    private val testPalette = ThemeSurfacePalette(
        darkContainerColor = Color(0xFF222222),
        lightContainerColor = Color(0xFFEEEEEE)
    )

    @Test
    fun themeCardSurface_returnsSameModifier_whenDepthZero() {
        val initial = Modifier
        val resultZero = initial.themeCardSurface(
            style = ThemeCardStyle(depth = 0.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultZero)

        val resultNegative = initial.themeCardSurface(
            style = ThemeCardStyle(depth = (-1).dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultNegative)
    }

    @Test
    fun themeChipSurface_returnsSameModifier_whenDepthZero() {
        val initial = Modifier
        val resultZero = initial.themeChipSurface(
            style = ThemeChipStyle(depth = 0.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultZero)

        val resultNegative = initial.themeChipSurface(
            style = ThemeChipStyle(depth = (-2).dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultNegative)
    }

    @Test
    fun themeFloatingBarSurface_returnsSameModifier_whenDepthZero() {
        val initial = Modifier
        val resultZero = initial.themeFloatingBarSurface(
            style = ThemeFloatingBarStyle(depth = 0.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultZero)

        val resultNegative = initial.themeFloatingBarSurface(
            style = ThemeFloatingBarStyle(depth = (-4).dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape
        )
        assertSame(initial, resultNegative)
    }

    @Test
    fun themeIconButtonSurface_returnsSameModifier_whenDepthZeroOrPlain() {
        val initial = Modifier

        // Plain icon with positive depth -> same
        val resultPlain = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.PLAIN_ICON,
            style = ThemeIconButtonStyle(depth = 4.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false
        )
        assertSame(initial, resultPlain)

        // Filled tonal with positive depth -> same
        val resultTonal = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.M3_FILLED_TONAL,
            style = ThemeIconButtonStyle(depth = 4.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false
        )
        assertSame(initial, resultTonal)

        // Tactile plate with zero depth -> same
        val resultTactileZero = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            style = ThemeIconButtonStyle(depth = 0.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false
        )
        assertSame(initial, resultTactileZero)

        // Tactile plate with negative depth -> same
        val resultTactileNegative = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            style = ThemeIconButtonStyle(depth = (-1).dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false
        )
        assertSame(initial, resultTactileNegative)
    }

    @Test
    fun themeCardSurface_appliesModifier_whenDepthPositive() {
        val initial = Modifier

        // Positive depth with solid container color
        val resultSolid = initial.themeCardSurface(
            style = ThemeCardStyle(depth = 3.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.White
        )
        assertNotSame(initial, resultSolid)

        // Positive depth with gradient fill and dark mode
        val resultGradient = initial.themeCardSurface(
            style = ThemeCardStyle(depth = 3.dp, useGradientFill = true),
            palette = testPalette,
            isDark = true,
            fallbackShape = testShape
        )
        assertNotSame(initial, resultGradient)

        // Positive depth with unspecified container color (shadow only)
        val resultShadowOnly = initial.themeCardSurface(
            style = ThemeCardStyle(depth = 3.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.Unspecified
        )
        assertNotSame(initial, resultShadowOnly)

        // Positive depth with style shape override
        val customShape = RoundedCornerShape(24.dp)
        val resultCustomShape = initial.themeCardSurface(
            style = ThemeCardStyle(depth = 4.dp, shape = customShape, useGradientFill = true),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.LightGray
        )
        assertNotSame(initial, resultCustomShape)
    }

    @Test
    fun themeChipSurface_appliesModifier_whenDepthPositive() {
        val initial = Modifier

        val resultSolid = initial.themeChipSurface(
            style = ThemeChipStyle(depth = 2.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.Cyan
        )
        assertNotSame(initial, resultSolid)

        val resultGradient = initial.themeChipSurface(
            style = ThemeChipStyle(depth = 2.dp, useGradientFill = true),
            palette = testPalette,
            isDark = true,
            fallbackShape = testShape
        )
        assertNotSame(initial, resultGradient)

        val resultShadowOnly = initial.themeChipSurface(
            style = ThemeChipStyle(depth = 2.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.Unspecified
        )
        assertNotSame(initial, resultShadowOnly)
    }

    @Test
    fun themeFloatingBarSurface_appliesModifier_whenDepthPositive() {
        val initial = Modifier

        val resultSolid = initial.themeFloatingBarSurface(
            style = ThemeFloatingBarStyle(depth = 6.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.Magenta
        )
        assertNotSame(initial, resultSolid)

        val resultGradient = initial.themeFloatingBarSurface(
            style = ThemeFloatingBarStyle(depth = 6.dp, useGradientFill = true),
            palette = testPalette,
            isDark = true,
            fallbackShape = testShape
        )
        assertNotSame(initial, resultGradient)

        val resultShadowOnly = initial.themeFloatingBarSurface(
            style = ThemeFloatingBarStyle(depth = 6.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            fallbackShape = testShape,
            fallbackContainerColor = Color.Unspecified
        )
        assertNotSame(initial, resultShadowOnly)
    }

    @Test
    fun themeIconButtonSurface_appliesModifier_whenTactileAndDepthPositive() {
        val initial = Modifier

        val resultSolid = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            style = ThemeIconButtonStyle(depth = 3.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            shape = CircleShape,
            fallbackContainerColor = Color.Yellow
        )
        assertNotSame(initial, resultSolid)

        val resultGradient = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            style = ThemeIconButtonStyle(depth = 3.dp, useGradientFill = true),
            palette = testPalette,
            isDark = true,
            shape = CircleShape
        )
        assertNotSame(initial, resultGradient)

        val resultShadowOnly = initial.themeIconButtonSurface(
            plate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            style = ThemeIconButtonStyle(depth = 3.dp),
            palette = ThemeSurfacePalette.Default,
            isDark = false,
            shape = CircleShape,
            fallbackContainerColor = Color.Unspecified
        )
        assertNotSame(initial, resultShadowOnly)
    }

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
