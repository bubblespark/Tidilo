package me.bubblespark.tidilo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val CollectionColorPalette: List<Color> = listOf(
    Color(0xFF4FD8EB), // Expressive Teal / Cyan
    Color(0xFF64B5F6), // Sky Blue
    Color(0xFF9FA8DA), // Soft Indigo
    Color(0xFFCE93D8), // Lavender Purple
    Color(0xFFF48FB1), // Rose Pink
    Color(0xFFFF8A65), // Warm Coral
    Color(0xFFFFD54F), // Luminous Amber / Gold
    Color(0xFF81C784)  // Mint / Emerald Green
)

private val CollectionMembershipGolds = listOf(
    Color(0xFFFFD54F), Color(0xFF805600), Color(0xFF332100)
)

/** Keeps the selected star distinct on both tonal and dynamically colored toolbars. */
internal fun collectionMembershipColor(background: Color): Color = CollectionMembershipGolds.first { gold ->
    (maxOf(gold.luminance(), background.luminance()) + 0.05f) /
        (minOf(gold.luminance(), background.luminance()) + 0.05f) >= 3f
}
