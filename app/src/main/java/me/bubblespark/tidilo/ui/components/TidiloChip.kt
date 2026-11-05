package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.bubblespark.tidilo.ui.theme.*

@Composable
fun TidiloDisplayChip(
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    state: ThemeSurfaceState = ThemeSurfaceState(),
    content: @Composable () -> Unit
) = TidiloSurface(role, modifier, state = state, content = content)

/** Category colors carry semantic hierarchy and search-match information, not theme switches. */
@Composable
fun TidiloBadgeSurface(
    colors: ChipResolvedColors,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val theme = currentThemeSurfaces()
    val style = theme.resolve(SurfaceRole.CLASSIFICATION_CHIP).copy(
        containerColor = colors.containerColor, contentColor = colors.contentColor
    ).decorate(theme.isDark, theme.preset.chipStyle.useGradientFill, DecorationFamily.CHIP)
    ResolvedTidiloSurface(style, modifier, content)
}

/** Keeps the existing native detail-chip layout while isolating theme treatment selection. */
@Composable
fun TidiloClassificationContent(decorated: @Composable () -> Unit, material: @Composable () -> Unit) {
    if (currentThemeSurfaces().resolve(SurfaceRole.CLASSIFICATION_CHIP).treatment == SurfaceTreatment.DECORATED) decorated() else material()
}
