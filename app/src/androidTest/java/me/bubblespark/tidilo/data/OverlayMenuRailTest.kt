package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMenuRailTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun rtlRailFrostFollowsNativeTransformsDuringCollapse(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        lateinit var state: WideNavigationRailState
        lateinit var scope: CoroutineScope
        try {
            host.main { activity.setContent { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TidiloTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                    state = rememberWideNavigationRailState()
                    scope = rememberCoroutineScope()
                    LaunchedEffect(state) { state.expand() }
                    LibraryNavigationRail(state, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {}, Modifier.testTag("rtl-rail"))
                }
            } } }
            val rail = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "rtl-rail" }
            val title = host.await(activity) { host.text(it, host.context.getString(me.bubblespark.tidilo.R.string.app_name)) }
            delay(500)
            host.main {
                val names = rail.layoutInfo.getModifierInfo().map { it.modifier.javaClass.simpleName }
                assertTrue("Native RTL anchors and predictive-back layers must wrap frost: $names",
                    names.indexOfLast { it.contains("GraphicsLayer") } < names.indexOfFirst { it.contains("TypedHazeEffect") })
                assertTrue("RTL movement must not be reimplemented with mirrored pixel offsets", names.none { it == "OffsetPxElement" })
                scope.launch { state.collapse() }
            }
            delay(70)
            var left = -1f
            host.main {
                // The tag is on the caller modifier before native anchors. The actual content
                // coordinates include native RTL placement and the current anchored translation.
                val positions = generateSequence(title.layoutInfo.coordinates) { it.parentLayoutCoordinates }
                    .filter { it.isAttached }.map { it.localToScreen(Offset.Zero).x }.toList()
                left = positions.filter { it > 1f }.minOrNull() ?: -1f
            }
            val frame = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                val sampleX = if (left > 64) (left.toInt() - 64).coerceAtMost(frame.width - 1) else frame.width / 2
                java.io.File(host.context.cacheDir, "rtl-collapse.png").outputStream().use {
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertEquals("Frost must leave the scrim as the RTL rail moves right",
                    frame.getPixel(20, frame.height / 2), frame.getPixel(sampleX, frame.height / 2))
            } finally { frame.recycle() }
        } finally { host.main { activity.finish() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun expandedRailUsesFrostedBackground() = verify {
        val state = rememberWideNavigationRailState()
        LaunchedEffect(Unit) { state.expand() }
        LibraryNavigationRail(state, LibraryFilterMode.NEWEST_FIRST, {}, {}, {}, {}, {})
    }

    @Test fun popupUsesFrostedBackground() = verify {
        Box { TidiloDropdownMenu(expanded = true, onDismissRequest = {}) {
            repeat(5) { index -> DropdownMenuItem(text = { Text("Menu item $index") }, onClick = {}) }
        } }
    }

    private fun verify(overlay: @Composable () -> Unit): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize().background(Color(0xFF003399)))
                overlay()
            } } }
            delay(1000)
            val before = host.instrumentation.uiAutomation.takeScreenshot()
            host.main { enabled = true }
            delay(600)
            val after = host.instrumentation.uiAutomation.takeScreenshot()
            try {
                var changed = 0
                for (y in after.height / 8 until after.height * 7 / 8 step 4) {
                    for (x in 20 until after.width / 2 step 4) {
                        if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                    }
                }
                assertTrue("Enabling frost must change the popup or rail background", changed > 1000)
            } finally { before.recycle(); after.recycle() }
        } finally { host.main { activity.finish() } }
    }
}
