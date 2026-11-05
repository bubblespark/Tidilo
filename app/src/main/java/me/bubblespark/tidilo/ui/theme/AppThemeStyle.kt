package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme

/**
 * Strategy for active tab indicators in floating bottom navigation bars.
 */
enum class ActiveTabIndicatorStyle {
    M3_TONAL_CAPSULE,
    TACTILE_EXTRUDED_TILE
}

/**
 * Strategy for icon button backing plates and tactile styling.
 */
enum class IconButtonPlateStyle {
    PLAIN_ICON,
    M3_FILLED_TONAL,
    TACTILE_CIRCULAR_3D
}

/**
 * Strategy for filter chip coloring (per-category palette vs unified theme color).
 */
enum class ChipColorMode {
    PER_CATEGORY,
    UNIFIED
}

/**
 * Surface palette overrides for canvas and container backgrounds.
 * When properties are null, standard Material 3 colorScheme fallbacks are used.
 */
data class ThemeSurfacePalette(
    val darkCanvasColor: Color? = null,
    val lightCanvasColor: Color? = null,
    val darkContainerColor: Color? = null,
    val lightContainerColor: Color? = null
) {
    fun canvas(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkCanvasColor else lightCanvasColor) ?: fallback

    fun container(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkContainerColor else lightContainerColor) ?: fallback

    companion object {
        val Default = ThemeSurfacePalette()
    }
}

/**
 * Styling contract for bookmark card surfaces (tactile bevel, shape, gradients, rims).
 */
data class ThemeCardStyle(
    val depth: Dp = 0.dp,
    val shape: CornerBasedShape? = null,
    val useGradientFill: Boolean = false,
    val showAccentRim: Boolean = true,
    val treatment: SurfaceTreatment = SurfaceTreatment.MATERIAL
) {
    companion object {
        val Default = ThemeCardStyle()
    }
}

/**
 * Styling contract for filter chips and entity chips.
 */
data class ThemeChipStyle(
    val depth: Dp = 0.dp,
    val shape: CornerBasedShape? = null,
    val useGradientFill: Boolean = false,
    val colorMode: ChipColorMode = ChipColorMode.PER_CATEGORY,
    val categoryIconTint: Color? = null,
    val subcategoryIconTint: Color? = null,
    val darkNeutralContainer: Color? = null,
    val lightNeutralContainer: Color? = null,
    val darkCategoryContent: Color? = null,
    val lightCategoryContent: Color? = null,
    val darkEntityContent: Color? = null,
    val lightEntityContent: Color? = null,
    val treatment: SurfaceTreatment = SurfaceTreatment.MATERIAL
) {
    fun neutralContainer(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkNeutralContainer else lightNeutralContainer) ?: fallback

    fun categoryContent(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkCategoryContent else lightCategoryContent) ?: fallback

    fun entityContent(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkEntityContent else lightEntityContent) ?: fallback

    companion object {
        val Default = ThemeChipStyle()
    }
}

/**
 * Styling contract for the floating bottom navigation bar.
 */
data class ThemeFloatingBarStyle(
    val depth: Dp = 0.dp,
    val activeTabStyle: ActiveTabIndicatorStyle = ActiveTabIndicatorStyle.M3_TONAL_CAPSULE,
    val useGradientFill: Boolean = false,
    val darkActiveContainerColor: Color? = null,
    val lightActiveContainerColor: Color? = null,
    val darkActiveContentColor: Color? = null,
    val lightActiveContentColor: Color? = null,
    val treatment: SurfaceTreatment = SurfaceTreatment.MATERIAL
) {
    fun activeContainer(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkActiveContainerColor else lightActiveContainerColor) ?: fallback

    fun activeContent(isDark: Boolean, fallback: Color): Color =
        (if (isDark) darkActiveContentColor else lightActiveContentColor) ?: fallback

    companion object {
        val Default = ThemeFloatingBarStyle()
    }
}

/**
 * Styling contract for icon button plates across action buttons, like buttons, and toolbars.
 */
data class ThemeIconButtonStyle(
    val actionButtonPlate: IconButtonPlateStyle = IconButtonPlateStyle.M3_FILLED_TONAL,
    val likeButtonPlate: IconButtonPlateStyle = IconButtonPlateStyle.PLAIN_ICON,
    val toolbarActionPlate: IconButtonPlateStyle = IconButtonPlateStyle.PLAIN_ICON,
    val depth: Dp = 0.dp,
    val useGradientFill: Boolean = false
) {
    companion object {
        val Default = ThemeIconButtonStyle()
    }
}

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
 * For [AppTheme.DEFAULT], colorRole is ACCENT, all blur radii are 0.dp, and all sub-configs are Default (depth == 0.dp),
 * ensuring zero GPU overhead and 100% native M3 appearance.
 * Future themes configure their own values without touching individual component or screen files.
 */
