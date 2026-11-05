package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeStyleTest {

    @Test
    fun defaultStyle_hasZeroBlurRadiiAndAccentRole() {
        val style = AppThemeStyle.Default
        assertEquals(AppTheme.DEFAULT, style.theme)
        assertEquals(ThemeColorRole.ACCENT, style.colorRole)
        assertEquals(ThemeSurfacePalette.Default, style.surfacePalette)
        assertEquals(ThemeCardStyle.Default, style.cardStyle)
        assertEquals(ThemeChipStyle.Default, style.chipStyle)
        assertEquals(ThemeFloatingBarStyle.Default, style.floatingBarStyle)
        assertEquals(ThemeIconButtonStyle.Default, style.iconButtonStyle)
        assertEquals(0.dp, style.headerBlurRadius)
        assertEquals(0.85f, style.headerAlpha, 0.001f)
        assertEquals(0.dp, style.floatingBarBlurRadius)
        assertEquals(1.0f, style.floatingBarAlpha, 0.001f)
        assertEquals(0.dp, style.cardBlurRadius)
        assertEquals(1.0f, style.cardAlpha, 0.001f)
    }

    @Test
    fun defaultStyle_subConfigs_haveZeroDepthAndNullShapes() {
        val style = AppThemeStyle.Default
        assertEquals(0.dp, style.cardStyle.depth)
        assertNull(style.cardStyle.shape)
        assertEquals(0.dp, style.chipStyle.depth)
        assertNull(style.chipStyle.shape)
        assertEquals(0.dp, style.floatingBarStyle.depth)
        assertEquals(0.dp, style.iconButtonStyle.depth)
        assertNull(style.surfacePalette.darkCanvasColor)
        assertNull(style.surfacePalette.lightCanvasColor)
        assertNull(style.surfacePalette.darkContainerColor)
        assertNull(style.surfacePalette.lightContainerColor)
    }

    @Test
    fun themeSurfacePalette_canvasAndContainer_useFallbackWhenNull() {
        val palette = ThemeSurfacePalette.Default
        val fallbackLight = Color.White
        val fallbackDark = Color.Black

        assertEquals(fallbackDark, palette.canvas(isDark = true, fallback = fallbackDark))
        assertEquals(fallbackLight, palette.canvas(isDark = false, fallback = fallbackLight))
        assertEquals(fallbackDark, palette.container(isDark = true, fallback = fallbackDark))
        assertEquals(fallbackLight, palette.container(isDark = false, fallback = fallbackLight))
    }

    @Test
    fun themeSurfacePalette_canvasAndContainer_returnCustomColorsWhenProvided() {
        val darkCanvas = Color(0xFF101010)
        val lightCanvas = Color(0xFFF0F0F0)
        val darkContainer = Color(0xFF202020)
        val lightContainer = Color(0xFFE0E0E0)
        val palette = ThemeSurfacePalette(
            darkCanvasColor = darkCanvas,
            lightCanvasColor = lightCanvas,
            darkContainerColor = darkContainer,
            lightContainerColor = lightContainer
        )
        val fallback = Color.Red

        assertEquals(darkCanvas, palette.canvas(isDark = true, fallback = fallback))
        assertEquals(lightCanvas, palette.canvas(isDark = false, fallback = fallback))
        assertEquals(darkContainer, palette.container(isDark = true, fallback = fallback))
        assertEquals(lightContainer, palette.container(isDark = false, fallback = fallback))
    }

    @Test
    fun themeCardStyle_defaults() {
        val cardStyle = ThemeCardStyle.Default
        assertEquals(0.dp, cardStyle.depth)
        assertNull(cardStyle.shape)
        assertFalse(cardStyle.useGradientFill)
        assertTrue(cardStyle.showAccentRim)
    }

    @Test
    fun themeChipStyle_defaults() {
        val chipStyle = ThemeChipStyle.Default
        assertEquals(0.dp, chipStyle.depth)
        assertNull(chipStyle.shape)
        assertFalse(chipStyle.useGradientFill)
        assertEquals(ChipColorMode.PER_CATEGORY, chipStyle.colorMode)
        assertFalse(chipStyle.emphasizeEntityChips)
    }

    @Test
    fun themeFloatingBarStyle_defaults() {
        val barStyle = ThemeFloatingBarStyle.Default
        assertEquals(0.dp, barStyle.depth)
        assertEquals(ActiveTabIndicatorStyle.M3_TONAL_CAPSULE, barStyle.activeTabStyle)
        assertFalse(barStyle.useGradientFill)
    }

    @Test
    fun themeIconButtonStyle_defaults() {
        val btnStyle = ThemeIconButtonStyle.Default
        assertEquals(IconButtonPlateStyle.M3_FILLED_TONAL, btnStyle.actionButtonPlate)
        assertEquals(IconButtonPlateStyle.PLAIN_ICON, btnStyle.likeButtonPlate)
        assertEquals(IconButtonPlateStyle.PLAIN_ICON, btnStyle.toolbarActionPlate)
        assertEquals(0.dp, btnStyle.depth)
        assertFalse(btnStyle.useGradientFill)
    }

    @Test
    fun enums_containExpectedValues() {
        assertEquals(2, ActiveTabIndicatorStyle.entries.size)
        assertNotNull(ActiveTabIndicatorStyle.valueOf("M3_TONAL_CAPSULE"))
        assertNotNull(ActiveTabIndicatorStyle.valueOf("TACTILE_EXTRUDED_TILE"))

        assertEquals(3, IconButtonPlateStyle.entries.size)
        assertNotNull(IconButtonPlateStyle.valueOf("PLAIN_ICON"))
        assertNotNull(IconButtonPlateStyle.valueOf("M3_FILLED_TONAL"))
        assertNotNull(IconButtonPlateStyle.valueOf("TACTILE_CIRCULAR_3D"))

        assertEquals(2, ChipColorMode.entries.size)
        assertNotNull(ChipColorMode.valueOf("PER_CATEGORY"))
        assertNotNull(ChipColorMode.valueOf("UNIFIED"))
    }

    @Test
    fun forTheme_returnsDefault_forDefaultTheme() {
        val style = AppThemeStyle.forTheme(AppTheme.DEFAULT)
        assertEquals(AppThemeStyle.Default, style)
    }

    @Test
    fun forTheme_returnsModern_forModernTheme() {
        val style = AppThemeStyle.forTheme(AppTheme.MODERN)
        assertEquals(AppThemeStyle.Modern, style)
    }

    @Test
    fun modernStyle_propertiesMatchSpec() {
        val style = AppThemeStyle.Modern
        assertEquals(AppTheme.MODERN, style.theme)
        assertEquals(ThemeColorRole.ACCENT, style.colorRole)

        // surfacePalette
        assertEquals(Color(0xFF181A1E), style.surfacePalette.darkCanvasColor)
        assertEquals(Color(0xFFE8EEF2), style.surfacePalette.lightCanvasColor)
        assertEquals(Color(0xFF22262B), style.surfacePalette.darkContainerColor)
        assertEquals(Color(0xFFFFFFFF), style.surfacePalette.lightContainerColor)

        // cardStyle
        assertEquals(3.dp, style.cardStyle.depth)
        assertEquals(RoundedCornerShape(26.dp), style.cardStyle.shape)
        assertTrue(style.cardStyle.useGradientFill)
        assertFalse(style.cardStyle.showAccentRim)

        // chipStyle
        assertEquals(3.dp, style.chipStyle.depth)
        assertEquals(RoundedCornerShape(12.dp), style.chipStyle.shape)
        assertTrue(style.chipStyle.useGradientFill)
        assertEquals(ChipColorMode.UNIFIED, style.chipStyle.colorMode)
        assertTrue(style.chipStyle.emphasizeEntityChips)

        // floatingBarStyle
        assertEquals(3.dp, style.floatingBarStyle.depth)
        assertEquals(ActiveTabIndicatorStyle.TACTILE_EXTRUDED_TILE, style.floatingBarStyle.activeTabStyle)
        assertTrue(style.floatingBarStyle.useGradientFill)

        // iconButtonStyle
        assertEquals(IconButtonPlateStyle.TACTILE_CIRCULAR_3D, style.iconButtonStyle.actionButtonPlate)
        assertEquals(IconButtonPlateStyle.PLAIN_ICON, style.iconButtonStyle.likeButtonPlate)
        assertEquals(IconButtonPlateStyle.PLAIN_ICON, style.iconButtonStyle.toolbarActionPlate)
        assertEquals(3.dp, style.iconButtonStyle.depth)
        assertTrue(style.iconButtonStyle.useGradientFill)
    }

    @Test
    fun themeSurfaceBlur_zeroBlur_returnsSameModifier() {
        val initialModifier = Modifier
        val resultModifier = initialModifier.themeSurfaceBlur(0.dp)
        assertSame(initialModifier, resultModifier)
    }

    @Test
    fun themeSurfaceBlur_positiveBlur_appliesModifier() {
        val initialModifier = Modifier
        val resultModifier = initialModifier.themeSurfaceBlur(16.dp)
        assertNotSame(initialModifier, resultModifier)
    }

    @Test
    fun themeColorRole_resolvesCorrectly() {
        val scheme = lightColorScheme(
            primary = Color.Red,
            secondary = Color.Green,
            tertiary = Color.Blue
        )
        val dynamicColor = Color.Magenta

        // ACCENT with dynamic accent returns dynamic accent
        assertEquals(dynamicColor, ThemeColorRole.ACCENT.resolve(scheme, dynamicColor))
        // ACCENT without dynamic accent falls back to primary
        assertEquals(Color.Red, ThemeColorRole.ACCENT.resolve(scheme, null))

        // PRIMARY always returns primary
        assertEquals(Color.Red, ThemeColorRole.PRIMARY.resolve(scheme, dynamicColor))
        assertEquals(Color.Red, ThemeColorRole.PRIMARY.resolve(scheme, null))

        // SECONDARY always returns secondary
        assertEquals(Color.Green, ThemeColorRole.SECONDARY.resolve(scheme, dynamicColor))
        assertEquals(Color.Green, ThemeColorRole.SECONDARY.resolve(scheme, null))

        // TERTIARY always returns tertiary
        assertEquals(Color.Blue, ThemeColorRole.TERTIARY.resolve(scheme, dynamicColor))
        assertEquals(Color.Blue, ThemeColorRole.TERTIARY.resolve(scheme, null))
    }

    @Test
    fun appThemeStyle_resolveColor_delegatesToColorRole() {
        val scheme = lightColorScheme(
            primary = Color.Red,
            secondary = Color.Green,
            tertiary = Color.Blue
        )
        val style = AppThemeStyle(AppTheme.DEFAULT, colorRole = ThemeColorRole.TERTIARY)
        assertEquals(Color.Blue, style.resolveColor(scheme, Color.Magenta))
    }

    @Test
    fun themeSurfaceBlur_withShape_positiveBlur_appliesModifier() {
        val initialModifier = Modifier
        val resultModifier = initialModifier.themeSurfaceBlur(16.dp, RoundedCornerShape(8.dp))
        assertNotSame(initialModifier, resultModifier)
    }

    @Test
    fun themeSurfaceBlur_withShape_zeroBlur_returnsSameModifier() {
        val initialModifier = Modifier
        val resultModifier = initialModifier.themeSurfaceBlur(0.dp, RoundedCornerShape(8.dp))
        assertSame(initialModifier, resultModifier)
    }
}
