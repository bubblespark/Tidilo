package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.library.LibraryNavigationButton
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.MotionDurationScale
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class LibraryNavigationButtonTest {
    private val motion = TestMotionScale()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = motion)

    @Test fun crossfadeKeepsOneCurrentActionAndFixedGeometry() = verifyButton(false)
    @Test fun rtlLargeFontAndDarkAppearanceKeepNavigationStable() = verifyButton(true)

    @Test fun disabledMotionSettlesImmediately() = verifyButton(false, 0f)

    private fun verifyButton(rtl: Boolean, durationScale: Float = 1f) {
        motion.factor = durationScale
        val activity = composeRule.activity
        val drillDown = mutableStateOf(false)
        val menuLabel = activity.getString(R.string.navigation_open)
        val backLabel = activity.getString(R.string.library_back_to_domains)
        var menuClicks = 0
        var upClicks = 0
        val clock = composeRule.mainClock
        clock.autoAdvance = false
        fun frame(delta: Long = 16) {
            clock.advanceTimeBy(delta)
            composeRule.waitForIdle()
        }
        fun nodes(): List<SemanticsNode> =
            composeRule.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        fun description(node: SemanticsNode, value: String) =
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
        fun assertAction(up: Boolean) {
            val all = nodes()
            val actions = composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("Only one navigation action may exist, including during overlap", 1, actions.size)
            assertTrue(description(actions.single(), if (up) backLabel else menuLabel))
            assertEquals(1, all.count { description(it, if (up) backLabel else menuLabel) })
            assertFalse(all.any { description(it, if (up) menuLabel else backLabel) })
            composeRule.runOnUiThread {
                assertTrue(checkNotNull(actions.single().config.getOrNull(SemanticsActions.OnClick)?.action) {
                    "The current navigation button must expose an invokable Click action"
                }.invoke())
            }
        }
        fun glyphPixels(): IntArray {
            val bounds = composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow
            val width = bounds.width.toInt()
            val height = bounds.height.toInt()
            val pixels = IntArray(width * height)
            composeRule.runOnUiThread {
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    bitmap.getPixels(pixels, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height)
                } finally { bitmap.recycle() }
            }
            return pixels
        }
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density, if (rtl) 2f else 1f)
            ) {
                TidiloTheme(darkTheme = rtl, dynamicColor = false, blurEnabled = false) {
                    Surface { Row(Modifier.statusBarsPadding().size(320.dp, 80.dp)) {
                        LibraryNavigationButton(drillDown.value, backLabel, { menuClicks++ }, { upClicks++ },
                            modifier = Modifier.testTag("library-navigation-button"))
                        Text("Navigation title", Modifier.testTag("navigation-title"))
                    } }
                }
            }
        }
        repeat(3) { frame() }
        val initial = composeRule.onAllNodes(hasTestTag("library-navigation-button") and hasContentDescription(menuLabel, substring = true),
            useUnmergedTree = true).fetchSemanticsNodes().single()
        fun titleLeft() = composeRule.onAllNodes(hasTestTag("navigation-title"), useUnmergedTree = true)
            .fetchSemanticsNodes().single().boundsInRoot.left
        val initialBounds = initial.boundsInRoot
        val initialTitleX = titleLeft()
        val pxDensity = activity.resources.displayMetrics.density
        assertEquals(48f * pxDensity, initialBounds.width, 1f)
        assertEquals(48f * pxDensity, initialBounds.height, 1f)
        assertAction(false)
        val menuPixels = glyphPixels()
        drillDown.value = true
        frame()
        frame()
        frame(110)
        assertAction(true)
        val middlePixels = glyphPixels()
        frame(240)
        val backPixels = glyphPixels()
        val button = composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals(initialBounds.left, button.boundsInRoot.left, 1f)
        assertEquals(initialBounds.width, button.boundsInRoot.width, 1f)
        assertEquals(initialTitleX, titleLeft(), 1f)
        assertFalse("The midpoint must paint an intermediate glyph", middlePixels.contentEquals(menuPixels))
        if (durationScale == 0f) {
            assertTrue("Disabled motion must settle immediately", middlePixels.contentEquals(backPixels))
        } else {
            assertFalse("The midpoint must not snap straight to Up", middlePixels.contentEquals(backPixels))
        }
        assertFalse(menuPixels.contentEquals(backPixels))
        drillDown.value = false
        frame()
        frame()
        frame(110)
        assertAction(false)
        drillDown.value = true
        frame()
        frame()
        assertAction(true)
        drillDown.value = false
        frame()
        frame()
        assertAction(false)
        frame(300)
        assertTrue("Reversal must settle back to the original Menu", menuPixels.contentEquals(glyphPixels()))
        assertEquals(3, menuClicks)
        assertEquals(2, upClicks)
    }
}

/** Motion scale read by every animation the rule runs; set it before `setContent`. */
internal class TestMotionScale : MotionDurationScale {
    var factor = 1f
    override val scaleFactor: Float get() = factor
}
