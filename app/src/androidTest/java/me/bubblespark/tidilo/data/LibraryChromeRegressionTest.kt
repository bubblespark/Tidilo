package me.bubblespark.tidilo.data

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.navigation.LibrarySearchAction
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class LibraryChromeRegressionTest {
    private suspend fun library(check: suspend (HeaderTestHost, ComponentActivity) -> Unit) {
        val host = HeaderTestHost()
        val activity = host.launch()
        var ready = false
        try {
            host.main {
                val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.DEFAULT) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LibraryScreen(model)
                        Icon(ImageVector.vectorResource(R.drawable.ic_dashboard_2_gear), null,
                            Modifier.align(Alignment.BottomStart).padding(20.dp).size(24.dp).testTag("reference_view_mode"),
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    SideEffect { ready = true }
                } }
            }
            withTimeout(3000) { while (!ready) delay(20) }
            delay(350)
            check(host, activity)
        } finally { host.main { activity.finish() } }
    }

    private fun click(node: SemanticsNode) {
        val target = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    private suspend fun search(check: suspend (HeaderTestHost, ComponentActivity) -> Unit) {
        val host = HeaderTestHost()
        val activity = host.launch()
        var ready = false
        try {
            host.main {
                val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.DEFAULT) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SearchScreen(model)
                    }
                    SideEffect { ready = true }
                } }
            }
            withTimeout(3000) { while (!ready) delay(20) }
            host.instrumentation.waitForIdleSync()
            delay(800)
            host.instrumentation.waitForIdleSync()
            check(host, activity)
        } finally { host.main { activity.finish() } }
    }

    @Test fun searchBackButtonUsesReadableForegroundOnDarkHeader(): Unit = runBlocking {
        search { host, activity ->
            val icon = host.await(activity) { host.description(it, host.context.getString(R.string.common_back)) }
            var offset = androidx.compose.ui.geometry.Offset.Zero
            host.main {
                offset = icon.layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            }
            val size = icon.size
            val bounds = androidx.compose.ui.geometry.Rect(offset, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
            val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
            val centerPixel = bitmap.getPixel((bounds.left + bounds.width / 2).roundToInt(), (bounds.top + bounds.height / 2).roundToInt())
            var foreground = 0
            for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
                for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) >= 50 && android.graphics.Color.green(pixel) >= 50) foreground++
                }
            }
            val title = host.await(activity) { host.text(it, host.context.getString(R.string.search_header_title)) }
            val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val trailingSeparation = title.boundsInWindow.left - button.boundsInWindow.right
            val density = activity.resources.displayMetrics.density
            try {
                assertNotEquals("The back button icon must not be black (0xFF000000) on a dark header",
                    android.graphics.Color.BLACK, centerPixel)
                assertTrue("The back button glyph must contrast with a dark header on SearchScreen (foreground pixels: $foreground)",
                    foreground > 30)
                assertTrue("Back navigation slot must retain 4dp trailing separation (actual: ${trailingSeparation / density}dp)",
                    trailingSeparation >= 3.5f * density)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun menuGlyphUsesReadableForegroundOnDarkHeader(): Unit = runBlocking {
        library { host, activity ->
            val icon = host.await(activity) { host.description(it, host.context.getString(R.string.navigation_open)) }
            var offset = androidx.compose.ui.geometry.Offset.Zero
            host.main {
                offset = icon.layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            }
            val size = icon.size
            val bounds = androidx.compose.ui.geometry.Rect(offset, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
            val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                var foreground = 0
                for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
                    for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180) foreground++
                    }
                }
                assertTrue("The menu glyph must contrast with a dark Library header", foreground > 40)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun viewModeGlyphRetainsTheExistingDashboardAsset(): Unit = runBlocking {
        library { host, activity ->
            val actual = host.await(activity) { host.description(it, host.context.getString(R.string.library_view_mode_menu_desc)) }.boundsInWindow
            val reference = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "reference_view_mode" }.boundsInWindow
            val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                val width = actual.width.roundToInt()
                val height = actual.height.roundToInt()
                var differences = 0
                var referenceInk = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val a = android.graphics.Color.red(bitmap.getPixel(actual.left.roundToInt() + x, actual.top.roundToInt() + y)) > 150
                    val b = android.graphics.Color.red(bitmap.getPixel(reference.left.roundToInt() + x, reference.top.roundToInt() + y)) > 150
                    if (a != b) differences++
                    if (b) referenceInk++
                }
                assertTrue("View mode must render the existing dashboard/gear glyph: $differences differing pixels", differences < referenceInk / 5)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun viewModePopupAlignsToItsHeaderButton(): Unit = runBlocking {
        library { host, activity ->
            val icon = host.await(activity) { host.description(it, host.context.getString(R.string.library_view_mode_menu_desc)) }
            val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val anchor = androidx.compose.ui.geometry.Rect(button.layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero), button.boundsInRoot.size)
            host.main { click(icon) }
            val popup = host.await(activity) { it.config.contains(SemanticsProperties.IsPopup) }
            delay(300)
            val popupBounds = androidx.compose.ui.geometry.Rect(popup.layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero), popup.boundsInRoot.size)
            assertEquals("Popup right edge must align with its trigger", anchor.right, popupBounds.right, 2f)
            assertTrue("Popup must open below the header trigger", popupBounds.top >= anchor.bottom - 2f)
        }
    }

    @Test fun searchRippleDoesNotPaintOutsideItsRoundSurface(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var ready = false
        try {
            host.main { activity.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, theme = AppTheme.DEFAULT) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(64.dp)) { LibrarySearchAction({}) }
                SideEffect { ready = true }
            } } }
            withTimeout(3000) { while (!ready) delay(20) }
            val bounds = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }.boundsInWindow
            delay(400)
            val inset = 3f * activity.resources.displayMetrics.density
            val x = (bounds.left + inset).roundToInt()
            val y = (bounds.top + inset).roundToInt()
            val insideX = bounds.center.x.roundToInt()
            val insideY = (bounds.top + 8f * activity.resources.displayMetrics.density).roundToInt()
            val before = host.instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                try { bitmap.getPixel(x, y) to bitmap.getPixel(insideX, insideY) } finally { bitmap.recycle() }
            }
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.center.x, bounds.center.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(host.instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            send(MotionEvent.ACTION_DOWN)
            val pressed = try {
                delay(400)
                host.instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    try { bitmap.getPixel(x, y) to bitmap.getPixel(insideX, insideY) } finally { bitmap.recycle() }
                }
            } finally { send(MotionEvent.ACTION_UP) }
            val difference = maxOf(abs(android.graphics.Color.red(before.first) - android.graphics.Color.red(pressed.first)),
                abs(android.graphics.Color.green(before.first) - android.graphics.Color.green(pressed.first)),
                abs(android.graphics.Color.blue(before.first) - android.graphics.Color.blue(pressed.first)))
            assertTrue("The pointer must actually press and paint the action", before.second != pressed.second)
            assertTrue("Pressed ripple leaked into the square corner outside the round Surface ($difference)", difference <= 5)
        } finally { host.main { activity.finish() } }
    }
}