data class AppThemeStyle(
    val theme: AppTheme,
    val colorRole: ThemeColorRole = ThemeColorRole.ACCENT,
    val surfacePalette: ThemeSurfacePalette = ThemeSurfacePalette.Default,
    val cardStyle: ThemeCardStyle = ThemeCardStyle.Default,
    val chipStyle: ThemeChipStyle = ThemeChipStyle.Default,
    val floatingBarStyle: ThemeFloatingBarStyle = ThemeFloatingBarStyle.Default,
    val iconButtonStyle: ThemeIconButtonStyle = ThemeIconButtonStyle.Default,
    val headerBlurRadius: Dp = 0.dp,
    val headerAlpha: Float = 0.85f,
    val floatingBarBlurRadius: Dp = 0.dp,
    val floatingBarAlpha: Float = 1.0f,
    val cardBlurRadius: Dp = 0.dp,
    val cardAlpha: Float = 1.0f,
    val surfaceOverrides: Map<SurfaceRole, SurfaceStyleOverride> = emptyMap()
) {
    fun resolveColor(colorScheme: ColorScheme, dynamicAccent: Color? = null): Color =
        colorRole.resolve(colorScheme, dynamicAccent)

    companion object {
        val Default = AppThemeStyle(AppTheme.DEFAULT)

        val Modern = AppThemeStyle(
            theme = AppTheme.MODERN,
            surfacePalette = ThemeSurfacePalette(
                darkCanvasColor = Color(0xFF181A1E),
                lightCanvasColor = Color(0xFFE8EEF2),
                darkContainerColor = Color(0xFF22262B),
                lightContainerColor = Color(0xFFFFFFFF)
            ),
            cardStyle = ThemeCardStyle(
                depth = 3.dp,
                shape = RoundedCornerShape(26.dp),
                useGradientFill = true,
                showAccentRim = false,
                treatment = SurfaceTreatment.DECORATED
            ),
            chipStyle = ThemeChipStyle(
                depth = 3.dp,
                shape = RoundedCornerShape(12.dp),
                useGradientFill = true,
                colorMode = ChipColorMode.UNIFIED,
                categoryIconTint = Color(0xFFF59E0B),
                subcategoryIconTint = Color(0xFFF97316),
                darkNeutralContainer = Color(0xFF161920),
                lightNeutralContainer = Color(0xFFFFFFFF),
                darkCategoryContent = Color(0xFFE2E8F0),
                lightCategoryContent = Color(0xFF1E293B),
                darkEntityContent = Color(0xFF94A3B8),
                lightEntityContent = Color(0xFF475569),
                treatment = SurfaceTreatment.DECORATED
            ),
            floatingBarStyle = ThemeFloatingBarStyle(
                depth = 3.dp,
                activeTabStyle = ActiveTabIndicatorStyle.TACTILE_EXTRUDED_TILE,
                useGradientFill = true,
                darkActiveContainerColor = Color(0xFF22262B),
                lightActiveContainerColor = Color.White,
                darkActiveContentColor = Color(0xFFE2E8F0),
                lightActiveContentColor = Color(0xFF1E293B),
                treatment = SurfaceTreatment.DECORATED
            ),
            iconButtonStyle = ThemeIconButtonStyle(
                actionButtonPlate = IconButtonPlateStyle.TACTILE_CIRCULAR_3D,
                likeButtonPlate = IconButtonPlateStyle.PLAIN_ICON,
                toolbarActionPlate = IconButtonPlateStyle.PLAIN_ICON,
                depth = 3.dp,
                useGradientFill = true
            )
        )

        fun forTheme(theme: AppTheme): AppThemeStyle = when (theme) {
            AppTheme.DEFAULT -> Default
            AppTheme.MODERN -> Modern
        }
    }
}

val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.Default }

/**
 * Conditionally applies a blur filter if [blurRadius] is greater than 0.dp.
 * When [blurRadius] is 0.dp (as in AppTheme.DEFAULT), returns `this` directly
 * with zero allocation, no modifier nodes, and no GPU offscreen passes.
 */
fun Modifier.themeSurfaceBlur(blurRadius: Dp, shape: Shape? = null): Modifier =
    if (blurRadius > 0.dp) {
        if (shape != null) {
            blur(blurRadius, edgeTreatment = BlurredEdgeTreatment(shape))
        } else {
            blur(blurRadius)
        }
    } else {
        this
    }
