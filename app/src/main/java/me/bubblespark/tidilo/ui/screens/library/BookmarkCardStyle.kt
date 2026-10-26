package me.bubblespark.tidilo.ui.screens.library

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
import androidx.compose.ui.unit.dp

/** Scoped to each animated library destination; unspecified colors use the theme primary. */
internal val LocalCardAccentColor = staticCompositionLocalOf { Color.Unspecified }

internal data class BookmarkCardStyle(
    val containerColor: Color,
    val rim: Brush,
    val surfaceWash: Brush?,
    val ambientShadowColor: Color,
    val spotShadowColor: Color
)

@Composable
internal fun rememberBookmarkCardStyle(darkContainerColor: Color): BookmarkCardStyle {
    val providedAccent = LocalCardAccentColor.current
    val accent = if (providedAccent == Color.Unspecified) MaterialTheme.colorScheme.primary else providedAccent
    // Follow the applied color scheme, including explicit preview/test theme overrides.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(accent, dark, darkContainerColor) {
        BookmarkCardStyle(
            // Intentional exceptions per docs/ui_design.md: white surfaces and black contact shadows
            // preserve crisp contrast without muddy coloration under tinted ambient beams.
            containerColor = if (dark) darkContainerColor else Color.White,
            rim = Brush.linearGradient(
                colors = listOf(accent.copy(alpha = if (dark) 0.65f else 0.38f),
                    accent.copy(alpha = if (dark) 0.05f else 0.04f)),
                start = Offset(Float.POSITIVE_INFINITY, 0f),
                end = Offset(0f, Float.POSITIVE_INFINITY)
            ),
            surfaceWash = if (dark) null else Brush.linearGradient(
                colors = listOf(accent.copy(alpha = 0.045f), accent.copy(alpha = 0f)),
                start = Offset(Float.POSITIVE_INFINITY, 0f),
                end = Offset(0f, Float.POSITIVE_INFINITY)
            ),
            ambientShadowColor = accent.copy(alpha = 0.25f),
            spotShadowColor = if (dark) accent.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.10f)
        )
    }
}

internal fun Modifier.bookmarkCardLighting(style: BookmarkCardStyle, shape: Shape): Modifier =
    shadow(
        elevation = 4.dp,
        shape = shape,
        clip = false,
        ambientColor = style.ambientShadowColor,
        spotColor = style.spotShadowColor
    ).border(1.dp, style.rim, shape)

internal fun Modifier.bookmarkCardSurfaceWash(style: BookmarkCardStyle): Modifier =
    style.surfaceWash?.let { background(it) } ?: this
