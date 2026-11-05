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
    role: SurfaceRole = SurfaceRole.CLASSIFICATION_CHIP,
    state: ThemeSurfaceState = ThemeSurfaceState(),
    content: @Composable () -> Unit
) {
    val theme = currentThemeSurfaces()
    val style = theme.resolveBadge(role, colors, state)
    ResolvedTidiloSurface(style, modifier, content)
}

/** Keeps the existing native detail-chip layout while isolating theme treatment selection. */
@Composable
fun TidiloClassificationContent(decorated: @Composable () -> Unit, material: @Composable () -> Unit) {
    if (currentThemeSurfaces().resolve(SurfaceRole.CLASSIFICATION_CHIP).treatment == SurfaceTreatment.DECORATED) decorated() else material()
}
