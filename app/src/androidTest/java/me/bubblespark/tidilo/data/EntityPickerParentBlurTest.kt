package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.components.AmbientLightingState
import me.bubblespark.tidilo.ui.components.LocalAmbientLighting
import me.bubblespark.tidilo.ui.components.LocalOverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurController
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.EntityTagPickerBottomSheet
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EntityPickerParentBlurTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun suppressedParentStillSuppliesLiveContentToThePicker(): Unit = runBlocking {
        var color by mutableStateOf(Color.Green)
        var upper by mutableStateOf(false)
        var candidates by mutableStateOf(listOf("Short fixture"))
        var controller: OverlayBlurController? = null
        composeRule.setContent {
            TidiloTheme(dynamicColor = false) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                Box(Modifier.fillMaxSize().background(Color.Red))
                TidiloAlertDialog({}, {}, blurBackground = false, text = {
                    Box(Modifier.fillMaxWidth().height(650.dp).background(color))
                })
                if (upper) EntityTagPickerBottomSheet(emptyList(), candidates, {}, {}, {})
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { upper = true }
        val title = composeRule.awaitNode(hasText(string(R.string.detail_entity_picker_title)))
        // Screenshots are in screen pixels; the sheet's window need not start at the screen origin.
        val sampleX = (title.positionOnScreen.x + title.size.width / 2).toInt() + 100
        val sampleY = title.positionOnScreen.y.toInt() - 20
        val before = sampleScreen("the picker over the parent") { it.getPixel(sampleX, sampleY) }
        assertTrue("Picker must sample the green parent", android.graphics.Color.green(before) > android.graphics.Color.blue(before))
        var ownership = emptyList<Long>()
        composeRule.runOnUiThread {
            ownership = requireController(controller).registry.lowerLayers(Long.MAX_VALUE)
            assertEquals(2, ownership.size)
            color = Color.Blue
        }
        composeRule.waitForIdle()
        // The picker re-samples its parent when it redraws, as when toggling an entity changes both.
        composeRule.runOnUiThread { candidates = listOf("Short fixture", "Second fixture") }
        val after = sampleScreen("the refreshed blue parent", { "#%08x (before #%08x)".format(it.getPixel(sampleX, sampleY), before) }) { frame ->
            frame.getPixel(sampleX, sampleY).takeIf { android.graphics.Color.blue(it) > android.graphics.Color.green(it) }
        }
        assertNotEquals("Upper blur must refresh the suppressed parent's changed content", before, after)
        composeRule.runOnUiThread { assertEquals(ownership, requireController(controller).registry.lowerLayers(Long.MAX_VALUE)) }
    }

    @Test fun outsideTapOnShortPickerRestoresParentAppearance(): Unit = runBlocking {
        var picker by mutableStateOf(false)
        var controller: OverlayBlurController? = null
        val tint = Color(0xFFE0EAE8)
        composeRule.setContent {
            TidiloTheme(dynamicColor = false) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                Box(Modifier.fillMaxSize().stripedBackground())
                TidiloAlertDialog({}, {}, containerColor = tint, blurBackground = !picker, text = {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).background(Color.Magenta))
                    }
                })
                if (picker) EntityTagPickerBottomSheet(emptyList(), listOf("Short fixture"), {}, {}, { picker = false })
            }
        }
        // The magenta marker's row, and the blurred parent beside it.
        val (y, initial) = sampleScreen("the blurred parent beside the marker") { frame ->
            val x = frame.width / 2
            val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
            if (rows.isEmpty()) return@sampleScreen null
            val row = (rows.first() + rows.last()) / 2
            frame.getPixel(x + 80, row).takeIf { it != tint.toArgb() }?.let { row to it }
        }
        composeRule.runOnUiThread { picker = true }
        val title = composeRule.awaitNode(hasText(string(R.string.detail_entity_picker_title)))
        val bounds = title.boundsInWindow
        composeRule.runOnUiThread { assertEquals(2, requireController(controller).activeCount) }
        val density = composeRule.activity.resources.displayMetrics.density
        val tapY = bounds.top - 120 * density
        assertTrue("Fixture must expose scrim above the short sheet", tapY > 100f)
        val down = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, bounds.center.x, tapY, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        composeRule.runOnUiThread { assertTrue("Picker stays composed during native closing motion", picker) }
        // The native sheet's black scrim also fades during dismissal. The sharp
        // magenta foreground measures its opacity independently of the background.
        val closing = captureScreen { frame ->
            val pixel = frame.getPixel(frame.width / 2 + 80, y)
            val opacity = android.graphics.Color.red(frame.getPixel(frame.width / 2, y)) / 255f
            fun undim(shift: Int) = Math.round((pixel ushr shift and 255) / opacity).coerceIn(0, 255)
            android.graphics.Color.rgb(undim(16), undim(8), undim(0))
        }
        for (shift in listOf(16, 8, 0)) {
            assertTrue("Parent blur stays suppressed during closing motion",
                kotlin.math.abs((closing ushr shift and 255) - (tint.toArgb() ushr shift and 255)) <= 2)
        }
        composeRule.waitUntil(8_000) { !picker }
        sampleScreen("the restored parent") { frame ->
            val x = frame.width / 2
            (frame.getPixel(x, y) == Color.Magenta.toArgb() && frame.getPixel(x + 80, y) == initial).takeIf { it }
        }
        composeRule.runOnUiThread { assertEquals(1, requireController(controller).activeCount) }
    }

    @Test fun pickerScrollingLeavesParentIdleAndDismissalPreservesDrafts() = withEntityPickerLibrary { library, detailId ->
        val picker = EntityPickerDriver(composeRule)
        val model = library.viewModel()
        composeRule.setContent { EntityPickerDetailContent(detailId, model) { picker.controller = it } }
        picker.addDrafts()
        val parentToken = picker.parentToken()
        for (closeWith in listOf("OK", "Dismiss")) {
            picker.open()
            val scroll = picker.awaitCandidateList()
            composeRule.runOnUiThread { assertTrue(picker.requireController().registry.lowerLayers(Long.MAX_VALUE).contains(parentToken)) }
            if (closeWith == "OK") {
                click(composeRule.awaitNode(hasContentDescription(string(R.string.detail_tag_remove_entity, "DraftOne")) and picker.inPicker))
                picker.awaitSelected(1)
                click(composeRule.awaitNode(hasText("DraftOne") and picker.inPicker))
                picker.awaitSelected(2)
                val extras = (3..10).map { "Picker fixture entity $it" }
                for ((index, name) in extras.withIndex()) {
                    click(composeRule.awaitNode(hasText(name) and picker.inPicker))
                    picker.awaitSelected(index + 3)
                }
                click(composeRule.awaitNode(hasText("Picker fixture entity 11") and picker.inPicker))
                composeRule.awaitNode(hasText(string(R.string.detail_tag_entity_limit_inline)))
                picker.awaitSelected(10)
                for ((index, name) in extras.withIndex()) {
                    click(composeRule.awaitNode(hasContentDescription(string(R.string.detail_tag_remove_entity, name)) and picker.inPicker))
                    picker.awaitSelected(9 - index)
                }
            }
            composeRule.waitForIdle()
            val range = checkNotNull(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            assertTrue("The picker fixture must be scrollable", range.maxValue() > 0f)
            val frames = ChromeFrameMetrics(parentWindow())
            frames.start()
            val summary = try {
                repeat(8) {
                    composeRule.runOnUiThread { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 70f) == true) }
                    // Spaced input lets the frame metrics see each scroll step separately.
                    delay(100)
                }
                frames.stop()
            } catch (failure: Throwable) { frames.stop(); throw failure }
            assertTrue("Picker scrolling must not continuously redraw its parent: ${summary.count} frames", summary.count <= 2)
            composeRule.runOnUiThread { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, -10000f) == true) }
            composeRule.waitForIdle()
            if (closeWith == "OK") {
                click(composeRule.awaitNode(hasText(string(R.string.common_ok)) and picker.inPicker))
            } else {
                val dismiss = composeRule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss) and picker.inPicker)
                composeRule.runOnUiThread { check(dismiss.config[SemanticsActions.Dismiss].action?.invoke() == true) }
            }
            picker.assertClosedWithDrafts(parentToken)
        }
    }

    @Test fun dialogBlurCanBeSuppressedAndRestoredWithoutLosingItsSource(): Unit = runBlocking {
        val tint = Color(0xFFE0EAE8)
        var variant by mutableStateOf(AppTheme.DEFAULT to false)
        var blur by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var controller: OverlayBlurController? = null
        composeRule.setContent {
            key(variant) {
                TidiloTheme(theme = variant.first, darkTheme = variant.second, dynamicColor = false, blurEnabled = enabled) {
                    val current = LocalOverlayBlurController.current
                    SideEffect { controller = current }
                    Box(Modifier.fillMaxSize().stripedBackground())
                    TidiloAlertDialog({}, {}, containerColor = tint, blurBackground = blur, text = {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(16.dp).background(Color.Magenta))
                        }
                    })
                }
            }
        }
        /** The parent pixel beside the sharp magenta marker once it is (or is not) the plain dialog tint. */
        suspend fun background(step: String, expectPlain: Boolean, expected: Int? = null): Int = sampleScreen(
            "$step: a ${if (expectPlain) "plain" else "blurred"} parent${expected?.let { " #%08x".format(it) } ?: ""}",
            { frame ->
                val x = frame.width / 2
                val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
                if (rows.isEmpty()) "no marker" else "#%08x".format(frame.getPixel(x + 80, (rows.first() + rows.last()) / 2))
            }
        ) { frame ->
            val x = frame.width / 2
            val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
            if (rows.isEmpty()) return@sampleScreen null
            val y = (rows.first() + rows.last()) / 2
            val pixel = frame.getPixel(x + 80, y)
            if ((pixel == tint.toArgb()) != expectPlain || (expected != null && pixel != expected)) return@sampleScreen null
            assertEquals("Foreground remains sharp", Color.Magenta.toArgb(), frame.getPixel(x, y))
            pixel
        }
        for (target in listOf(AppTheme.DEFAULT to false, AppTheme.MODERN to true)) {
            composeRule.runOnUiThread { variant = target; blur = true; enabled = true }
            val initial = background("initial", expectPlain = false)
            var ownership = emptyList<Long>()
            composeRule.runOnUiThread {
                ownership = requireController(controller).registry.lowerLayers(Long.MAX_VALUE)
                assertEquals(1, ownership.size)
                blur = false
            }
            assertEquals(tint.toArgb(), background("suppressed", expectPlain = true))
            composeRule.runOnUiThread {
                assertEquals("Suppression retains source ownership", ownership, requireController(controller).registry.lowerLayers(Long.MAX_VALUE))
            }
            // Step virtual time so the restoration is caught mid-animation instead of racing it.
            composeRule.mainClock.autoAdvance = false
            val intermediate = try {
                composeRule.runOnUiThread { blur = true }
                // Half of the 300ms restoration tween.
                composeRule.mainClock.advanceTimeBy(150)
                background("intermediate", expectPlain = false)
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            assertNotEquals("Restoration must show an intermediate background, not jump to full blur", initial, intermediate)
            composeRule.runOnUiThread { blur = false }
            background("interrupted", expectPlain = true)
            composeRule.runOnUiThread {
                assertEquals("Interrupted restoration retains source ownership", ownership,
                    requireController(controller).registry.lowerLayers(Long.MAX_VALUE))
                blur = true
            }
            assertEquals("Restoring blur restores its captured background", initial, background("restored", expectPlain = false, expected = initial))
            composeRule.runOnUiThread { enabled = false }
            background("disabled", expectPlain = true)
            composeRule.runOnUiThread { assertEquals(0, requireController(controller).activeCount); blur = false; enabled = true }
            background("re-enabled suppressed", expectPlain = true)
            composeRule.runOnUiThread { assertEquals(ownership, requireController(controller).registry.lowerLayers(Long.MAX_VALUE)); blur = true }
            background("final", expectPlain = false, expected = initial)
        }
    }

    private fun string(id: Int, vararg args: Any) = composeRule.activity.getString(id, *args)

    private fun click(node: SemanticsNode) = composeRule.clickNode(node)

    /** One screenshot after idle; the bitmap is recycled before returning. */
    private fun <T> captureScreen(read: (Bitmap) -> T): T {
        val frame = automation.takeScreenshot()
        return try { read(frame) } finally { frame.recycle() }
    }

    /**
     * Samples the screen at most 10 times 200ms apart until [read] returns a value. Each sample
     * follows waitForIdle(), which also advances the test clock that drives Haze's frame work.
     */
    private suspend fun <T : Any> sampleScreen(
        label: String,
        describe: (Bitmap) -> String = { "" },
        advanceMillis: Long = 0,
        read: (Bitmap) -> T?
    ): T {
        var last = ""
        repeat(10) { attempt ->
            if (advanceMillis > 0) composeRule.mainClock.advanceTimeBy(advanceMillis)
            composeRule.waitForIdle()
            captureScreen { frame -> read(frame).also { if (it == null) last = describe(frame) } }?.let { return it }
            if (attempt < 9) delay(200)
        }
        throw AssertionError("The screen never showed $label. Last sample: $last")
    }

    /** The window that hosts the tag dialog (the picker's parent), for frame metrics. */
    private fun parentWindow(): android.view.Window {
        var window: android.view.Window? = null
        composeRule.runOnUiThread {
            val title = string(R.string.detail_tag_dialog_title)
            fun contains(node: SemanticsNode): Boolean =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true || node.children.any(::contains)
            fun matches(view: android.view.View): Boolean = when (view) {
                is ViewRootForTest -> contains(view.semanticsOwner.unmergedRootSemanticsNode)
                is android.view.ViewGroup -> (0 until view.childCount).any { matches(view.getChildAt(it)) }
                else -> false
            }
            val decor = android.view.inspector.WindowInspector.getGlobalWindowViews().first(::matches)
            val field = decor.javaClass.declaredFields.first { android.view.Window::class.java.isAssignableFrom(it.type) }
            field.isAccessible = true
            window = field.get(decor) as android.view.Window
        }
        return requireNotNull(window)
    }

    private fun requireController(controller: OverlayBlurController?): OverlayBlurController =
        requireNotNull(controller) { "Overlay blur controller was not initialized" }

    private fun Modifier.stripedBackground(): Modifier = drawBehind {
        val stripe = 8.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawRect(if ((x / stripe).toInt() % 2 == 0) Color.Red else Color.Blue,
                Offset(x, 0f), Size(stripe, size.height))
            x += stripe
        }
    }

    private fun withEntityPickerLibrary(block: suspend (InMemoryLibrary, String) -> Unit) =
        withEntityPickerLibrary(composeRule, block)
}

