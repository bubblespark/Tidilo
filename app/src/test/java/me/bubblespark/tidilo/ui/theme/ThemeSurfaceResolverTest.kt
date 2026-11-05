package me.bubblespark.tidilo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.*
import org.junit.Test

class ThemeSurfaceResolverTest {
    private val colors = lightColorScheme()

    @Test fun decoratedZeroElevationKeepsFillAndBorder() {
        val preset = AppThemeStyle.Modern.copy(cardStyle = AppThemeStyle.Modern.cardStyle.copy(depth = 0.dp))
        val card = resolveThemeSurfaces(AppTheme.MODERN, false, colors, preset).resolve(SurfaceRole.BOOKMARK_CARD)
        assertEquals(SurfaceTreatment.DECORATED, card.treatment)
        assertEquals(0.dp, card.elevation)
        assertTrue(card.containerColor.alpha > 0f)
        assertTrue(card.borderWidth > 0.dp)
    }

    @Test fun plainActionIgnoresDecoratedPlateElevation() {
        val action = resolveThemeSurfaces(AppTheme.MODERN, false, colors).resolve(SurfaceRole.LIKE_ACTION)
        assertEquals(SurfaceTreatment.MATERIAL, action.treatment)
        assertEquals(0.dp, action.elevation)
        assertTrue(action.contentColor.alpha > 0f)
    }

    @Test fun semanticErrorAndSelectionBeatNeutralPalette() {
        val theme = resolveThemeSurfaces(AppTheme.MODERN, false, colors)
        val error = theme.resolve(SurfaceRole.STATUS_CARD, state = ThemeSurfaceState(tone = SurfaceTone.ERROR), accent = Color.Green)
        assertEquals(colors.errorContainer, error.containerColor)
        assertEquals(colors.onErrorContainer, error.contentColor)
        val selected = theme.resolve(SurfaceRole.FILTER_CHIP, state = ThemeSurfaceState(selected = true), accent = Color.Green)
        assertEquals(colors.primary, selected.containerColor)
        assertEquals(colors.onPrimary, selected.contentColor)
    }

    @Test fun allRolesResolveContainerContentPairs() {
        for (preset in AppTheme.entries) for (dark in listOf(false, true)) {
            val theme = resolveThemeSurfaces(preset, dark, if (dark) darkColorScheme() else colors)
            for (role in SurfaceRole.entries) {
                val result = theme.resolve(role)
                assertNotEquals(Color.Unspecified, result.containerColor)
                assertNotEquals(Color.Unspecified, result.contentColor)
            }
            assertEquals(SurfaceTreatment.MATERIAL, theme.resolve(SurfaceRole.SETTINGS_GROUP).treatment)
            assertEquals(SurfaceTreatment.MATERIAL, theme.resolve(SurfaceRole.DIALOG).treatment)
        }
    }

    @Test fun explicitModeWinsOverSystemMode() {
        val theme = resolveThemeSurfaces(AppTheme.MODERN, true, colors)
        assertTrue(theme.isDark)
        assertEquals(Color(0xFF181A1E), theme.canvasColor)
        assertEquals(Color(0xFF22262B), theme.resolve(SurfaceRole.BOOKMARK_CARD).containerColor)
    }

    @Test fun rejectsNonFiniteOpacityAndNegativeElevation() {
        for (alpha in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { ResolvedSurfaceStyle(opacity = alpha) }
        }
        assertThrows(IllegalArgumentException::class.java) { ResolvedSurfaceStyle(elevation = (-1).dp) }
        ResolvedSurfaceStyle(elevation = 0.dp)
    }

