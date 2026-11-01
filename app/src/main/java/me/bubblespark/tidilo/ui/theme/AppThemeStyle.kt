package me.bubblespark.tidilo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme

/**
 * Color role strategy for theme highlights and ambient lighting.
 * - [ACCENT]: Uses dynamic item/domain/feed accent color (default).
 * - [PRIMARY]: Uses MaterialTheme.colorScheme.primary.
 * - [SECONDARY]: Uses MaterialTheme.colorScheme.secondary.
 * - [TERTIARY]: Uses MaterialTheme.colorScheme.tertiary.
 */
enum class ThemeColorRole {
    ACCENT,
    PRIMARY,
    SECONDARY,
    TERTIARY;

    fun resolve(colorScheme: ColorScheme, dynamicAccent: Color? = null): Color = when (this) {
        ACCENT -> dynamicAccent ?: colorScheme.primary
        PRIMARY -> colorScheme.primary
        SECONDARY -> colorScheme.secondary
        TERTIARY -> colorScheme.tertiary
    }
}

/**
 * Visual theme styling contract governing color role, blur, and transparency across all UI surfaces in Tidilo.
 *
 * For [AppTheme.DEFAULT], colorRole is ACCENT, all blur radii are 0.dp, ensuring zero GPU overhead and 100% native M3 appearance.
 * Future themes configure their own values without touching individual component or screen files.
 */
data class AppThemeStyle(
    val theme: AppTheme,
    val colorRole: ThemeColorRole = ThemeColorRole.ACCENT,
    // 1. Header blur & opacity
    val headerBlurRadius: Dp = 0.dp,
    val headerAlpha: Float = 0.85f,
    // 2. Floating bar & toolbar blur & opacity
    val floatingBarBlurRadius: Dp = 0.dp,
    val floatingBarAlpha: Float = 1.0f,
    // 3. Card blur & opacity
    val cardBlurRadius: Dp = 0.dp,
    val cardAlpha: Float = 1.0f
) {
    fun resolveColor(colorScheme: ColorScheme, dynamicAccent: Color? = null): Color =
        colorRole.resolve(colorScheme, dynamicAccent)

    companion object {
        val Default = AppThemeStyle(AppTheme.DEFAULT)

        fun forTheme(theme: AppTheme): AppThemeStyle = when (theme) {
            AppTheme.DEFAULT -> Default
        }
    }
}

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.Default }

/**
 * Conditionally applies a blur filter if [blurRadius] is greater than 0.dp.
 * When [blurRadius] is 0.dp (as in AppTheme.DEFAULT), returns `this` directly
 * with zero allocation, no modifier nodes, and no GPU offscreen passes.
 */
fun Modifier.themeSurfaceBlur(blurRadius: Dp): Modifier =
    if (blurRadius > 0.dp) blur(blurRadius) else this