internal typealias EntityPickerRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

internal fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher): SemanticsNode {
    waitUntil(8_000) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    return onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first()
}

/** Matches nodes whose window (a dialog, sheet, or the activity) shows [text] anywhere. */
internal fun inWindowShowing(text: String) = SemanticsMatcher("in window showing $text") { node ->
    var root = node
    while (root.parent != null) root = requireNotNull(root.parent)
    val pending = mutableListOf(root)
    var found = false
    while (pending.isNotEmpty() && !found) {
        val next = pending.removeAt(pending.lastIndex)
        found = next.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        pending.addAll(next.children)
    }
    found
}

/** Invokes the click action of [node] or its nearest clickable ancestor, then waits for idle. */
internal fun ComposeTestRule.clickNode(node: SemanticsNode) {
    val click = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
    runOnUiThread { check(click()) }
    waitForIdle()
}

/**
 * Runs [block] against an in-memory Library with offline caches, seeded with a Detail bookmark
 * and 40 picker candidates across four bookmarks (`DraftOne`, `DraftTwo`, `Picker fixture entity 3..40`):
 * the fewest that still make the picker sheet scroll.
 */
internal fun withEntityPickerLibrary(rule: EntityPickerRule, block: suspend (InMemoryLibrary, String) -> Unit): Unit = runBlocking {
    val detailId = "picker-detail"
    val caches = OfflineVisualCaches()
    val library = withContext(Dispatchers.IO) { InMemoryLibrary(rule.activity.applicationContext).also { it.awaitReady() } }
    try {
        val candidates = listOf("DraftOne", "DraftTwo") + (3..40).map { "Picker fixture entity $it" }
        val sources = candidates.chunked(10).mapIndexed { index, entities ->
            BookmarkEntity("picker-source-$index", "Picker source $index", "https://picker.invalid/$index", "Fixture",
                entities = entities, createdAt = 2L + index, updatedAt = 1)
        }
        withContext(Dispatchers.IO) {
            library.db.bookmarkDao().insertAll(sources +
                BookmarkEntity(detailId, "Picker detail", "https://picker.invalid/detail", "Fixture", createdAt = 1, updatedAt = 1))
        }
        block(library, detailId)
    } finally {
        caches.close()
        library.close()
    }
}

