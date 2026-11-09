package me.bubblespark.tidilo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.OverlayBlurHost
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

@Composable
fun TidiloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Prioritize Material You / Dynamic Color on Pixel and compatible devices
    theme: AppTheme = AppTheme.DEFAULT,
    blurEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val style = remember(theme) { AppThemeStyle.forTheme(theme) }
    val colorScheme = resolveMaterialColors(darkTheme, style) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }
    val surfaces = remember(theme, darkTheme, colorScheme, style) {
        resolveThemeSurfaces(theme, darkTheme, colorScheme, style)
    }
    CompositionLocalProvider(LocalThemeSurfaces provides surfaces, LocalAppThemeStyle provides style,
        LocalOverlayBlurSetting provides resolveOverlayBlur(style.overlayBlurPolicy, blurEnabled)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = remember(style.shapeScale) { resolveMaterialShapes(style.shapeScale) },
            content = { OverlayBlurHost(LocalOverlayBlurSetting.current.enabled, content) }
        )
    }
}

/** An explicit preset palette overrides the system palette; absent values retain Dynamic Color. */
fun resolveMaterialColors(base: ColorScheme, darkTheme: Boolean, style: AppThemeStyle): ColorScheme =
    resolveMaterialColors(darkTheme, style) { base }

private inline fun resolveMaterialColors(
    darkTheme: Boolean,
    style: AppThemeStyle,
    fallback: () -> ColorScheme
): ColorScheme = (if (darkTheme) style.darkMaterialColors else style.lightMaterialColors) ?: fallback()
