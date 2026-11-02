package me.bubblespark.tidilo.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AppThemeStyleTest {

    @Test
    fun defaultStyle_hasZeroBlurRadiiAndAccentRole() {
        val style = AppThemeStyle.Default
        assertEquals(AppTheme.DEFAULT, style.theme)
        assertEquals(ThemeColorRole.ACCENT, style.colorRole)
        assertEquals(0.dp, style.headerBlurRadius)
        assertEquals(0.85f, style.headerAlpha, 0.001f)
        assertEquals(0.dp, style.floatingBarBlurRadius)
        assertEquals(1.0f, style.floatingBarAlpha, 0.001f)
        assertEquals(0.dp, style.cardBlurRadius)
        assertEquals(1.0f, style.cardAlpha, 0.001f)
    }

    @Test
    fun forTheme_returnsDefault_forDefaultTheme() {
        val style = AppThemeStyle.forTheme(AppTheme.DEFAULT)
        assertEquals(AppThemeStyle.Default, style)
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
        val resultModifier = initialModifier.themeSurfaceBlur(16.dp, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        assertNotSame(initialModifier, resultModifier)
    }

    @Test
    fun themeSurfaceBlur_withShape_zeroBlur_returnsSameModifier() {
        val initialModifier = Modifier
        val resultModifier = initialModifier.themeSurfaceBlur(0.dp, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        assertSame(initialModifier, resultModifier)
    }
}