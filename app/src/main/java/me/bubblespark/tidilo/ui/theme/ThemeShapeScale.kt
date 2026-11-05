package me.bubblespark.tidilo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeCorner { LINE, SMALL, CONTROL, FIELD, CHIP, IMAGE, CARD, DECK, PROMINENT_CARD, SHEET, TOOLBAR, PROMINENT }

/** Existing corner sizes, named by purpose; presets may tune the scale without editing consumers. */
@Immutable
data class ThemeShapeScale(
    val line: Dp = 6.dp,
    val small: Dp = 8.dp,
    val control: Dp = 10.dp,
    val field: Dp = 12.dp,
    val chip: Dp = 14.dp,
    val image: Dp = 16.dp,
    val card: Dp = 20.dp,
    val deck: Dp = 22.dp,
    val prominentCard: Dp = 24.dp,
    val sheet: Dp = 28.dp,
    val toolbar: Dp = 32.dp,
    val prominent: Dp = 36.dp
) {
    init {
        require(ThemeCorner.entries.all { radius(it).value.isFinite() && radius(it) >= 0.dp })
    }
    fun radius(corner: ThemeCorner): Dp = when (corner) {
        ThemeCorner.LINE -> line
        ThemeCorner.SMALL -> small
        ThemeCorner.CONTROL -> control
        ThemeCorner.FIELD -> field
        ThemeCorner.CHIP -> chip
        ThemeCorner.IMAGE -> image
        ThemeCorner.CARD -> card
        ThemeCorner.DECK -> deck
        ThemeCorner.PROMINENT_CARD -> prominentCard
        ThemeCorner.SHEET -> sheet
        ThemeCorner.TOOLBAR -> toolbar
        ThemeCorner.PROMINENT -> prominent
    }
    fun shape(corner: ThemeCorner) = RoundedCornerShape(radius(corner))
}

fun resolveMaterialShapes(scale: ThemeShapeScale) = androidx.compose.material3.Shapes(
    extraSmall = scale.shape(ThemeCorner.SMALL), small = scale.shape(ThemeCorner.FIELD),
    medium = scale.shape(ThemeCorner.CARD), large = scale.shape(ThemeCorner.SHEET),
    extraLarge = scale.shape(ThemeCorner.PROMINENT)
)

@Composable
fun themeCorner(corner: ThemeCorner): RoundedCornerShape {
    val radius = currentThemeSurfaces().preset.shapeScale.radius(corner)
    return remember(radius) { RoundedCornerShape(radius) }
}
