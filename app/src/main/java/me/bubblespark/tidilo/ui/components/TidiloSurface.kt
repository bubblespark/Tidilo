package me.bubblespark.tidilo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
    blurBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    ResolvedTidiloSurface(currentThemeSurfaces().resolve(role, variant, state, accent), modifier, blurBackground, content)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TidiloSurface(
    onClick: () -> Unit,
    role: SurfaceRole,
    modifier: Modifier = Modifier,
    variant: SurfaceVariant = SurfaceVariant.STANDARD,
    state: ThemeSurfaceState = ThemeSurfaceState(),
    accent: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    blurBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val style = currentThemeSurfaces().resolve(role, variant, state, accent)
    val frosted = blurBackground && rememberScreenBackdropInput() != null
    if (onLongClick != null) {
        Surface(
            modifier = modifier.decoration(style).shadow(style.materialElevation(), style.shape, clip = false)
                .clip(style.shape).combinedClickable(
                enabled = state.enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                interactionSource = interactionSource, indication = ripple()),
            shape = style.shape, color = if (frosted) Color.Transparent else style.materialColor(), contentColor = style.contentColor,
            shadowElevation = 0.dp, tonalElevation = if (frosted) 0.dp else style.tonalElevation,
            border = style.materialBorder()
        ) { SurfaceContent(style, frosted, content) }
        return
    }
    Surface(
        onClick = onClick, modifier = modifier.decoration(style), enabled = state.enabled,
        shape = style.shape, color = if (frosted) Color.Transparent else style.materialColor(), contentColor = style.contentColor,
        shadowElevation = style.materialElevation(), tonalElevation = if (frosted) 0.dp else style.tonalElevation,
        border = style.materialBorder(), interactionSource = interactionSource
    ) { SurfaceContent(style, frosted, content) }
}

@Composable
internal fun ResolvedTidiloSurface(
    style: ResolvedSurfaceStyle,
    modifier: Modifier = Modifier,
    blurBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val frosted = blurBackground && rememberScreenBackdropInput() != null
    Surface(
        modifier = modifier.decoration(style), shape = style.shape, color = if (frosted) Color.Transparent else style.materialColor(),
        contentColor = style.contentColor, shadowElevation = style.materialElevation(),
        tonalElevation = if (frosted) 0.dp else style.tonalElevation, border = style.materialBorder()
    ) { SurfaceContent(style, frosted, content) }
}

private fun ResolvedSurfaceStyle.materialColor() =
    if (treatment == SurfaceTreatment.DECORATED) Color.Transparent else containerColor.copy(alpha = containerColor.alpha * opacity)
private fun ResolvedSurfaceStyle.materialElevation() = if (treatment == SurfaceTreatment.MATERIAL) elevation else 0.dp
private fun ResolvedSurfaceStyle.materialBorder() =
    if (treatment == SurfaceTreatment.MATERIAL && borderWidth > 0.dp) BorderStroke(borderWidth, borderColor) else null

@Composable
private fun Modifier.decoration(style: ResolvedSurfaceStyle): Modifier {
    var result = this
    if (style.treatment == SurfaceTreatment.DECORATED && style.elevation > 0.dp) {
        result = result.shadow(style.elevation, style.shape, clip = false, ambientColor = style.ambientShadow, spotColor = style.spotShadow)
    }
    style.accentAmbientShadow?.let { result = result.shadow(4.dp, style.shape, clip = false, ambientColor = it, spotColor = style.accentSpotShadow) }
    if (style.treatment == SurfaceTreatment.DECORATED && style.borderWidth > 0.dp) {
        val bevel = style.bevel
        result = if (bevel != null) {
            val brush = remember(bevel) { Brush.verticalGradient(listOf(bevel.top, bevel.bottom)) }
            result.border(style.borderWidth, brush, style.shape)
        } else {
            result.border(style.borderWidth, style.borderColor, style.shape)
        }
    }
    style.accentRim?.let { rim ->
        result = result.border(1.dp, rememberAccentBrush(rim), style.shape)
    }
    return result
}

@Composable
private fun rememberAccentBrush(gradient: SurfaceGradient): Brush = remember(gradient) {
    Brush.linearGradient(
        listOf(gradient.top, gradient.bottom),
        start = Offset(Float.POSITIVE_INFINITY, 0f),
        end = Offset(0f, Float.POSITIVE_INFINITY)
    )
}

@Composable
private fun SurfaceContent(style: ResolvedSurfaceStyle, frosted: Boolean, content: @Composable () -> Unit) {
    Box(propagateMinConstraints = true) {
        if (frosted) {
            ChromeBlurBackground(style.shape, style.containerColor, Modifier.matchParentSize())
        } else if (style.treatment == SurfaceTreatment.DECORATED) {
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
        if (!frosted) style.accentWash?.let { wash ->
            Box(Modifier.matchParentSize().themeSurfaceBlur(style.selfBlurRadius, style.shape)
                .background(rememberAccentBrush(wash)))
        }
        content()
    }
}

@Composable
fun Modifier.themeNavigationIndicator(selected: Boolean, animatedColor: Color): Modifier {
    val theme = currentThemeSurfaces()
    val style = theme.resolveNavigationIndicator(selected, animatedColor)
    if (style.treatment == SurfaceTreatment.MATERIAL) {
        return background(style.containerColor.copy(alpha = style.containerColor.alpha * style.opacity), style.shape)
    }
    return decoration(style).drawWithCache {
        val fill = style.gradient
        val brush = if (fill == null) null else Brush.verticalGradient(listOf(fill.top, style.containerColor, fill.bottom))
        val outline = style.shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            if (brush == null) drawOutline(outline, style.containerColor.copy(alpha = style.containerColor.alpha * style.opacity))
            else drawOutline(outline, brush, alpha = style.opacity)
        }
    }
}

@Composable
fun TidiloPrimaryActionSurface(expanded: Boolean, modifier: Modifier = Modifier, blurBackground: Boolean = false, content: @Composable () -> Unit) {
    val style = currentThemeSurfaces().resolve(SurfaceRole.PRIMARY_ACTION,
        if (expanded) SurfaceVariant.EMPHASIZED else SurfaceVariant.STANDARD)
    val elevation = androidx.compose.animation.core.animateDpAsState(style.elevation,
        androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow), label = "actionSurfaceElevation")
    ResolvedTidiloSurface(style.copy(elevation = elevation.value), modifier, blurBackground, content)
}