/** The Detail screen with blur on, as the picker's parent dialog shows it in the app. */
@Composable
internal fun EntityPickerDetailContent(detailId: String, viewModel: LibraryViewModel, onController: (OverlayBlurController?) -> Unit) {
    TidiloTheme(theme = AppTheme.MODERN, dynamicColor = false) {
        val current = LocalOverlayBlurController.current
        SideEffect { onController(current) }
        CompositionLocalProvider(
            LocalBarsVisible provides true,
            LocalAmbientLighting provides remember { AmbientLightingState() }
        ) {
            Box(Modifier.fillMaxSize()) {
                TidiloBackground(Modifier.fillMaxSize())
                BookmarkDetailScreen(detailId, viewModel, {})
            }
        }
    }
}

/** Drives the tag dialog and entity picker of [EntityPickerDetailContent] through Compose semantics. */
internal class EntityPickerDriver(private val rule: EntityPickerRule) {
    var controller: OverlayBlurController? = null
    private fun string(id: Int) = rule.activity.getString(id)

    fun requireController(): OverlayBlurController = requireNotNull(controller) { "Overlay blur controller was not initialized" }

    /** Matches nodes in the window whose root shows [title] (a dialog or sheet). */
    fun inWindowTitled(title: Int) = inWindowShowing(string(title))

