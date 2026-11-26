package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.ui.screens.library.CollectionCard
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class CollectionBadgeContrastTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun countBadgesRemainReadableAcrossPaletteAndThemes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val paletteIndex = mutableIntStateOf(0)
        val dark = mutableStateOf(false)
        val dynamic = mutableStateOf(false)
        composeRule.setContent {
            TidiloTheme(darkTheme = dark.value, dynamicColor = dynamic.value, blurEnabled = false) {
                CollectionCard(CollectionWithCount(CollectionEntity("contrast",
                    "Badge ${paletteIndex.intValue} ${dark.value} ${dynamic.value}", "", "folder", null,
                    paletteIndex.intValue, 1, 1), 12), {}, Modifier.padding(20.dp))
            }
        }
        // Each theme/dynamic pair once, with palette indices spread across 0..7.
        for ((dynamicColor, night, index) in listOf(Triple(false, false, 0), Triple(false, true, 7),
            Triple(true, false, 3), Triple(true, true, 5))) {
            paletteIndex.intValue = index; dark.value = night; dynamic.value = dynamicColor
            composeRule.awaitNode(hasText("Badge $index $night $dynamicColor"))
            val count = composeRule.awaitNode(hasText("12 items"))
            composeRule.waitForIdle()
            val left = count.positionOnScreen.x.roundToInt()
            val top = count.positionOnScreen.y.roundToInt()
            val right = left + count.size.width
            val bottom = top + count.size.height
            var maximumContrast = 1f
            var background = 0f
            // The compositor can present the new palette a frame after semantics update.
            for (sample in 1..10) {
                if (sample > 1) delay(100)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                try {
                    background = Color(bitmap.getPixel(right + 2, (top + bottom) / 2)).luminance()
                    maximumContrast = 1f
                    for (y in top until bottom) for (x in left until right) {
                        val foreground = Color(bitmap.getPixel(x, y)).luminance()
                        maximumContrast = maxOf(maximumContrast,
                            (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f))
                    }
                } finally { bitmap.recycle() }
                if (maximumContrast >= 4.5f) break
            }
            assertTrue("Badge $index dark=$night dynamic=$dynamicColor background=$background has contrast $maximumContrast",
                maximumContrast >= 4.5f)
        }
    }
}
