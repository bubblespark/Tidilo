package me.bubblespark.tidilo.ui.screens.discover

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.components.DomainIconVisual
import me.bubblespark.tidilo.ui.components.OgImageState
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Plates (§8.2) fill their 1.91:1 frame with any og:image ratio: no mat at the edges. */
class PlateCropTest {
    @get:Rule val composeRule = createComposeRule()

    private fun art(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }.asImageBitmap()

    @Test fun squareTallAndWideArtFillThePlateEdgeToEdge() {
        val cases = listOf("square" to art(8, 8), "tall" to art(9, 16), "wide" to art(16, 9))
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                Column {
                    cases.forEach { (name, bitmap) ->
                        DiscoverPlateContent(
                            BookmarkItem(name, name, "https://plate.example/$name", "", Tag.createEmpty()),
                            OgImageState(bitmap, resolved = true),
                            DomainIconVisual(null, Color(0xFF4285F4)),
                            RectangleShape,
                            Modifier.width(200.dp).testTag("plate_$name")
                        )
                    }
                }
            }
        }
        cases.forEach { (name, _) ->
            val pixels = composeRule.onNodeWithTag("plate_$name").captureToImage().toPixelMap()
            // Sample the middle of every edge, one pixel in.
            listOf(1 to pixels.height / 2, pixels.width - 2 to pixels.height / 2, pixels.width / 2 to 1, pixels.width / 2 to pixels.height - 2)
                .forEach { (x, y) ->
                    val p = pixels[x, y]
                    assertTrue("$name plate shows a mat at ($x, $y): $p", p.red > 0.9f && p.green < 0.1f && p.blue < 0.1f)
                }
        }
    }
}