    val inPicker get() = inWindowTitled(R.string.detail_entity_picker_title)

    fun awaitLayers(count: Int) = rule.waitUntil(8_000) {
        var active = -1
        rule.runOnUiThread { active = controller?.activeCount ?: -1 }
        active == count
    }

    fun awaitSelected(count: Int) =
        rule.awaitNode(hasText("${string(R.string.detail_entity_picker_selected)} ($count/10)"))

    /** Opens the tag editor and adds `DraftOne` and `DraftTwo` through the Add Entity dialog. */
    fun addDrafts() {
        rule.clickNode(rule.awaitNode(hasContentDescription(string(R.string.detail_edit_tags))))
        for (name in listOf("DraftOne", "DraftTwo")) {
            rule.clickNode(rule.awaitNode(hasContentDescription(string(R.string.detail_tag_add_entity))))
            val inAddDialog = inWindowTitled(R.string.detail_add_entity_title)
            val field = rule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText) and inAddDialog)
            rule.runOnUiThread { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(name)) == true) }
            rule.awaitNode(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(name)) and inAddDialog)
            awaitLayers(2)
            rule.clickNode(rule.awaitNode(hasText(string(R.string.common_ok)) and inAddDialog))
            awaitLayers(1)
        }
    }

    fun parentToken(): Long {
        var token = 0L
        rule.runOnUiThread { token = requireController().registry.lowerLayers(Long.MAX_VALUE).single() }
        return token
    }

    fun open() {
        rule.clickNode(rule.awaitNode(hasContentDescription(string(R.string.detail_tag_select_entities))))
        awaitLayers(2)
    }

    /** The picker's scrollable candidate list. */
    fun awaitCandidateList(): SemanticsNode {
        fun hasCandidate(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Picker fixture") } == true ||
                node.children.any(::hasCandidate)
        return rule.awaitNode(SemanticsMatcher("candidate list") {
            it.config.getOrNull(SemanticsActions.ScrollBy) != null && hasCandidate(it)
        })
    }

    fun assertClosedWithDrafts(parentToken: Long) {
        awaitLayers(1)
        rule.awaitNode(hasText("DraftOne"))
        rule.awaitNode(hasText("DraftTwo"))
        rule.runOnUiThread { assertEquals(parentToken, requireController().registry.lowerLayers(Long.MAX_VALUE).single()) }
    }

    /** System Back reaches the focused window, so it closes the picker sheet first. */
    fun pressSystemBack() {
        check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        rule.waitForIdle()
    }
}
