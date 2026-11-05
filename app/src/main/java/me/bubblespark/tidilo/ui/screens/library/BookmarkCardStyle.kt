package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.theme.currentThemeSurfaces
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.theme.LocalAppThemeStyle
import me.bubblespark.tidilo.ui.theme.themeSurfaceBlur

/** Scoped to each animated library destination; unspecified colors use the theme primary. */
internal val LocalCardAccentColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * Styling contract for bookmark card surfaces across the library.
 *
 * Provides a dynamic container background and subtle directional top-right
 * accent lighting (rim, gradient wash, and soft tinted drop shadow) keyed to
 * the active item or domain accent color.
 */
internal data class BookmarkCardStyle(
    val containerColor: Color,
    val rim: Brush,
    val surfaceWash: Brush?,
    val ambientShadowColor: Color,
    val spotShadowColor: Color,
    val blurRadius: Dp = 0.dp,
    val hasAmbientAccent: Boolean = false
)

@Composable
internal fun rememberBookmarkCardStyle(darkContainerColor: Color): BookmarkCardStyle {
    val themeStyle = currentThemeSurfaces().preset
    val providedAccent = LocalCardAccentColor.current
    val hasAmbientAccent = providedAccent != Color.Unspecified
    val accent = if (hasAmbientAccent) providedAccent else MaterialTheme.colorScheme.primary
    // Follow the applied color scheme, including explicit preview/test theme overrides.
    val dark = currentThemeSurfaces().isDark
    val isTactile = themeStyle.cardStyle.depth > 0.dp
    return remember(accent, dark, darkContainerColor, themeStyle.cardBlurRadius, themeStyle.cardAlpha, themeStyle.surfacePalette, themeStyle.cardStyle, hasAmbientAccent) {
        val fallbackColor = if (dark) darkContainerColor else Color.White
        val resolvedContainer = themeStyle.surfacePalette.container(dark, fallbackColor)
        val effectiveContainer = resolvedContainer.copy(alpha = themeStyle.cardAlpha)
        BookmarkCardStyle(
            // Intentional exceptions per docs/ui_design.md: white surfaces and black contact shadows
            // preserve crisp contrast without muddy coloration under tinted ambient beams.
            containerColor = effectiveContainer,
            rim = Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = if (dark) (if (isTactile) 0.70f else 0.65f) else (if (isTactile) 0.45f else 0.38f)),
                    accent.copy(alpha = if (dark) (if (isTactile) 0.08f else 0.05f) else 0.04f)
                ),
                start = Offset(Float.POSITIVE_INFINITY, 0f),
                end = Offset(0f, Float.POSITIVE_INFINITY)
            ),
            surfaceWash = if (dark || isTactile) null else Brush.linearGradient(
                colors = listOf(accent.copy(alpha = 0.045f), accent.copy(alpha = 0f)),
                start = Offset(Float.POSITIVE_INFINITY, 0f),
                end = Offset(0f, Float.POSITIVE_INFINITY)
            ),
            ambientShadowColor = accent.copy(alpha = if (dark && isTactile) 0.35f else 0.25f),
            spotShadowColor = if (dark) accent.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.10f),
            blurRadius = themeStyle.cardBlurRadius,
            hasAmbientAccent = hasAmbientAccent
        )
    }
}

internal fun Modifier.bookmarkCardLighting(
    style: BookmarkCardStyle,
    shape: Shape,
    showAccentRim: Boolean = true
): Modifier {
    if (!showAccentRim && !style.hasAmbientAccent) return this
    val base = if (showAccentRim) {
        shadow(
            elevation = 4.dp,
            shape = shape,
            clip = false,
            ambientColor = style.ambientShadowColor,
            spotColor = style.spotShadowColor
        )
    } else {
        this
    }
    return base.border(1.dp, style.rim, shape)
}

internal fun Modifier.bookmarkCardSurfaceWash(style: BookmarkCardStyle): Modifier =
    style.surfaceWash?.let { background(it) } ?: this

/**
 * Applies surface blur conditionally if [style.blurRadius] is greater than 0.dp.
 * Completely bypassed when blurRadius is 0.dp to ensure zero performance overhead for DEFAULT theme.
 */
internal fun Modifier.bookmarkCardSurfaceBlur(style: BookmarkCardStyle, shape: Shape? = null): Modifier =
    themeSurfaceBlur(style.blurRadius, shape)
