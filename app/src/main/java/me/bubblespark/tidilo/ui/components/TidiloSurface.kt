package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.theme.*

/** Owns decoration and Material background/elevation coordination for all shared surfaces. */
@Composable
fun TidiloSurface(
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    variant: SurfaceVariant = SurfaceVariant.STANDARD,
    state: ThemeSurfaceState = ThemeSurfaceState(),
    accent: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    ResolvedTidiloSurface(currentThemeSurfaces().resolve(role, variant, state, accent), modifier, content)
}

@Composable
fun TidiloSurface(
    onClick: () -> Unit,
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    variant: SurfaceVariant = SurfaceVariant.STANDARD,
    state: ThemeSurfaceState = ThemeSurfaceState(),
    accent: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val style = currentThemeSurfaces().resolve(role, variant, state, accent)
    Surface(
        onClick = onClick, modifier = modifier.decoration(style), enabled = state.enabled,
        shape = style.shape, color = style.materialColor(), contentColor = style.contentColor,
        shadowElevation = style.materialElevation(), tonalElevation = style.tonalElevation,
        border = style.materialBorder(), interactionSource = interactionSource
    ) { SurfaceContent(style, content) }
}

@Composable
internal fun ResolvedTidiloSurface(
    style: ResolvedSurfaceStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.decoration(style), shape = style.shape, color = style.materialColor(),
        contentColor = style.contentColor, shadowElevation = style.materialElevation(),
        tonalElevation = style.tonalElevation, border = style.materialBorder()
    ) { SurfaceContent(style, content) }
}

private fun ResolvedSurfaceStyle.materialColor() =
    if (treatment == SurfaceTreatment.DECORATED) Color.Transparent else containerColor.copy(alpha = containerColor.alpha * opacity)
private fun ResolvedSurfaceStyle.materialElevation() = if (treatment == SurfaceTreatment.MATERIAL) elevation else 0.dp
private fun ResolvedSurfaceStyle.materialBorder() =
    if (treatment == SurfaceTreatment.MATERIAL && borderWidth > 0.dp) BorderStroke(borderWidth, borderColor) else null

private fun Modifier.decoration(style: ResolvedSurfaceStyle): Modifier {
    var result = this
    if (style.treatment == SurfaceTreatment.DECORATED && style.elevation > 0.dp) {
        result = result.shadow(style.elevation, style.shape, clip = false, ambientColor = style.ambientShadow, spotColor = style.spotShadow)
    }
    style.accentAmbientShadow?.let { result = result.shadow(4.dp, style.shape, clip = false, ambientColor = it, spotColor = style.accentSpotShadow) }
    if (style.treatment == SurfaceTreatment.DECORATED && style.borderWidth > 0.dp) {
        result = if (style.bevel != null) result.border(style.borderWidth, Brush.verticalGradient(listOf(style.bevel.top, style.bevel.bottom)), style.shape)
        else result.border(style.borderWidth, style.borderColor, style.shape)
    }
    style.accentRim?.let { rim ->
        result = result.border(1.dp, Brush.linearGradient(listOf(rim.top, rim.bottom),
            start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY)), style.shape)
    }
    return result
}

@Composable
private fun SurfaceContent(style: ResolvedSurfaceStyle, content: @Composable () -> Unit) {
    Box {
        if (style.treatment == SurfaceTreatment.DECORATED) {
            Box(Modifier.matchParentSize().themeSurfaceBlur(style.selfBlurRadius, style.shape).drawWithCache {
                val fill = style.gradient
                val brush = if (fill != null) Brush.verticalGradient(listOf(fill.top, style.containerColor, fill.bottom)) else null
                val outline = style.shape.createOutline(size, layoutDirection, this)
                onDrawBehind {
                    if (brush != null) drawOutline(outline, brush, alpha = style.opacity)
                    else drawOutline(outline, style.containerColor.copy(alpha = style.containerColor.alpha * style.opacity))
                }
            })
        }
        style.accentWash?.let { wash ->
            Box(Modifier.matchParentSize().themeSurfaceBlur(style.selfBlurRadius, style.shape).background(
                Brush.linearGradient(listOf(wash.top, wash.bottom), start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY))))
        }
        content()
    }
}
