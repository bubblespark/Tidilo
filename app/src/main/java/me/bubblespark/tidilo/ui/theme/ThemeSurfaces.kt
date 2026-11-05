package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.config.AppTheme

enum class SurfaceTreatment { MATERIAL, DECORATED }
enum class SurfaceRole {
    BOOKMARK_CARD, DECK_FRONT, DECK_BACK, SETTINGS_GROUP, STATUS_CARD,
    CLASSIFICATION_CHIP, ENTITY_CHIP, FILTER_CHIP, STATUS_BADGE,
    HEADER, FLOATING_BAR, TOOLBAR, AI_BUBBLE, PRIMARY_ACTION, TOOLBAR_ACTION,
    LIKE_ACTION, FILTER_ACTION, DESTRUCTIVE_ACTION, NAVIGATION_INDICATOR, DIALOG, SHEET, MENU, INPUT, ICON_PLATE
}
enum class SurfaceVariant { STANDARD, COMPACT, SUBTLE, EMPHASIZED }
enum class SurfaceTone { NORMAL, ERROR, CAUTION }

@Immutable
data class ThemeSurfaceState(
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val tone: SurfaceTone = SurfaceTone.NORMAL
)

/** Optional preset values. Semantic state colors are resolved after these overrides. */
@Immutable
data class SurfaceStyleOverride(
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val shape: Shape? = null,
    val treatment: SurfaceTreatment? = null,
    val elevation: Dp? = null,
    val borderWidth: Dp? = null,
    val opacity: Float? = null,
    val gradient: SurfaceGradient? = null,
    val bevel: SurfaceGradient? = null,
    val ambientShadow: Color? = null,
    val spotShadow: Color? = null,
    val accentRim: SurfaceGradient? = null,
    val accentWash: SurfaceGradient? = null
)

@Immutable
data class SurfaceGradient(val top: Color, val bottom: Color)

/** Fully resolved background drawing; opacity never applies to the content slot. */
@Immutable
data class ResolvedSurfaceStyle(
    val containerColor: Color = Color.Transparent,
    val contentColor: Color = Color.Black,
    val shape: Shape = RoundedCornerShape(20.dp),
    val treatment: SurfaceTreatment = SurfaceTreatment.MATERIAL,
    val opacity: Float = 1f,
    val elevation: Dp = 0.dp,
    val tonalElevation: Dp = 0.dp,
    val borderWidth: Dp = 0.dp,
    val borderColor: Color = Color.Transparent,
    val bevel: SurfaceGradient? = null,
    val gradient: SurfaceGradient? = null,
    val ambientShadow: Color = Color.Black,
    val spotShadow: Color = Color.Black,
    val selfBlurRadius: Dp = 0.dp,
    val accentRim: SurfaceGradient? = null,
    val accentWash: SurfaceGradient? = null,
    val accentAmbientShadow: Color? = null,
    val accentSpotShadow: Color = Color.Black
) {
    init {
        require(opacity.isFinite() && opacity in 0f..1f)
        require(elevation.value.isFinite() && elevation >= 0.dp)
        require(borderWidth.value.isFinite() && borderWidth >= 0.dp)
    }
}

