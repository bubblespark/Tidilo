package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailState
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class OverlayMenuRailTest {
    @get:Rule val composeRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun rtlRailFrostFollowsNativeTransformsDuringCollapse(): Unit = runBlocking {
        lateinit var state: WideNavigationRailState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TidiloTheme(dynamicColor = false, darkTheme = false) {
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(outlineVariant = Color.Magenta)) {
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                        state = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
                        scope = rememberCoroutineScope()
                        LibraryNavigationRail(
                            state,
                            LibraryFilterMode.NEWEST_FIRST,
                            {},
                            {},
                            {},
                            {},
                            {},
                            Modifier.testTag("rtl-rail")
                        )
                    }
                }
            }
        }
        val rail = composeRule.awaitNode(hasTestTag("rtl-rail"))
        composeRule.runOnUiThread {
            val names = rail.layoutInfo.getModifierInfo().map { it.modifier.javaClass.simpleName }
            val frost = names.indexOfFirst { it.contains("TypedHazeEffect") }
            val nativeLayer = names.indexOfLast { it.contains("GraphicsLayer") }
            assertTrue(
                "Native RTL anchors and predictive-back layers must wrap frost: $names",
                nativeLayer >= 0 && frost > nativeLayer
            )
            assertTrue(
                "RTL movement must not be reimplemented with mirrored pixel offsets",
                names.none { it == "OffsetPxElement" }
            )
        }
        val density = composeRule.density.density
        val margin = (24 * density).roundToInt()
        val expanded = composeRule.sampleScreen("expanded RTL rail") { bitmap ->
            val divider = findRailDivider(bitmap) ?: return@sampleScreen null
            if (divider.x < margin || bitmap.getPixel(bitmap.width - 1, divider.y) == Color.Magenta.toArgb()) {
                return@sampleScreen null
            }
            val y = divider.y - (4 * density).roundToInt()
            val scrim = bitmap.getPixel(0, y)
            val expandedLeft = (1 until divider.x).firstOrNull { bitmap.getPixel(it, y) != scrim }
                ?: return@sampleScreen null
            val probeX = expandedLeft + margin
            if (bitmap.getPixel(probeX, y) == scrim) return@sampleScreen null
            ExpandedRailSample(divider, y, expandedLeft, probeX)
        }

        var lastDivider: IntOffset? = null
        val collapsed = try {
            composeRule.mainClock.autoAdvance = false
            composeRule.runOnUiThread { scope.launch { state.collapse() } }
            composeRule.mainClock.advanceTimeByFrame()
            var match: CollapseSample? = null
            for (step in 0 until 8) {
                composeRule.waitForIdle()
                match = captureScreen { frame ->
                    val divider = findRailDivider(frame) ?: return@captureScreen null
                    lastDivider = divider
                    val left = expanded.expandedLeft + divider.x - expanded.divider.x
                    if (divider.y == expanded.divider.y &&
                        left >= expanded.probeX + margin &&
                        left + margin < frame.width
                    ) {
                        CollapseSample(
                            left = left,
                            scrim = frame.getPixel(0, expanded.y),
                            probePixel = frame.getPixel(expanded.probeX, expanded.y),
                            movingPixel = frame.getPixel(left + margin, expanded.y)
                        )
                    } else {
                        null
                    }
                }
                if (match != null) break
                composeRule.mainClock.advanceTimeBy(48)
                delay(120)
            }
            match ?: throw AssertionError(
                "No rendered frame for collapsing rail from ${expanded.divider}. Last divider=$lastDivider"
            )
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertEquals(
            "Frost must leave the scrim at (${expanded.probeX}, ${expanded.y}), rail edge=${collapsed.left}",
            collapsed.scrim,
            collapsed.probePixel
        )
        assertTrue(
            "The moving rail must still be drawn at (${collapsed.left + margin}, ${expanded.y})",
            collapsed.movingPixel != collapsed.scrim
        )
    }

    private fun findRailDivider(bitmap: Bitmap): IntOffset? {
        val color = Color.Magenta.toArgb()
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height / 2) {
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            val x = row.indexOf(color)
            if (x >= 0) return IntOffset(x, y)
        }
        return null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun expandedRailUsesFrostedBackground() {
        val appName = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.app_name)
        verify(hasText(appName)) {
            val state = rememberWideNavigationRailState()
            LaunchedEffect(Unit) { state.expand() }
            LibraryNavigationRail(state, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun popupUsesFrostedBackground() = verify(hasText("Menu item 0")) {
        Box {
            TidiloDropdownMenu(expanded = true, onDismissRequest = {}) {
                repeat(5) { index -> DropdownMenuItem(text = { Text("Menu item $index") }, onClick = {}) }
            }
        }
    }

    private fun verify(readyMatcher: SemanticsMatcher, overlay: @Composable () -> Unit): Unit = runBlocking {
        var enabled by mutableStateOf(false)
        composeRule.setContent {
            TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize().background(Color(0xFF003399)))
                overlay()
            }
        }
        composeRule.awaitNode(readyMatcher)
        composeRule.waitForIdle()
        val before = captureScreen { frame -> sampleGrid(frame) }
        composeRule.runOnUiThread { enabled = true }
        val changed = composeRule.sampleScreen("frosted overlay background") { after ->
            val current = sampleGrid(after)
            var diff = 0
            for (i in before.indices) {
                if (before[i] != current[i]) diff++
            }
            diff.takeIf { it > 1000 }
        }
        assertTrue("Enabling frost must change the popup or rail background", changed > 1000)
    }

    private fun sampleGrid(bitmap: Bitmap): IntArray {
        val ys = (bitmap.height / 8 until bitmap.height * 7 / 8 step 4).count()
        val xs = (20 until bitmap.width / 2 step 4).count()
        val samples = IntArray(ys * xs)
        var idx = 0
        for (y in bitmap.height / 8 until bitmap.height * 7 / 8 step 4) {
            for (x in 20 until bitmap.width / 2 step 4) {
                samples[idx++] = bitmap.getPixel(x, y)
            }
        }
        return samples
    }

    private data class ExpandedRailSample(
        val divider: IntOffset,
        val y: Int,
        val expandedLeft: Int,
        val probeX: Int
    )

    private data class CollapseSample(
        val left: Int,
        val scrim: Int,
        val probePixel: Int,
        val movingPixel: Int
    )
}
