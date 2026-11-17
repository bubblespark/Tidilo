package me.bubblespark.tidilo.data

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.Lifecycle
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.navigation.*
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.config.AppTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test

/** Menu-only fixture. Effective semantics deliberately use the merged Activity root. */
internal class RadialMenuFixture(val direction: LayoutDirection = LayoutDirection.Ltr,
    theme: AppTheme = AppTheme.DEFAULT, dark: Boolean = false, blur: Boolean = true,
    fontScale: Float? = null, motionScale: Float? = null) : AutoCloseable {
    val host = HeaderTestHost()
    val activity = host.launch()
    val state = LibraryRadialMenuState()
    val actions = mutableListOf<String>()
    private var pointerDown = false
    private var ready = false
    private val motionScope = motionScale?.let { CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob()) }
    private val recomposer = motionScope?.let { scope -> Recomposer(scope.coroutineContext + object : MotionDurationScale {
        override val scaleFactor: Float = requireNotNull(motionScale)
    }) }


    init {
        recomposer?.let { active -> motionScope!!.launch { active.runRecomposeAndApplyChanges() } }
        host.main { activity.setContent(parent = recomposer) {
            TidiloTheme(dynamicColor = false, theme = theme, darkTheme = dark, blurEnabled = blur) {
                val currentDensity = LocalDensity.current
                CompositionLocalProvider(LocalLayoutDirection provides direction,
                    LocalDensity provides Density(currentDensity.density, fontScale ?: currentDensity.fontScale)) {
                    Box(Modifier.fillMaxSize().testTag("radial_fixture")) {
                    TidiloScreenScaffold(header = {}, overlay = {
                        LibraryRadialMenu(state, LibraryFilterMode.DOMAIN, { actions += it.name }, { actions += "SEARCH" })
                    }) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        } }
    }

    suspend fun node(suffix: String): SemanticsNode {
        if (!ready) {
            withTimeout(4000) {
                var focused = false
                while (!focused) {
                    host.main { focused = activity.hasWindowFocus() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    if (!focused) delay(20)
                }
            }
            host.instrumentation.waitForIdleSync()
            ready = true
        }
        try {
            return host.await(activity) {
                it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_$suffix" &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    generateSequence(it) { node -> node.parent }.any { node ->
                        node.config.getOrNull(SemanticsProperties.TestTag) == "radial_fixture"
                    }
            }
        } catch (error: Exception) {
            throw AssertionError("Missing $suffix; open=${state.isOpen}; actions=$actions", error)
        }
    }

    suspend fun click(suffix: String) {
        val node = node(suffix)
        host.main { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
        if (state.isOpen) delay(280)
    }

    suspend fun center(suffix: String): Offset {
        if (state.isOpen) delay(280)
        return node(suffix).boundsInWindow.center
    }

    fun send(action: Int, position: Offset, downTime: Long) {
        if (action == MotionEvent.ACTION_CANCEL && !pointerDown) return
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(host.instrumentation.uiAutomation.injectInputEvent(event, true))
            if (action == MotionEvent.ACTION_DOWN) pointerDown = true
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerDown = false
        } finally { event.recycle() }
    }

    override fun close() { host.main { activity.setContent {}; activity.finish(); recomposer?.cancel(); motionScope?.cancel() } }

    companion object {
        fun effectiveNodes(activity: ComponentActivity): List<SemanticsNode> {
            fun roots(view: View): List<ViewRootForTest> = when (view) {
                is ViewRootForTest -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
                else -> emptyList()
            }
            fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
            val owners = roots(activity.window.decorView)
            check(owners.isNotEmpty()) { "No effective Compose root" }
            return owners.flatMap { visit(it.semanticsOwner.rootSemanticsNode) }
        }
    }
}

class LibraryRadialMenuInteractionTest {
    private fun readProgress(fixture: RadialMenuFixture, trigger: Offset): Float {
        val node = fixture.host.nodes(fixture.activity).first { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_newest_first" &&
            generateSequence(it) { parent -> parent.parent }.any { parent -> parent.config.getOrNull(SemanticsProperties.TestTag) == "radial_fixture" } }
        return (trigger.y - node.boundsInWindow.center.y) / (150f * fixture.activity.resources.displayMetrics.density)
    }

    private fun progress(fixture: RadialMenuFixture, trigger: Offset): Float {
        var value = 0f
        fixture.host.main { value = readProgress(fixture, trigger) }
        return value
    }

    private suspend fun awaitProgress(fixture: RadialMenuFixture, trigger: Offset, range: ClosedFloatingPointRange<Float>, increasing: Boolean = true): Float = withTimeout(4000) {
        var value = progress(fixture, trigger)
        while (value !in range) {
            check(!increasing || value <= range.endInclusive) { "Missed progress $range, observed $value" }
            delay(5); value = progress(fixture, trigger)
        }
        value
    }

    @Test fun stationaryCornerPressDoesNotCountWrapperMotionAsFingerMotion() = runBlocking {
        run {
            RadialMenuFixture(motionScale = 10f).use { fixture ->
                val trigger = fixture.center("trigger")
                val button = fixture.node("trigger")
                fixture.host.main { button.config[SemanticsActions.OnClick].action!!.invoke() }
                val start = awaitProgress(fixture, trigger, .42f.. .48f)
                val density = fixture.activity.resources.displayMetrics.density
                val point = trigger + Offset(-27f * density, (-150f * start + 8f) * density)
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, point, time)
                try {
                    val slop = android.view.ViewConfiguration.get(fixture.activity).scaledTouchSlop
                    val delta = (slop / density + 4f) / 150f
                    val end = awaitProgress(fixture, trigger, start + delta..start + delta + .07f)
                    assertTrue("Wrapper must move farther than touch slop", (end - start) * 150f * density > slop)
                    fixture.send(MotionEvent.ACTION_UP, point, time)
                } finally { fixture.send(MotionEvent.ACTION_CANCEL, point, time) }
                delay(100)
                fixture.host.main { assertEquals(listOf("DOMAIN"), fixture.actions); assertFalse(fixture.state.isOpen) }
            }
        }
    }

    @Test fun reversingDuringMotionKeepsContinuousFramePositions() = runBlocking {
        run {
            RadialMenuFixture(motionScale = 10f).use { fixture ->
                val trigger = fixture.center("trigger")
                val button = fixture.node("trigger")
                fun toggle() = fixture.host.main { button.config[SemanticsActions.OnClick].action!!.invoke() }
                toggle()
                awaitProgress(fixture, trigger, .4f.. .6f)
                data class FrameSample(val progress: Float, val observedAtNanos: Long)
                val samples = java.util.concurrent.CopyOnWriteArrayList<FrameSample>()
                var generation = 0
                fun assertFrameContinuity(before: Float, toggledAt: Long) {
                    assertTrue("No drawn frame sampled after reversal", samples.isNotEmpty())
                    var previous = FrameSample(before, toggledAt)
                    for (sample in samples) {
                        val elapsedMillis = (sample.observedAtNanos - previous.observedAtNanos) / 1_000_000f
                        // Bound the easing derivative by 5 and allow two pixels of rounding.
                        // This checks the first post-toggle frame and subsequent frames too.
                        val rounding = 2f / (150f * fixture.activity.resources.displayMetrics.density)
                        val tolerance = 5f * elapsedMillis / (230f * 10f) + rounding
                        assertTrue("Drawn frame jumped: ${previous.progress} -> ${sample.progress}, elapsed=$elapsedMillis, tolerance=$tolerance",
                            kotlin.math.abs(sample.progress - previous.progress) <= tolerance)
                        previous = sample
                    }
                }
                var sampling = true
                val callback = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        val frameGeneration = generation
                        // Run after this frame's traversal/draw, not before placement.
                        fixture.activity.window.decorView.post {
                            if (sampling && frameGeneration == generation) {
                                samples += FrameSample(readProgress(fixture, trigger), System.nanoTime())
                            }
                        }
                        if (sampling) android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                try {
                    fixture.host.main { android.view.Choreographer.getInstance().postFrameCallback(callback) }
                    var beforeClose = 0f
                    var closeTime = 0L
                    fixture.host.main {
                        beforeClose = readProgress(fixture, trigger)
                        generation++
                        samples.clear()
                        closeTime = System.nanoTime()
                        button.config[SemanticsActions.OnClick].action!!.invoke()
                    }
                    assertTrue("Close must interrupt expansion", beforeClose in .05f.. .9f)
                    val contracting = awaitProgress(fixture, trigger, .15f.. .3f, increasing = false)
                    assertFrameContinuity(beforeClose, closeTime)
                    assertTrue("Retraction must not reset to either endpoint", samples.isNotEmpty() && samples.all { it.progress > 0f && it.progress <= beforeClose + .1f })
                    var beforeOpen = 0f
                    var openTime = 0L
                    fixture.host.main {
                        beforeOpen = readProgress(fixture, trigger)
                        generation++
                        samples.clear()
                        openTime = System.nanoTime()
                        button.config[SemanticsActions.OnClick].action!!.invoke()
                    }
                    awaitProgress(fixture, trigger, .7f.. .9f)
                    assertFrameContinuity(beforeOpen, openTime)
                    assertTrue(beforeClose > contracting)
                    assertTrue(beforeOpen < .4f)
                    assertTrue(samples.size > 2)
                    assertTrue("Reopening must continue from the contracted position", samples.all { it.progress > 0f && it.progress >= beforeOpen - .1f && it.progress < 1f })
                    fixture.host.main { assertTrue(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
                } finally { fixture.host.main { sampling = false; android.view.Choreographer.getInstance().removeFrameCallback(callback) } }
            }
        }
    }

    @Test fun midExpansionCornerTapSelectsSearchInsteadOfDismissing() = runBlocking {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val previous = android.provider.Settings.Global.getString(instrumentation.targetContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        } }
        try {
            shell("settings put global animator_duration_scale 10")
            delay(200)
            RadialMenuFixture(motionScale = 10f).use { fixture ->
                val trigger = fixture.center("trigger")
                val button = fixture.node("trigger")
                fixture.host.main { button.config[SemanticsActions.OnClick].action!!.invoke() }
                val density = fixture.activity.resources.displayMetrics.density
                withTimeout(4000) {
                    var ready = false
                    while (!ready) {
                        fixture.host.main {
                            val node = fixture.host.nodes(fixture.activity).first { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_newest_first" }
                            val progress = (trigger.y - node.boundsInWindow.center.y) / (150f * density)
                            ready = progress in .48f.. .65f
                        }
                        if (!ready) delay(10)
                    }
                }
                val point = trigger + Offset(-25f * density, -25f * density)
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, point, time)
                try { fixture.send(MotionEvent.ACTION_UP, point, time) }
                finally { fixture.send(MotionEvent.ACTION_CANCEL, point, time) }
                delay(100)
                fixture.host.main { assertEquals(listOf("SEARCH"), fixture.actions); assertFalse(fixture.state.isOpen) }
            }
        } finally {
            shell(if (previous == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $previous")
        }
    }

    @Test fun expansionAndRetractionMoveItemsWhileTriggerStaysFixed() = runBlocking {
        RadialMenuFixture().use { fixture ->
            val trigger = fixture.center("trigger")
            val samples = java.util.concurrent.CopyOnWriteArrayList<Float>()
            var sampling = true
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    fixture.host.nodes(fixture.activity).firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_newest_first" &&
                            generateSequence(it) { node -> node.parent }.any { node ->
                                node.config.getOrNull(SemanticsProperties.TestTag) == "radial_fixture"
                            }
                    }?.let { samples += it.boundsInWindow.center.y }
                    if (sampling) android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            try {
                fixture.host.main {
                    android.view.Choreographer.getInstance().postFrameCallback(callback)
                    val button = fixture.host.nodes(fixture.activity).first {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" &&
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    button.config[SemanticsActions.OnClick].action!!.invoke()
                }
                delay(400)
                assertTrue("Opening must move, not only fade", samples.distinct().size > 2)
                assertTrue(samples.first() > samples.last())
                assertEquals(trigger, fixture.center("trigger"))
                samples.clear()
                fixture.click("trigger")
                delay(400)
                assertTrue("Closing must move", samples.distinct().size > 2)
                assertTrue(samples.first() < samples.last())
                fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
            } finally {
                fixture.host.main { sampling = false; android.view.Choreographer.getInstance().removeFrameCallback(callback) }
            }
        }
    }

    @Test fun tapsOutsideEveryCircleInsideControlBoundsDismiss() = runBlocking {
        RadialMenuFixture().use { fixture ->
            for (target in listOf("trigger", "search", "collection", "rss", "domain", "newest_first")) {
                fixture.click("trigger")
                val bounds = fixture.node(target).boundsInWindow
                val corner = bounds.bottomRight - Offset(2f, 2f)
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, corner, time)
                try {
                    fixture.send(MotionEvent.ACTION_UP, corner, time)
                    delay(100)
                    fixture.host.main { assertFalse(target, fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
                } finally { fixture.send(MotionEvent.ACTION_CANCEL, corner, time) }
            }
        }
    }
    @Test fun backgroundingCancelsTapOpenMenu() = runBlocking {
        RadialMenuFixture().use { fixture ->
            fixture.click("trigger")
            fixture.node("search")
            fixture.host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME)
            delay(200)
            fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
        }
    }
    @Test fun itemPointerCancelAndAdditionalPointerCloseWithoutAction() = runBlocking {
        RadialMenuFixture().use { fixture ->
            for (additional in listOf(false, true)) {
                fixture.click("trigger")
                val center = fixture.center("search")
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, center, time)
                if (additional) {
                    val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                        id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                    } }
                    val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                        x = center.x + index * 10; y = center.y; pressure = 1f; size = 1f
                    } }
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(fixture.host.instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                    val cancel = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL,
                        2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try {
                        withTimeout(3000) {
                            var closed = false
                            while (!closed) {
                                fixture.host.main { closed = !fixture.state.isOpen; assertTrue(fixture.actions.isEmpty()) }
                                if (!closed) delay(20)
                            }
                        }
                    } finally {
                        try { assertTrue(fixture.host.instrumentation.uiAutomation.injectInputEvent(cancel, true)) }
                        finally { cancel.recycle() }
                    }
                } else fixture.send(MotionEvent.ACTION_CANCEL, center, time)
                delay(100)
                fixture.host.main { assertFalse("additional=$additional", fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
            }
            val trigger = fixture.center("trigger")
            val time = SystemClock.uptimeMillis()
            fixture.send(MotionEvent.ACTION_DOWN, trigger, time)
            fixture.send(MotionEvent.ACTION_MOVE, trigger + Offset(-50f, -50f), time)
            val domain = fixture.center("domain")
            fixture.send(MotionEvent.ACTION_MOVE, domain, time)
            fixture.send(MotionEvent.ACTION_CANCEL, domain, time)
            delay(100)
            fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
        }
    }
    @Test fun geometryAndNamesFitAcrossThemesAndLargeFonts() = runBlocking {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val landscape = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("radialLandscape") == "true"
        if (landscape) assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
        if (landscape) delay(600)
        try {
        for (theme in AppTheme.entries) for (dark in listOf(false, true)) for (blur in listOf(false, true)) {
            RadialMenuFixture(LayoutDirection.Rtl, theme, dark, blur, 2f).use { fixture ->
                if (landscape) withTimeout(4000) {
                    while (fixture.activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) delay(20)
                }
                fixture.click("trigger")
                val nodes = listOf("trigger", "search", "collection", "rss", "domain", "newest_first").map { fixture.node(it) }
                val bounds = nodes.map { it.boundsInWindow }
                val density = fixture.activity.resources.displayMetrics.density
                bounds.forEach { rect ->
                    assertEquals(56f * density, rect.width, 1f)
                    assertTrue("$theme/$dark/$blur left $rect", rect.left >= 0)
                    assertTrue("$theme/$dark/$blur top $rect", rect.top >= 0)
                    assertTrue(rect.right <= fixture.activity.window.decorView.width)
                    assertTrue(rect.bottom <= fixture.activity.window.decorView.height)
                }
                bounds.forEachIndexed { index, rect -> bounds.drop(index + 1).forEach {
                    assertTrue("Circles overlap", (it.center - rect.center).getDistance() > rect.width)
                } }
                for (target in LibraryShortcut.entries) {
                    fixture.host.main { fixture.state.feedback(target) }
                    delay(160)
                    val popup = fixture.host.await(fixture.activity) {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_name"
                    }.boundsInWindow
                    bounds.forEach { circle ->
                        val nearest = Offset(circle.center.x.coerceIn(popup.left, popup.right),
                            circle.center.y.coerceIn(popup.top, popup.bottom))
                        assertTrue("Name overlaps a target: $target/$popup/$circle",
                            (nearest - circle.center).getDistance() >= circle.width / 2)
                    }
                }
                fixture.host.capture("radial-${theme.name}-$dark-$blur")
                fixture.click("trigger")
            }
        }
        } finally { if (landscape) automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
    }
    @Test fun swipeCrossingSearchCommitsOnlyDomainOnceInBothDirections() = runBlocking {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            RadialMenuFixture(direction).use { fixture ->
                val start = fixture.center("trigger")
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, start, time)
                try {
                    delay(650)
                    fixture.host.main { assertFalse("No long-press timer", fixture.state.isOpen) }
                    fixture.send(MotionEvent.ACTION_MOVE, start + Offset(-40f, -40f), time)
                    val search = fixture.center("search")
                    fixture.send(MotionEvent.ACTION_MOVE, search, time)
                    fixture.host.main { assertTrue(fixture.actions.isEmpty()) }
                    val domain = fixture.center("domain")
                    fixture.send(MotionEvent.ACTION_MOVE, domain, time)
                    fixture.send(MotionEvent.ACTION_UP, domain, time)
                    delay(100)
                    fixture.host.main {
                        assertEquals(listOf("DOMAIN"), fixture.actions)
                        assertFalse(fixture.state.isOpen)
                    }
                } finally { fixture.send(MotionEvent.ACTION_CANCEL, start, time) }
            }
        }
    }

    @Test fun gapClosePointerCancelAndLateReleaseNeverExecute() = runBlocking {
        RadialMenuFixture().use { fixture ->
            for (cancelled in listOf(false, true)) {
                val start = fixture.center("trigger")
                val time = SystemClock.uptimeMillis()
                fixture.send(MotionEvent.ACTION_DOWN, start, time)
                try {
                    fixture.send(MotionEvent.ACTION_MOVE, start + Offset(-45f, -45f), time)
                    fixture.node("domain")
                    if (cancelled) fixture.host.main { fixture.state.cancel() }
                    fixture.send(MotionEvent.ACTION_UP, if (cancelled) start + Offset(-200f, -200f) else start, time)
                    delay(100)
                    fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
                } finally { fixture.send(MotionEvent.ACTION_CANCEL, start, time) }
            }
            fixture.click("trigger")
            fixture.click("trigger")
            fixture.host.main { assertFalse(fixture.state.isOpen) }
        }
    }

    @Test fun physicalTapAndOutsideDismissDoNotDispatch() = runBlocking {
        RadialMenuFixture().use { fixture ->
            val start = fixture.center("trigger")
            val time = SystemClock.uptimeMillis()
            fixture.send(MotionEvent.ACTION_DOWN, start, time)
            fixture.send(MotionEvent.ACTION_UP, start, time)
            fixture.node("search")
            fixture.host.main { assertTrue(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
            val outside = Offset(60f, 200f)
            val next = SystemClock.uptimeMillis()
            fixture.send(MotionEvent.ACTION_DOWN, outside, next)
            fixture.send(MotionEvent.ACTION_UP, outside, next)
            delay(150)
            fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
        }
    }
}