data class ResolvedThemeSurfaces(
    val isDark: Boolean,
    val colorScheme: ColorScheme,
    val preset: AppThemeStyle
) {
    val canvasColor: Color get() = preset.surfacePalette.canvas(isDark, colorScheme.background)
    val navigationRippleColor: Color get() =
        if (preset.floatingBarStyle.activeTabStyle == ActiveTabIndicatorStyle.TACTILE_EXTRUDED_TILE) {
            if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
        } else Color.Unspecified
    val entityChipIconTint: Color? get() =
        if (preset.chipStyle.treatment == SurfaceTreatment.DECORATED) resolve(SurfaceRole.ENTITY_CHIP).contentColor else null

    fun resolveBadge(role: SurfaceRole, colors: ChipResolvedColors, state: ThemeSurfaceState = ThemeSurfaceState()): ResolvedSurfaceStyle {
        val overrides = preset.surfaceOverrides[role]
        val semantic = state.selected || state.tone != SurfaceTone.NORMAL
        return resolve(role, state = state).copy(
            containerColor = if (semantic) colors.containerColor else overrides?.containerColor ?: colors.containerColor,
            contentColor = if (semantic) colors.contentColor else overrides?.contentColor ?: colors.contentColor
        ).decorate(isDark, preset.chipStyle.useGradientFill, DecorationFamily.CHIP)
            .drawingOverrides(overrides, semantic)
    }

    fun resolve(
        role: SurfaceRole,
        variant: SurfaceVariant = SurfaceVariant.STANDARD,
        state: ThemeSurfaceState = ThemeSurfaceState(),
        accent: Color = Color.Unspecified
    ): ResolvedSurfaceStyle {
        val c = colorScheme
        val card = preset.cardStyle
        val chip = preset.chipStyle
        val bar = preset.floatingBarStyle
        val icons = preset.iconButtonStyle
        val shapes = preset.shapeScale
        val base = ResolvedSurfaceStyle(containerColor = c.surfaceContainerHigh, contentColor = c.onSurface)
        var result = when (role) {
            SurfaceRole.BOOKMARK_CARD -> base.copy(
                containerColor = preset.surfacePalette.container(isDark,
                    if (!isDark) Color.White else if (variant == SurfaceVariant.COMPACT) c.surfaceContainer else c.surfaceContainerHigh),
                shape = card.shape ?: shapes.shape(if (variant == SurfaceVariant.COMPACT) ThemeCorner.IMAGE else ThemeCorner.PROMINENT_CARD),
                opacity = preset.cardAlpha, selfBlurRadius = preset.cardBlurRadius,
                treatment = card.treatment, elevation = card.depth * 2
            ).decorate(isDark, card.useGradientFill, DecorationFamily.CARD)
            SurfaceRole.DECK_FRONT -> base.copy(
                containerColor = preset.surfacePalette.container(isDark, c.surfaceContainerHigh),
                shape = card.shape ?: shapes.shape(if (variant == SurfaceVariant.COMPACT) ThemeCorner.CARD else ThemeCorner.DECK), opacity = preset.cardAlpha,
                treatment = card.treatment, elevation = if (card.treatment == SurfaceTreatment.DECORATED) card.depth * 2 else 2.dp,
                borderWidth = if (card.showAccentRim) 1.dp else 0.dp,
                borderColor = if (accent != Color.Unspecified) accent.copy(alpha = if (variant == SurfaceVariant.COMPACT) 0.42f else 0.45f) else Color.Transparent,
                selfBlurRadius = preset.cardBlurRadius
            ).decorate(isDark, card.useGradientFill, DecorationFamily.CARD)
            SurfaceRole.DECK_BACK -> base.copy(
                containerColor = if (variant == SurfaceVariant.COMPACT || variant == SurfaceVariant.SUBTLE) c.surfaceContainerLow else c.surfaceContainer,
                shape = shapes.shape(if (variant == SurfaceVariant.COMPACT || variant == SurfaceVariant.EMPHASIZED) ThemeCorner.CARD else ThemeCorner.DECK),
                elevation = if (variant == SurfaceVariant.COMPACT || variant == SurfaceVariant.SUBTLE) 0.dp else 1.dp,
                borderWidth = 1.dp,
                borderColor = if (accent == Color.Unspecified) Color.Transparent else accent.copy(alpha = if (variant == SurfaceVariant.COMPACT || variant == SurfaceVariant.SUBTLE) 0.18f else 0.28f)
            )
            SurfaceRole.SETTINGS_GROUP -> base.copy(
                shape = shapes.shape(if (variant == SurfaceVariant.EMPHASIZED) ThemeCorner.PROMINENT_CARD else ThemeCorner.CARD),
                containerColor = if (variant == SurfaceVariant.COMPACT) c.surfaceVariant.copy(alpha = if (state.enabled) 0.5f else 0.22f) else c.surfaceContainerHigh
            )
            SurfaceRole.STATUS_CARD -> base.copy(containerColor = c.surfaceContainer)
            SurfaceRole.CLASSIFICATION_CHIP, SurfaceRole.ENTITY_CHIP -> base.copy(
                containerColor = if (chip.colorMode == ChipColorMode.UNIFIED) chip.neutralContainer(isDark, if (isDark) Color(0xFF161920) else Color.White) else c.primaryContainer.copy(alpha = 0.65f),
                contentColor = if (chip.colorMode == ChipColorMode.UNIFIED) {
                    if (role == SurfaceRole.ENTITY_CHIP) chip.entityContent(isDark, c.onSurfaceVariant) else chip.categoryContent(isDark, c.onSurface)
                } else c.onPrimaryContainer,
                shape = chip.shape ?: shapes.shape(ThemeCorner.FIELD), treatment = chip.treatment, elevation = chip.depth
            ).decorate(isDark, chip.useGradientFill, DecorationFamily.CHIP)
            SurfaceRole.FILTER_CHIP -> base.copy(containerColor = c.surfaceContainerHighest, shape = shapes.shape(ThemeCorner.CHIP))
            SurfaceRole.STATUS_BADGE -> base.copy(containerColor = c.surfaceContainerHighest, shape = shapes.shape(ThemeCorner.SMALL))
            SurfaceRole.HEADER -> base.copy(containerColor = c.background, opacity = preset.headerAlpha, selfBlurRadius = preset.headerBlurRadius)
            SurfaceRole.FLOATING_BAR, SurfaceRole.TOOLBAR -> base.copy(
                containerColor = preset.surfacePalette.container(isDark, c.surfaceContainerHighest),
                shape = if (role == SurfaceRole.TOOLBAR) shapes.shape(ThemeCorner.TOOLBAR) else CircleShape,
                opacity = preset.floatingBarAlpha, selfBlurRadius = preset.floatingBarBlurRadius,
                treatment = bar.treatment,
                elevation = if (bar.treatment == SurfaceTreatment.DECORATED) bar.depth * 2 else 10.dp,
                tonalElevation = if (bar.treatment == SurfaceTreatment.DECORATED) 0.dp else 6.dp
            ).decorate(isDark, bar.useGradientFill, DecorationFamily.BAR)
            SurfaceRole.AI_BUBBLE -> base.copy(containerColor = c.surfaceContainerHighest, shape = shapes.shape(ThemeCorner.IMAGE), elevation = 10.dp, tonalElevation = 6.dp, opacity = preset.floatingBarAlpha, selfBlurRadius = preset.floatingBarBlurRadius)
            SurfaceRole.FILTER_ACTION -> base.copy(containerColor = if (state.selected) c.primaryContainer else c.secondaryContainer, contentColor = if (state.selected) c.onPrimaryContainer else c.onSecondaryContainer, shape = CircleShape, elevation = 6.dp, tonalElevation = 4.dp, opacity = preset.floatingBarAlpha)
            SurfaceRole.PRIMARY_ACTION, SurfaceRole.LIKE_ACTION, SurfaceRole.TOOLBAR_ACTION -> {
                val plate = when (role) {
                    SurfaceRole.LIKE_ACTION -> icons.likeButtonPlate
                    SurfaceRole.TOOLBAR_ACTION -> icons.toolbarActionPlate
                    else -> icons.actionButtonPlate
                }
                base.copy(
                    shape = if (role == SurfaceRole.PRIMARY_ACTION && variant == SurfaceVariant.COMPACT) shapes.shape(ThemeCorner.IMAGE) else CircleShape,
                    containerColor = if (plate == IconButtonPlateStyle.PLAIN_ICON) Color.Transparent else c.primaryContainer,
                    contentColor = if (role == SurfaceRole.PRIMARY_ACTION) c.onPrimaryContainer else c.onSurface,
                    treatment = if (plate == IconButtonPlateStyle.TACTILE_CIRCULAR_3D) SurfaceTreatment.DECORATED else SurfaceTreatment.MATERIAL,
                    elevation = if (plate == IconButtonPlateStyle.TACTILE_CIRCULAR_3D) icons.depth else if (role == SurfaceRole.PRIMARY_ACTION) (when (variant) { SurfaceVariant.COMPACT -> 4.dp; SurfaceVariant.EMPHASIZED -> 10.dp; else -> 6.dp }) else 0.dp,
                    opacity = preset.floatingBarAlpha,
                    selfBlurRadius = preset.floatingBarBlurRadius
                ).decorate(isDark, icons.useGradientFill, DecorationFamily.PLATE)
            }
            SurfaceRole.DESTRUCTIVE_ACTION -> base.copy(containerColor = Color(0xFFBA1A1A), contentColor = Color.White, shape = CircleShape)
            SurfaceRole.NAVIGATION_INDICATOR -> base.copy(
                containerColor = bar.activeContainer(isDark, c.primaryContainer),
                contentColor = bar.activeContent(isDark, c.onPrimaryContainer), shape = CircleShape,
                treatment = if (bar.activeTabStyle == ActiveTabIndicatorStyle.TACTILE_EXTRUDED_TILE) SurfaceTreatment.DECORATED else SurfaceTreatment.MATERIAL,
                elevation = chip.depth
            ).decorate(isDark, chip.useGradientFill, DecorationFamily.CHIP)
            SurfaceRole.DIALOG -> base.copy(shape = shapes.shape(ThemeCorner.PROMINENT))
            SurfaceRole.SHEET -> base.copy(containerColor = c.surfaceContainerLow, shape = RoundedCornerShape(topStart = shapes.sheet, topEnd = shapes.sheet))
            SurfaceRole.MENU -> base.copy(shape = shapes.shape(ThemeCorner.IMAGE), tonalElevation = 6.dp)
            SurfaceRole.INPUT -> base.copy(containerColor = c.surfaceVariant.copy(alpha = 0.35f), shape = shapes.shape(ThemeCorner.FIELD), borderWidth = 1.dp, borderColor = c.outlineVariant)
            SurfaceRole.ICON_PLATE -> base.copy(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = CircleShape)
        }
        if ((role == SurfaceRole.ENTITY_CHIP || role == SurfaceRole.FILTER_CHIP) && variant == SurfaceVariant.COMPACT) {
            result = base.copy(containerColor = c.surfaceVariant.copy(alpha = if (state.enabled) 0.55f else 0.25f),
                contentColor = c.onSurfaceVariant.copy(alpha = if (state.enabled) 1f else 0.38f),
                shape = shapes.shape(ThemeCorner.CONTROL), borderWidth = 1.dp, borderColor = c.outlineVariant)
        }
        preset.surfaceOverrides[role]?.let { override ->
            result = result.copy(
                containerColor = override.containerColor ?: result.containerColor,
                contentColor = override.contentColor ?: result.contentColor,
                shape = override.shape ?: result.shape,
                treatment = override.treatment ?: result.treatment,
                elevation = override.elevation ?: result.elevation,
                borderWidth = override.borderWidth ?: result.borderWidth,
                opacity = override.opacity ?: result.opacity
            )
            if (result.treatment == SurfaceTreatment.DECORATED && override.containerColor != null) {
                val family = when (role) {
                    SurfaceRole.BOOKMARK_CARD, SurfaceRole.DECK_FRONT -> DecorationFamily.CARD
                    SurfaceRole.CLASSIFICATION_CHIP, SurfaceRole.ENTITY_CHIP, SurfaceRole.NAVIGATION_INDICATOR -> DecorationFamily.CHIP
                    SurfaceRole.FLOATING_BAR, SurfaceRole.TOOLBAR -> DecorationFamily.BAR
                    else -> DecorationFamily.PLATE
                }
                result = result.decorate(isDark, result.gradient != null, family)
            }
        }
        if (state.tone == SurfaceTone.ERROR) result = result.copy(containerColor = c.errorContainer, contentColor = c.onErrorContainer, gradient = null)
        if (state.tone == SurfaceTone.CAUTION) result = result.copy(containerColor = c.tertiaryContainer, contentColor = c.onTertiaryContainer, gradient = null)
        if (state.selected && (role == SurfaceRole.FILTER_CHIP || ((role == SurfaceRole.ENTITY_CHIP || role == SurfaceRole.FILTER_CHIP) && variant == SurfaceVariant.COMPACT))) result = result.copy(containerColor = c.primary, contentColor = c.onPrimary, gradient = null)
        if (role == SurfaceRole.BOOKMARK_CARD) {
            val highlight = if (accent == Color.Unspecified) c.primary else accent
            val tactile = result.treatment == SurfaceTreatment.DECORATED
            result = result.copy(
                accentRim = if (card.showAccentRim || accent != Color.Unspecified) SurfaceGradient(
                    highlight.copy(alpha = if (isDark) (if (tactile) 0.70f else 0.65f) else (if (tactile) 0.45f else 0.38f)),
                    highlight.copy(alpha = if (isDark) (if (tactile) 0.08f else 0.05f) else 0.04f)
                ) else null,
                accentWash = if (!isDark && !tactile) SurfaceGradient(highlight.copy(alpha = 0.045f), Color.Transparent) else null,
                accentAmbientShadow = if (card.showAccentRim) highlight.copy(alpha = if (isDark && tactile) 0.35f else 0.25f) else null,
                accentSpotShadow = if (isDark) highlight.copy(alpha = 0.50f) else Color.Black.copy(alpha = 0.10f)
            )
        }
        return result.drawingOverrides(preset.surfaceOverrides[role], state.selected || state.tone != SurfaceTone.NORMAL)
    }
}

