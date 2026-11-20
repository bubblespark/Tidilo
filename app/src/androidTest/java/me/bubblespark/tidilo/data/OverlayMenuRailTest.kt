package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class OverlayMenuRailTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun rtlRailFrostFollowsNativeTransformsDuringCollapse(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val motionScope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        val recomposer = Recomposer(motionScope.coroutineContext + object : MotionDurationScale {
            // Leave time to capture a rendered intermediate position on slower emulators.
            override val scaleFactor = 4f
        })
        lateinit var state: WideNavigationRailState
        lateinit var scope: CoroutineScope
        try {
            motionScope.launch { recomposer.runRecomposeAndApplyChanges() }
            host.main {
                activity.setContent(parent = recomposer) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        TidiloTheme(dynamicColor = false, darkTheme = false) {
                            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(outlineVariant = Color.Magenta)) {
                                Box(Modifier.fillMaxSize().background(Color.Blue))
                                state = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
                                scope = rememberCoroutineScope()
                                LibraryNavigationRail(state, LibraryFilterMode.NEWEST_FIRST,
                                    {}, {}, {}, {}, {}, Modifier.testTag("rtl-rail"))
                            }
                        }
                    }
                }
            }
            val rail = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "rtl-rail" }
            host.main {
                val names = rail.layoutInfo.getModifierInfo().map { it.modifier.javaClass.simpleName }
                val frost = names.indexOfFirst { it.contains("TypedHazeEffect") }
                val nativeLayer = names.indexOfLast { it.contains("GraphicsLayer") }
                assertTrue("Native RTL anchors and predictive-back layers must wrap frost: $names",
                    nativeLayer >= 0 && frost > nativeLayer)
                assertTrue("RTL movement must not be reimplemented with mirrored pixel offsets", names.none { it == "OffsetPxElement" })
            }
            val density = activity.resources.displayMetrics.density
            val margin = (24 * density).roundToInt()
            val (expanded, expandedDivider) = awaitRailFrame(host, "expanded RTL rail") { bitmap, marker ->
                // Wait for RTL placement and a divider that is fully inside the screen.
                marker.x >= margin && bitmap.getPixel(bitmap.width - 1, marker.y) != Color.Magenta.toArgb()
            }
            val y = expandedDivider.y - (4 * density).roundToInt()
            val expandedLeft: Int
            val probeX: Int
            try {
                saveRailFrame(host, expanded, "rtl-expanded.png")
                val scrim = expanded.getPixel(0, y)
                expandedLeft = (1 until expandedDivider.x).firstOrNull { expanded.getPixel(it, y) != scrim }
                    ?: throw AssertionError("Expanded rail has no visible edge before divider $expandedDivider")
                probeX = expandedLeft + margin
                assertTrue("Probe must start inside the expanded rail: x=$probeX y=$y left=$expandedLeft",
                    expanded.getPixel(probeX, y) != scrim)
            } finally {
                expanded.recycle()
            }

            host.main { scope.launch { state.collapse() } }
            // The header divider is foreground. Its displacement locates the moving rail even
            // if the frost is left behind, and comes from the same image as the scrim pixels.
            val (frame, divider) = awaitRailFrame(host, "collapsing rail from $expandedDivider") { bitmap, marker ->
                val left = expandedLeft + marker.x - expandedDivider.x
                marker.y == expandedDivider.y && left >= probeX + margin && left + margin < bitmap.width
            }
            try {
                saveRailFrame(host, frame, "rtl-collapse.png")
                val left = expandedLeft + divider.x - expandedDivider.x
                val scrim = frame.getPixel(0, y)
                assertEquals("Frost must leave the scrim at ($probeX, $y), rail edge=$left",
                    scrim, frame.getPixel(probeX, y))
                assertTrue("The moving rail must still be drawn at (${left + margin}, $y)",
                    frame.getPixel(left + margin, y) != scrim)
            } finally { frame.recycle() }
        } finally {
            host.main {
                activity.setContent {}
                activity.finish()
                recomposer.cancel()
                motionScope.cancel()
            }
        }
    }

    private suspend fun awaitRailFrame(
        host: HeaderTestHost,
        description: String,
        accept: (Bitmap, IntOffset) -> Boolean,
    ): Pair<Bitmap, IntOffset> {
        var lastDivider: IntOffset? = null
        val frame = withTimeoutOrNull(5_000) {
            while (isActive) {
                val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    val divider = findRailDivider(bitmap)
                    if (divider != null) {
                        lastDivider = divider
                        if (accept(bitmap, divider)) return@withTimeoutOrNull bitmap to divider
                    }
                    bitmap.recycle()
                }
                delay(16)
            }
            null
        }
        return frame ?: throw AssertionError("No rendered frame for $description. Last divider=$lastDivider")
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

    private fun saveRailFrame(host: HeaderTestHost, bitmap: Bitmap, filename: String) {
        File(host.context.cacheDir, filename).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
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
