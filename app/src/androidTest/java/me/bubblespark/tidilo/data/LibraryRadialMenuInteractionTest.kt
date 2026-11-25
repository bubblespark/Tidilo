package me.bubblespark.tidilo.data

import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenuState
import me.bubblespark.tidilo.ui.navigation.LibraryShortcut
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

internal typealias ComponentActivityRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** Motion scale read by every animation the rule runs; set it before `setContent`. */
internal class TestMotionScale : MotionDurationScale {
    var factor = 1f
    override val scaleFactor: Float get() = factor
}

/** The radial menu alone over an empty scaffold. Shortcuts are recorded in [actions]. */
internal class RadialMenuScene(private val rule: ComponentActivityRule) {
    val state = LibraryRadialMenuState()
    val actions = mutableListOf<String>()
    val direction = mutableStateOf(LayoutDirection.Ltr)
    /** True confines the menu to a short full-width box instead of the whole window. */
    val landscapeBox = mutableStateOf(false)
    private var pointerDown = false

    val density: Float get() = rule.activity.resources.displayMetrics.density

    fun show(fontScale: Float? = null) {
        rule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                val current = LocalDensity.current
                CompositionLocalProvider(LocalLayoutDirection provides direction.value,
                    LocalDensity provides Density(current.density, fontScale ?: current.fontScale)) {
                    val bounds = if (landscapeBox.value) Modifier.fillMaxWidth().height(360.dp) else Modifier.fillMaxSize()
                    Box(bounds.testTag(FIXTURE_TAG)) {
                        TidiloScreenScaffold(header = {}, overlay = {
                            LibraryRadialMenu(state, LibraryFilterMode.DOMAIN, { actions += it.name }, { actions += "SEARCH" })
                        }) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        // The menu ignores input unless its window has focus.
        rule.waitUntil(4_000) { rule.activity.hasWindowFocus() }
        rule.waitForIdle()
    }

    private fun nodes(suffix: String, clickable: Boolean): List<SemanticsNode> {
        val tag = hasTestTag("library_radial_$suffix")
        return rule.onAllNodes(if (clickable) tag and hasClickAction() else tag, useUnmergedTree = true).fetchSemanticsNodes()
    }

    /** Waits for the clickable shortcut (or trigger) and returns it. */
    fun node(suffix: String): SemanticsNode {
        try {
            rule.waitUntil(4_000) { nodes(suffix, clickable = true).isNotEmpty() }
        } catch (error: Throwable) {
            throw AssertionError("Missing $suffix; open=${state.isOpen}; actions=$actions", error)
        }
        return nodes(suffix, clickable = true).first()
    }

    fun bounds(suffix: String): Rect = node(suffix).boundsInRoot
    fun center(suffix: String): Offset = bounds(suffix).center
    fun fixtureBounds(): Rect = rule.onAllNodes(hasTestTag(FIXTURE_TAG)).fetchSemanticsNodes().single().boundsInRoot

    /** Shortcut extension in [0, 1], measured from the Newest first circle whether or not it is clickable. */
    fun progress(trigger: Offset): Float {
        val item = nodes("newest_first", clickable = false).first()
        return (trigger.y - item.boundsInRoot.center.y) / (150f * density)
    }

    fun click(suffix: String) {
        node(suffix)
        rule.onAllNodes(hasTestTag("library_radial_$suffix") and hasClickAction(), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    fun touch(block: TouchInjectionScope.() -> Unit) = rule.onRoot().performTouchInput(block)

    fun down(position: Offset) { touch { down(position) }; pointerDown = true }
    fun up() { touch { up() }; pointerDown = false }
    fun cancel() { if (pointerDown) touch { cancel() }; pointerDown = false }

    fun tap(position: Offset) = touch { down(position); up() }

    fun assertClosedWithoutAction(message: String = "") = rule.runOnIdle {
        assertFalse(message, state.isOpen)
        assertTrue(message, actions.isEmpty())
    }

    companion object {
        const val FIXTURE_TAG = "radial_fixture"
    }
}

@OptIn(ExperimentalTestApi::class)
class LibraryRadialMenuInteractionTest {
    private val motion = TestMotionScale()
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>(effectContext = motion)
    private val scene = RadialMenuScene(composeRule)
    private val clock get() = composeRule.mainClock

    /** Steps paused frames until progress enters [range]; fails if an increasing animation overshoots it. */
    private fun awaitProgress(trigger: Offset, range: ClosedFloatingPointRange<Float>): Float {
        var value = scene.progress(trigger)
        repeat(400) {
            if (value in range) return value
            check(value <= range.endInclusive) { "Missed progress $range, observed $value" }
            clock.advanceTimeByFrame()
            value = scene.progress(trigger)
        }
        throw AssertionError("Progress never reached $range, last $value")
    }

    @Test fun stationaryCornerPressDoesNotCountWrapperMotionAsFingerMotion() {
        motion.factor = 10f
        scene.show()
        val trigger = scene.center("trigger")
        clock.autoAdvance = false
        scene.click("trigger")
        val start = awaitProgress(trigger, .42f..0.48f)
        val density = scene.density
        val point = trigger + Offset(-27f * density, (-150f * start + 8f) * density)
        scene.down(point)
        try {
            val slop = ViewConfiguration.get(composeRule.activity).scaledTouchSlop
            val delta = (slop / density + 4f) / 150f
            val end = awaitProgress(trigger, start + delta..start + delta + .07f)
            assertTrue("Wrapper must move farther than touch slop", (end - start) * 150f * density > slop)
            scene.up()
        } finally { scene.cancel() }
        clock.autoAdvance = true
        composeRule.runOnIdle { assertEquals(listOf("DOMAIN"), scene.actions); assertFalse(scene.state.isOpen) }
    }

    @Test fun reversingDuringMotionKeepsContinuousFramePositions() {
        motion.factor = 10f
        scene.show()
        val trigger = scene.center("trigger")
        val frameMillis = 16L // ComposeTestRule frame duration
        val rounding = 2f / (150f * scene.density)
        // The easing derivative is bounded by 5. Compare positions from adjacent clock steps.
        val tolerance = 5f * frameMillis / (230f * motion.factor) + rounding
        clock.autoAdvance = false

        fun advanceUntil(target: ClosedFloatingPointRange<Float>): Float {
            var previous = scene.progress(trigger)
            val initial = previous
            // Each reversal starts at an intermediate position. Its first step is checked too.
            repeat(150) {
                clock.advanceTimeByFrame()
                val current = scene.progress(trigger)
                assertTrue("Shortcut position jumped: $previous -> $current at ${clock.currentTime} ms",
                    abs(current - previous) <= tolerance)
                if (initial > 0f) {
                    assertTrue("Shortcut reached an endpoint during reversal: $current", current > 0f && current < 1f)
                }
                if (current in target) return current
                previous = current
            }
            throw AssertionError("Shortcut did not reach $target from $initial, last position $previous")
        }

        scene.click("trigger")
        val expanding = advanceUntil(.4f..0.6f)
        scene.click("trigger")
        val contracting = advanceUntil(.15f..0.3f)
        scene.click("trigger")
        val reopening = advanceUntil(.7f..0.9f)
        assertTrue(expanding > contracting)
        assertTrue(reopening > contracting)
        composeRule.runOnIdle {
            assertTrue(scene.state.isOpen)
            assertTrue(scene.actions.isEmpty())
        }
    }

    @Test fun midExpansionCornerTapSelectsSearchInsteadOfDismissing() {
        motion.factor = 10f
        scene.show()
        val trigger = scene.center("trigger")
        clock.autoAdvance = false
        scene.click("trigger")
        awaitProgress(trigger, .48f..0.65f)
        val density = scene.density
        scene.tap(trigger + Offset(-25f * density, -25f * density))
        clock.autoAdvance = true
        composeRule.runOnIdle { assertEquals(listOf("SEARCH"), scene.actions); assertFalse(scene.state.isOpen) }
    }

    @Test fun expansionAndRetractionMoveItemsWhileTriggerStaysFixed() {
        scene.show()
        val trigger = scene.center("trigger")
        clock.autoAdvance = false
        fun sample(): List<Float> = buildList {
            add(scene.progress(trigger))
            // 400ms of frames covers the 230ms motion.
            repeat(25) { clock.advanceTimeByFrame(); add(scene.progress(trigger)) }
        }
        scene.click("trigger")
        val opening = sample()
        assertTrue("Opening must move, not only fade", opening.distinct().size > 2)
        assertTrue(opening.first() < opening.last())
        assertEquals(trigger, scene.center("trigger"))
        scene.click("trigger")
        val closing = sample()
        assertTrue("Closing must move", closing.distinct().size > 2)
        assertTrue(closing.first() > closing.last())
        clock.autoAdvance = true
        scene.assertClosedWithoutAction()
    }

    @Test fun tapsOutsideEveryCircleInsideControlBoundsDismiss() {
        scene.show()
        for (target in listOf("trigger", "search", "collection", "rss", "domain", "newest_first")) {
            scene.click("trigger")
            val corner = scene.bounds(target).bottomRight - Offset(2f, 2f)
            scene.tap(corner)
            scene.assertClosedWithoutAction(target)
        }
    }

    @Test fun backgroundingCancelsTapOpenMenu() {
        scene.show()
        scene.click("trigger")
        scene.node("search")
        val scenario = composeRule.activityRule.scenario
        try {
            // Pausing is the lifecycle event the menu listens to when the app leaves the foreground.
            scenario.moveToState(Lifecycle.State.STARTED)
            scene.assertClosedWithoutAction()
        } finally { scenario.moveToState(Lifecycle.State.RESUMED) }
    }

    @Test fun itemPointerCancelAndAdditionalPointerCloseWithoutAction() {
        scene.show()
        for (additional in listOf(false, true)) {
            scene.click("trigger")
            val center = scene.center("search")
            scene.down(center)
            try {
                if (additional) {
                    scene.touch { down(1, center + Offset(10f, 0f)) }
                    composeRule.waitUntil(3_000) { !scene.state.isOpen }
                    composeRule.runOnIdle { assertTrue(scene.actions.isEmpty()) }
                }
            } finally { scene.cancel() }
            scene.assertClosedWithoutAction("additional=$additional")
        }
        val trigger = scene.center("trigger")
        scene.down(trigger)
        try {
            scene.touch { moveTo(trigger + Offset(-50f, -50f)) }
            val domain = scene.center("domain")
            scene.touch { moveTo(domain) }
        } finally { scene.cancel() }
        scene.assertClosedWithoutAction()
    }

    @Test fun geometryAndNamesFitAcrossThemesAndLargeFonts() {
        scene.direction.value = LayoutDirection.Rtl
        scene.show(fontScale = 2f)
        for (landscape in listOf(false, true)) {
            composeRule.runOnIdle { scene.landscapeBox.value = landscape }
            composeRule.waitForIdle()
            scene.click("trigger")
            val box = scene.fixtureBounds()
            val bounds = listOf("trigger", "search", "collection", "rss", "domain", "newest_first").map { scene.bounds(it) }
            val density = scene.density
            bounds.forEach { rect ->
                assertEquals(56f * density, rect.width, 1f)
                assertTrue("landscape=$landscape left $rect", rect.left >= box.left)
                assertTrue("landscape=$landscape top $rect", rect.top >= box.top)
                assertTrue("landscape=$landscape right $rect", rect.right <= box.right)
                assertTrue("landscape=$landscape bottom $rect", rect.bottom <= box.bottom)
            }
            bounds.forEachIndexed { index, rect -> bounds.drop(index + 1).forEach {
                assertTrue("Circles overlap", (it.center - rect.center).getDistance() > rect.width)
            } }
            for (target in LibraryShortcut.entries) {
                composeRule.runOnIdle { scene.state.feedback(target) }
                composeRule.waitForIdle()
                val popup = composeRule.onAllNodes(hasTestTag("library_radial_name"), useUnmergedTree = true)
                    .fetchSemanticsNodes().single().boundsInRoot
                bounds.forEach { circle ->
                    val nearest = Offset(circle.center.x.coerceIn(popup.left, popup.right),
                        circle.center.y.coerceIn(popup.top, popup.bottom))
                    assertTrue("Name overlaps a target: landscape=$landscape/$target/$popup/$circle",
                        (nearest - circle.center).getDistance() >= circle.width / 2)
                }
            }
            scene.click("trigger")
        }
    }

    @Test fun swipeCrossingSearchCommitsOnlyDomainOnceInBothDirections() {
        scene.show()
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            composeRule.runOnIdle { scene.direction.value = direction; scene.actions.clear() }
            val start = scene.center("trigger")
            scene.down(start)
            try {
                clock.advanceTimeBy(650)
                composeRule.runOnIdle { assertFalse("No long-press timer", scene.state.isOpen) }
                scene.touch { moveTo(start + Offset(-40f, -40f)) }
                val search = scene.center("search")
                scene.touch { moveTo(search) }
                composeRule.runOnIdle { assertTrue(scene.actions.isEmpty()) }
                val domain = scene.center("domain")
                scene.touch { moveTo(domain) }
                scene.up()
                composeRule.runOnIdle {
                    assertEquals("$direction", listOf("DOMAIN"), scene.actions)
                    assertFalse(scene.state.isOpen)
                }
            } finally { scene.cancel() }
        }
    }

    @Test fun gapClosePointerCancelAndLateReleaseNeverExecute() {
        scene.show()
        for (cancelled in listOf(false, true)) {
            val start = scene.center("trigger")
            scene.down(start)
            try {
                scene.touch { moveTo(start + Offset(-45f, -45f)) }
                scene.node("domain")
                if (cancelled) composeRule.runOnIdle { scene.state.cancel() }
                scene.touch { moveTo(if (cancelled) start + Offset(-200f, -200f) else start) }
                scene.up()
                scene.assertClosedWithoutAction("cancelled=$cancelled")
            } finally { scene.cancel() }
        }
        scene.click("trigger")
        scene.click("trigger")
        composeRule.runOnIdle { assertFalse(scene.state.isOpen) }
    }

    @Test fun physicalTapAndOutsideDismissDoNotDispatch() {
        scene.show()
        scene.tap(scene.center("trigger"))
        scene.node("search")
        composeRule.runOnIdle { assertTrue(scene.state.isOpen); assertTrue(scene.actions.isEmpty()) }
        scene.tap(Offset(60f, 200f))
        scene.assertClosedWithoutAction()
    }
}

/** Matches the radial shortcut buttons, which all share the `library_radial_` tag prefix. */
internal val isRadialButton = SemanticsMatcher("radial button") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library_radial_") == true &&
        it.config.getOrNull(SemanticsActions.OnClick) != null
}