private fun ResolvedSurfaceStyle.drawingOverrides(override: SurfaceStyleOverride?, semantic: Boolean): ResolvedSurfaceStyle {
    if (override == null) return this
    return copy(
        borderWidth = override.borderWidth ?: borderWidth,
        gradient = if (semantic) gradient else override.gradient ?: gradient,
        bevel = override.bevel ?: bevel,
        ambientShadow = override.ambientShadow ?: ambientShadow,
        spotShadow = override.spotShadow ?: spotShadow,
        accentRim = override.accentRim ?: accentRim,
        accentWash = override.accentWash ?: accentWash
    )
}

/** Decoration constants belong to the theme contract, not individual screens. */
internal enum class DecorationFamily { CARD, CHIP, BAR, PLATE }

internal fun ResolvedSurfaceStyle.decorate(dark: Boolean, gradientFill: Boolean, family: DecorationFamily): ResolvedSurfaceStyle {
    if (treatment != SurfaceTreatment.DECORATED) return this
    val topFactor = when (family) { DecorationFamily.CARD -> 1.18f; DecorationFamily.CHIP -> 1.08f; DecorationFamily.BAR -> 1.06f; DecorationFamily.PLATE -> 1.10f }
    val bottomFactor = when (family) {
        DecorationFamily.CARD -> if (dark) 0.85f else 0.94f
        DecorationFamily.CHIP -> 0.94f
        DecorationFamily.BAR -> 0.95f
        DecorationFamily.PLATE -> 0.92f
    }
    fun Color.scaled(factor: Float) = copy(red = (red * factor).coerceIn(0f, 1f), green = (green * factor).coerceIn(0f, 1f), blue = (blue * factor).coerceIn(0f, 1f))
    val top = if (family == DecorationFamily.CARD && !dark) Color.White else containerColor.scaled(topFactor)
    val bottom = if (family == DecorationFamily.CARD && !dark) containerColor.copy(red = containerColor.red * 0.93f, green = containerColor.green * 0.94f, blue = containerColor.blue * 0.95f) else containerColor.scaled(bottomFactor)
    val bevelTop = if (dark) when (family) { DecorationFamily.CARD -> 0.15f; DecorationFamily.BAR -> 0.12f; else -> 0.16f } else if (family == DecorationFamily.BAR) 0.85f else 0.90f
    val bevelBottom = if (dark) when (family) { DecorationFamily.CARD -> 0.35f; DecorationFamily.BAR -> 0.30f; else -> 0.40f } else if (family == DecorationFamily.BAR) 0.06f else 0.08f
    val spot = if (dark) { if (family == DecorationFamily.CHIP) 0.55f else 0.60f } else if (family == DecorationFamily.CHIP || family == DecorationFamily.PLATE) 0.12f else 0.10f
    val ambient = if (dark) { if (family == DecorationFamily.CARD) 0.40f else 0.35f } else 0.05f
    return copy(borderWidth = 1.dp, bevel = SurfaceGradient(Color.White.copy(alpha = bevelTop), Color.Black.copy(alpha = bevelBottom)),
        gradient = if (gradientFill) SurfaceGradient(top, bottom) else null,
        ambientShadow = Color.Black.copy(alpha = ambient), spotShadow = Color.Black.copy(alpha = spot))
}

fun resolveThemeSurfaces(theme: AppTheme, darkTheme: Boolean, colorScheme: ColorScheme, style: AppThemeStyle = AppThemeStyle.forTheme(theme)): ResolvedThemeSurfaces =
    ResolvedThemeSurfaces(darkTheme, colorScheme, style)

/** Nullable default keeps isolated MaterialTheme previews faithful to their applied palette. */
val LocalThemeSurfaces = staticCompositionLocalOf<ResolvedThemeSurfaces?> { null }

@Composable
fun currentThemeSurfaces(): ResolvedThemeSurfaces = LocalThemeSurfaces.current ?: run {
    val colors = MaterialTheme.colorScheme
    resolveThemeSurfaces(AppTheme.DEFAULT, colors.background.luminance() < 0.5f, colors, LocalAppThemeStyle.current)
}