    @Test fun presetContainerOverrideUpdatesDecoratedGradient() {
        val base = resolveThemeSurfaces(AppTheme.MODERN, true, darkColorScheme()).resolve(SurfaceRole.BOOKMARK_CARD)
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.BOOKMARK_CARD to SurfaceStyleOverride(containerColor = Color(0xFF553322))))
        val changed = resolveThemeSurfaces(AppTheme.MODERN, true, darkColorScheme(), preset).resolve(SurfaceRole.BOOKMARK_CARD)
        assertNotEquals(base.gradient, changed.gradient)
    }

    @Test fun nativeChipRolesPreserveEditorSelectionAndDisplayDifferences() {
        val theme = resolveThemeSurfaces(AppTheme.DEFAULT, false, colors)
        assertEquals(colors.primary, theme.resolve(SurfaceRole.FILTER_CHIP,
            state = ThemeSurfaceState(selected = true)).containerColor)
        assertEquals(0.dp, theme.resolve(SurfaceRole.DECK_BACK, SurfaceVariant.COMPACT).elevation)
        assertEquals(1.dp, theme.resolve(SurfaceRole.DECK_BACK).elevation)
    }

    @Test fun decorationGradientsMatchCurrentFamilies() {
        val theme = resolveThemeSurfaces(AppTheme.MODERN, true, darkColorScheme())
        val bar = theme.resolve(SurfaceRole.FLOATING_BAR)
        assertEquals(bar.containerColor.copy(red = bar.containerColor.red * 0.95f,
            green = bar.containerColor.green * 0.95f, blue = bar.containerColor.blue * 0.95f), bar.gradient!!.bottom)
        val action = theme.resolve(SurfaceRole.PRIMARY_ACTION)
        assertEquals(action.containerColor.copy(red = action.containerColor.red * 0.92f,
            green = action.containerColor.green * 0.92f, blue = action.containerColor.blue * 0.92f), action.gradient!!.bottom)
    }

    @Test fun likeAndToolbarTokensAffectTheirConsumers() {
        val preset = AppThemeStyle.Modern.copy(iconButtonStyle = AppThemeStyle.Modern.iconButtonStyle.copy(
            likeButtonPlate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
            toolbarActionPlate = IconButtonPlateStyle.M3_FILLED_TONAL))
        val theme = resolveThemeSurfaces(AppTheme.MODERN, false, colors, preset)
        assertEquals(SurfaceTreatment.DECORATED, theme.resolve(SurfaceRole.LIKE_ACTION).treatment)
        assertEquals(colors.primaryContainer, theme.resolve(SurfaceRole.TOOLBAR_ACTION).containerColor)
    }

    @Test fun shapeScaleChangesRoleAndNativeCorners() {
        val scale = ThemeShapeScale(card = 23.dp, prominentCard = 27.dp)
        val preset = AppThemeStyle.Default.copy(shapeScale = scale)
        val theme = resolveThemeSurfaces(AppTheme.DEFAULT, false, colors, preset)
        assertEquals(androidx.compose.foundation.shape.RoundedCornerShape(23.dp),
            theme.resolve(SurfaceRole.SETTINGS_GROUP).shape)
        assertEquals(27.dp, scale.radius(ThemeCorner.PROMINENT_CARD))
    }

    @Test fun settingsPresetChangeReachesGroupedAndRssCards() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.SETTINGS_GROUP to SurfaceStyleOverride(containerColor = Color.Cyan)))
        val theme = resolveThemeSurfaces(AppTheme.MODERN, false, colors, preset)
        assertEquals(Color.Cyan, theme.resolve(SurfaceRole.SETTINGS_GROUP).containerColor)
        assertEquals(SurfaceTreatment.MATERIAL, theme.resolve(SurfaceRole.SETTINGS_GROUP).treatment)
    }

    @Test fun presetMaterialColorsRetainDynamicWhenUnspecified() {
        assertSame(colors, resolveMaterialColors(colors, false, AppThemeStyle.Default))
        val replacement = colors.copy(surfaceContainerHigh = Color.Cyan)
        val preset = AppThemeStyle.Modern.copy(lightMaterialColors = replacement)
        assertSame(replacement, resolveMaterialColors(colors, false, preset))
        assertSame(colors, resolveMaterialColors(colors, true, preset))
    }

    @Test fun materialShapeScaleMatchesNativeRoles() {
        val scale = ThemeShapeScale(card = 23.dp)
        assertEquals(scale.shape(ThemeCorner.CARD), resolveMaterialShapes(scale).medium)
    }
}
