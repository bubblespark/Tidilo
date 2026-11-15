package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.components.LocalOverlayBlurController
import me.bubblespark.tidilo.ui.components.OverlayBlurController
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.components.AmbientLightingState
import me.bubblespark.tidilo.ui.components.LocalAmbientLighting
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityPickerParentBlurTest {
    @Test fun suppressedParentStillSuppliesLiveContentToThePicker(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var color by mutableStateOf(Color.Green)
        var upper by mutableStateOf(false)
        var controller: OverlayBlurController? = null
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                Box(Modifier.fillMaxSize().background(Color.Red))
                TidiloAlertDialog({}, {}, blurBackground = false, text = {
                    Box(Modifier.fillMaxWidth().height(650.dp).background(color))
                })
                if (upper) me.bubblespark.tidilo.ui.screens.library.EntityTagPickerBottomSheet(
                    emptyList(), listOf("Short fixture"), {}, {}, {}
                )
            } } }
            delay(500)
            host.main { upper = true }
            delay(500)
            val title = host.await(activity) { host.text(it, host.context.getString(R.string.detail_entity_picker_title)) }
            var sampleX = 0
            var sampleY = 0
            host.main {
                sampleX = title.boundsInWindow.center.x.toInt() + 100
                sampleY = title.boundsInWindow.top.toInt() - 20
            }
            fun sample(): Int {
                val frame = host.instrumentation.uiAutomation.takeScreenshot()
                return try {
                    frame.getPixel(sampleX, sampleY)
                } finally { frame.recycle() }
            }
            val before = sample()
            assertTrue("Picker must sample the green parent", android.graphics.Color.green(before) > android.graphics.Color.blue(before))
            var ownership = emptyList<Long>()
            host.main {
                ownership = controller!!.registry.lowerLayers(Long.MAX_VALUE)
                assertEquals(2, ownership.size)
                color = Color.Blue
            }
            delay(700)
            var after = before
            withTimeout(8000) { while (after == before) { delay(30); after = sample() } }
            assertNotEquals("Upper blur must refresh the suppressed parent's changed content", before, after)
            assertTrue("Picker must sample the changed blue parent", android.graphics.Color.blue(after) > android.graphics.Color.green(after))
            host.main { assertEquals(ownership, controller!!.registry.lowerLayers(Long.MAX_VALUE)) }
        } finally { host.main { activity.setContent {}; activity.finish() } }
    }

    @Test fun outsideTapOnShortPickerRestoresParentAppearance(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var picker by mutableStateOf(false)
        var controller: OverlayBlurController? = null
        val tint = Color(0xFFE0EAE8)
        var y = 0
        fun capture(stage: String): Int {
            val frame = host.instrumentation.uiAutomation.takeScreenshot()
            try { java.io.File(host.context.cacheDir, "picker-parent-$stage.png").outputStream().use {
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
                val pixel = frame.getPixel(frame.width / 2 + 80, y)
                // The native sheet's black scrim also fades during dismissal. The sharp
                // magenta foreground measures its opacity independently of the background.
                val opacity = android.graphics.Color.red(frame.getPixel(frame.width / 2, y)) / 255f
                fun undim(shift: Int) = Math.round((pixel ushr shift and 255) / opacity).coerceIn(0, 255)
                return android.graphics.Color.rgb(undim(16), undim(8), undim(0))
            } finally { frame.recycle() }
        }
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                Box(Modifier.fillMaxSize().stripedBackground())
                TidiloAlertDialog({}, {}, containerColor = tint, blurBackground = !picker, text = {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).background(Color.Magenta))
                    }
                })
                if (picker) me.bubblespark.tidilo.ui.screens.library.EntityTagPickerBottomSheet(
                    emptyList(), listOf("Short fixture"), {}, {}, { picker = false }
                )
            } } }
            var initial = 0
            withTimeout(8000) {
                while (y == 0) {
                    val frame = host.instrumentation.uiAutomation.takeScreenshot()
                    try {
                        val x = frame.width / 2
                        val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
                        if (rows.isNotEmpty()) {
                            y = (rows.first() + rows.last()) / 2
                            initial = frame.getPixel(x + 80, y)
                            if (initial == tint.toArgb()) y = 0
                        }
                    } finally { frame.recycle() }
                    if (y == 0) delay(30)
                }
            }
            capture("before")
            host.main { picker = true }
            val title = host.await(activity) { host.text(it, host.context.getString(R.string.detail_entity_picker_title)) }
            delay(500)
            capture("open")
            var bounds = androidx.compose.ui.geometry.Rect.Zero
            host.main { bounds = title.boundsInWindow; assertEquals(2, controller!!.activeCount) }
            val density = host.context.resources.displayMetrics.density
            val tapY = bounds.top - 120 * density
            assertTrue("Fixture must expose scrim above the short sheet", tapY > 100f)
            val down = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, bounds.center.x, tapY, 0)
                    .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                try { check(host.instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            delay(30)
            host.main { assertTrue("Picker stays composed during native closing motion", picker) }
            val closing = capture("closing")
            for (shift in listOf(16, 8, 0)) {
                assertTrue("Parent blur stays suppressed during closing motion",
                    kotlin.math.abs((closing ushr shift and 255) - (tint.toArgb() ushr shift and 255)) <= 2)
            }
            withTimeout(8000) { while (picker) delay(30) }
            withTimeout(8000) {
                var restored = false
                while (!restored) {
                    val frame = host.instrumentation.uiAutomation.takeScreenshot()
                    try {
                        val x = frame.width / 2
                        restored = frame.getPixel(x, y) == Color.Magenta.toArgb() && frame.getPixel(x + 80, y) == initial
                    } finally { frame.recycle() }
                    if (!restored) delay(30)
                }
            }
            host.main { assertEquals(1, controller!!.activeCount) }
            capture("after")
        } finally { host.main { activity.setContent {}; activity.finish() } }
    }

    @Test fun pickerScrollingLeavesParentIdleAndDismissalPreservesDrafts(): Unit = runBlocking {
        val host = HeaderTestHost()
        val fixture = ChromePerformanceFixture(host)
        val db = AppDatabase.getInstance(host.context)
        val prefix = "picker-${UUID.randomUUID()}"
        val ids = (0 until 15).map { "$prefix-$it" }
        var activity = host.launch()
        var controller: OverlayBlurController? = null
        var step = "prepare fixture"
        fun click(node: SemanticsNode) = host.main {
            var owner = node
            while (owner.config.getOrNull(SemanticsActions.OnClick) == null) owner = requireNotNull(owner.parent)
            check(owner.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        suspend fun clickDescription(id: Int) = click(host.await(activity) { host.description(it, host.context.getString(id)) })
        fun containsText(node: SemanticsNode, value: String): Boolean =
            host.text(node, value) || node.children.any { containsText(it, value) }
        fun inDialog(node: SemanticsNode, title: Int): Boolean {
            var root = node
            while (root.parent != null) root = requireNotNull(root.parent)
            return containsText(root, host.context.getString(title))
        }
        suspend fun confirmDialog(title: Int) {
            val node = host.await(activity) { host.text(it, host.context.getString(R.string.common_ok)) && inDialog(it, title) }
            click(node)
        }
        fun hasCandidate(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("Picker fixture") } == true || node.children.any(::hasCandidate)
        fun parentWindow(): android.view.Window {
            fun matches(view: android.view.View): Boolean = when (view) {
                is androidx.compose.ui.platform.ViewRootForTest -> containsText(view.semanticsOwner.unmergedRootSemanticsNode, host.context.getString(R.string.detail_tag_dialog_title))
                is android.view.ViewGroup -> (0 until view.childCount).any { matches(view.getChildAt(it)) }
                else -> false
            }
            val decor = android.view.inspector.WindowInspector.getGlobalWindowViews().first(::matches)
            val field = decor.javaClass.declaredFields.first { android.view.Window::class.java.isAssignableFrom(it.type) }
            field.isAccessible = true
            return field.get(decor) as android.view.Window
        }
        suspend fun awaitLayers(count: Int) = withTimeout(8000) {
            var found = false
            while (!found) {
                host.main { found = controller?.activeCount == count }
                if (!found) delay(30)
            }
        }
        fun render(target: androidx.activity.ComponentActivity) {
            target.setContent { TidiloTheme(theme = AppTheme.MODERN, dynamicColor = false) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalBarsVisible provides true,
                    LocalAmbientLighting provides androidx.compose.runtime.remember { AmbientLightingState() }
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TidiloBackground(Modifier.fillMaxSize())
                        BookmarkDetailScreen(fixture.detailId, fixture.vm, {})
                    }
                }
            } }
        }
        var recreated: androidx.activity.ComponentActivity? = null
        val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(created: android.app.Activity, state: android.os.Bundle?) {
                if (created.javaClass == activity.javaClass && created !== activity) {
                    val target = created as androidx.activity.ComponentActivity
                    render(target)
                    recreated = target
                }
            }
            override fun onActivityCreated(a: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: android.app.Activity) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivityStopped(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        }
        val application = activity.application
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            fixture.prepare()
            db.bookmarkDao().insertAll(ids.mapIndexed { index, id ->
                BookmarkEntity(id, "Picker fixture $index", "https://$prefix.invalid/$index", "Fixture",
                    entities = (1..10).map {
                        when {
                            index == 0 && it == 1 -> "DraftOne"
                            index == 0 && it == 2 -> "DraftTwo"
                            else -> "Picker fixture entity ${index * 10 + it}"
                        }
                    }, createdAt = 1, updatedAt = 1)
            })
            host.main { render(activity) }
            step = "open editor"
            clickDescription(R.string.detail_edit_tags)
            for (name in listOf("DraftOne", "DraftTwo")) {
                step = "add $name"
                clickDescription(R.string.detail_tag_add_entity)
                val field = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null && inDialog(it, R.string.detail_add_entity_title) }
                host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(name)) == true) }
                host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText)?.text == name && inDialog(it, R.string.detail_add_entity_title) }
                awaitLayers(2)
                confirmDialog(R.string.detail_add_entity_title)
                step = "confirm $name"
                awaitLayers(1)
            }
            var parentToken = 0L
            host.main { parentToken = controller!!.registry.lowerLayers(Long.MAX_VALUE).single() }
            repeat(3) { cycle ->
                step = "open picker $cycle"
                clickDescription(R.string.detail_tag_select_entities)
                var scroll = host.await(activity) { it.config.getOrNull(SemanticsActions.ScrollBy) != null && hasCandidate(it) }
                awaitLayers(2)
                if (cycle == 1) {
                    step = "recreate with picker open"
                    val previousController = controller
                    host.main { activity.recreate() }
                    withTimeout(10000) { while (recreated == null) delay(30) }
                    activity = requireNotNull(recreated)
                    withTimeout(10000) {
                        var restored = false
                        while (!restored) {
                            host.main { restored = controller !== previousController && controller?.activeCount == 2 }
                            if (!restored) delay(30)
                        }
                    }
                    scroll = host.await(activity) { it.config.getOrNull(SemanticsActions.ScrollBy) != null && hasCandidate(it) }
                    host.await(activity) { host.text(it, "${host.context.getString(R.string.detail_entity_picker_selected)} (2/10)") }
                }
                host.main { assertTrue(controller!!.registry.lowerLayers(Long.MAX_VALUE).contains(parentToken)) }
                if (cycle == 0) {
                    step = "toggle draft entity"
                    val remove = host.await(activity) { host.description(it, host.context.getString(R.string.detail_tag_remove_entity, "DraftOne")) }
                    click(remove)
                    host.await(activity) { host.text(it, "${host.context.getString(R.string.detail_entity_picker_selected)} (1/10)") }
                    val candidate = host.await(activity) { host.text(it, "DraftOne") && inDialog(it, R.string.detail_entity_picker_title) }
                    click(candidate)
                    step = "verify ten-entity feedback"
                    val extras = (3..10).map { "Picker fixture entity $it" }
                    for (name in extras) {
                        click(host.await(activity) { host.text(it, name) && inDialog(it, R.string.detail_entity_picker_title) })
                    }
                    host.await(activity) { host.text(it, "${host.context.getString(R.string.detail_entity_picker_selected)} (10/10)") }
                    click(host.await(activity) { host.text(it, "Picker fixture entity 11") && inDialog(it, R.string.detail_entity_picker_title) })
                    host.await(activity) { host.text(it, host.context.getString(R.string.detail_tag_entity_limit_inline)) }
                    host.await(activity) { host.text(it, "${host.context.getString(R.string.detail_entity_picker_selected)} (10/10)") }
                    for (name in extras) {
                        click(host.await(activity) { host.description(it, host.context.getString(R.string.detail_tag_remove_entity, name)) })
                    }
                    host.await(activity) { host.text(it, "${host.context.getString(R.string.detail_entity_picker_selected)} (2/10)") }
                }
                delay(700)
                var window: android.view.Window? = null
                host.main { window = parentWindow() }
                val frames = ChromeFrameMetrics(requireNotNull(window))
                step = "scroll picker $cycle"
                frames.start()
                val summary = try {
                    repeat(8) {
                        host.main { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 70f) == true) }
                        delay(100)
                    }
                    frames.stop()
                } catch (failure: Throwable) { frames.stop(); throw failure }
                assertTrue("Picker scrolling must not continuously redraw its parent: ${summary.count} frames", summary.count <= 2)
                host.main { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, -10000f) == true) }
                delay(400)
                when (cycle) {
                    0 -> {
                        confirmDialog(R.string.detail_entity_picker_title)
                    }
                    1 -> check(host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    else -> {
                        val dismiss = host.await(activity) { it.config.getOrNull(SemanticsActions.Dismiss) != null && inDialog(it, R.string.detail_entity_picker_title) }
                        host.main { check(dismiss.config[SemanticsActions.Dismiss].action?.invoke() == true) }
                    }
                }
                step = "dismiss picker $cycle"
                awaitLayers(1)
                host.await(activity) { host.text(it, "DraftOne") }
                host.await(activity) { host.text(it, "DraftTwo") }
                host.main { assertEquals(parentToken, controller!!.registry.lowerLayers(Long.MAX_VALUE).single()) }
            }
        } catch (failure: Throwable) {
            val frame = host.instrumentation.uiAutomation.takeScreenshot()
            try { java.io.File(host.context.cacheDir, "parent-blur-failure.png").outputStream().use {
                frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { frame.recycle() }
            throw AssertionError("Entity picker flow failed at: $step", failure)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            host.main { activity.setContent {}; activity.finish() }
            try { ids.forEach { db.bookmarkDao().deleteById(it) } } finally { fixture.close() }
        }
    }

    @Test fun dialogBlurCanBeSuppressedAndRestoredWithoutLosingItsSource(): Unit = runBlocking {
        for (theme in listOf(AppTheme.DEFAULT, AppTheme.MODERN)) {
            for (dark in listOf(false, true)) verifyDialogRendering(theme, dark)
        }
    }

    private suspend fun verifyDialogRendering(theme: AppTheme, dark: Boolean) {
        val host = HeaderTestHost()
        val activity = host.launch()
        val tint = Color(0xFFE0EAE8)
        var blur by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var controller: OverlayBlurController? = null
        try {
            host.main { activity.setContent { TidiloTheme(theme = theme, darkTheme = dark, dynamicColor = false, blurEnabled = enabled) {
                val current = LocalOverlayBlurController.current
                SideEffect { controller = current }
                Box(Modifier.fillMaxSize().stripedBackground())
                TidiloAlertDialog({}, {}, containerColor = tint, blurBackground = blur, text = {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).background(Color.Magenta))
                    }
                })
            } } }
            suspend fun background(expectPlain: Boolean): Int = withTimeout(8000) {
                while (true) {
                    val frame = host.instrumentation.uiAutomation.takeScreenshot()
                    try {
                        val x = frame.width / 2
                        val rows = (0 until frame.height).filter { frame.getPixel(x, it) == Color.Magenta.toArgb() }
                        if (rows.isNotEmpty()) {
                            val y = (rows.first() + rows.last()) / 2
                            val pixel = frame.getPixel(x + 80, y)
                            if ((pixel == tint.toArgb()) == expectPlain) {
                                assertEquals("Foreground remains sharp", Color.Magenta.toArgb(), frame.getPixel(x, y))
                                return@withTimeout pixel
                            }
                        }
                    } finally { frame.recycle() }
                    delay(30)
                }
                @Suppress("UNREACHABLE_CODE")
                error("No matching background")
            }
            val initial = background(expectPlain = false)
            var ownership = emptyList<Long>()
            host.main {
                ownership = controller!!.registry.lowerLayers(Long.MAX_VALUE)
                assertEquals(1, ownership.size)
                blur = false
            }
            assertEquals(tint.toArgb(), background(expectPlain = true))
            host.main {
                assertEquals("Suppression retains source ownership", ownership, controller!!.registry.lowerLayers(Long.MAX_VALUE))
                blur = true
            }
            assertEquals("Restoring blur restores its captured background", initial, background(expectPlain = false))
            host.main { enabled = false }
            background(expectPlain = true)
            host.main { assertEquals(0, controller!!.activeCount); blur = false; enabled = true }
            background(expectPlain = true)
            host.main { assertEquals(ownership, controller!!.registry.lowerLayers(Long.MAX_VALUE)); blur = true }
            background(expectPlain = false)
        } finally { host.main { activity.setContent {}; activity.finish() } }
    }

    private fun Modifier.stripedBackground(): Modifier = drawBehind {
        val stripe = 8.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawRect(if ((x / stripe).toInt() % 2 == 0) Color.Red else Color.Blue,
                Offset(x, 0f), Size(stripe, size.height))
            x += stripe
        }
    }
}
