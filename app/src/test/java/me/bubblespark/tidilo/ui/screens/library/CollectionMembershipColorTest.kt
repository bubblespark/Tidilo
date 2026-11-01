package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.bubblespark.tidilo.ui.theme.collectionMembershipColor
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMembershipColorTest {
    @Test fun goldRemainsDistinctAcrossToolbarBackgrounds() {
        val backgrounds = (0..255).map { value -> Color(value, value, value) } +
            listOf(Color(0xFFE1E9EC), Color(0xFF30383B), Color(0xFFEFE0A1), Color(0xFF665C38))
        backgrounds.forEach { background ->
            val gold = collectionMembershipColor(background)
            val contrast = (maxOf(gold.luminance(), background.luminance()) + 0.05f) /
                (minOf(gold.luminance(), background.luminance()) + 0.05f)
            assertTrue("Selected star must contrast against $background", contrast >= 3f)
            assertTrue("Selected star must retain a gold hue", gold.red > gold.green && gold.green > gold.blue)
        }
    }
}
