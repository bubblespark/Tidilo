package me.bubblespark.tidilo.data

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import org.junit.Assert.*
import org.junit.Test

/** Measures actual layouts rather than asserting on the geometry implementation. */
class HeaderScaffoldTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun stableGeometryAcrossTitleSizesSubtitlesAndFontScales(): Unit = runBlocking {
        val scale = mutableStateOf(1f)
        val titleSize = mutableStateOf(28)
        val subtitle = mutableStateOf(true)
        var action = Rect.Zero
        var header = Rect.Zero
        var body = Rect.Zero
        var expectedTop = 0f
        var rowHeight = 0f
        var density = 1f
        val activity = host()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                        MaterialTheme {
                            val d = LocalDensity.current
                            density = d.density
                            expectedTop = with(d) { HeaderDefaults.bodyTopPadding().toPx() }
                            rowHeight = with(d) { HeaderDefaults.rowHeight().toPx() }
                            Box(Modifier.size(320.dp, 500.dp)) {
                                TidiloScreenScaffold(header = {
                                    TidiloFrostedHeader(
                                        title = { Text("日本語 Long collection", fontSize = titleSize.value.sp, maxLines = 1) },
                                        modifier = Modifier.onGloballyPositioned { header = it.boundsInRoot() },
                                        subtitle = if (subtitle.value) ({ Text("12 bookmarks") }) else null,
                                        actions = { IconButton({}, Modifier.size(48.dp).onGloballyPositioned { action = it.boundsInRoot() }) { Text("A") } }
                                    )
                                }) { padding ->
                                    Box(Modifier.fillMaxSize().padding(padding)) {
                                        Box(Modifier.size(24.dp).onGloballyPositioned { body = it.boundsInRoot() })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                instrumentation.runOnMainSync { scale.value = fontScale }
                delay(250)
                val centers = mutableListOf<Float>()
                for (size in listOf(28, 23, 20, 17)) {
                    instrumentation.runOnMainSync { titleSize.value = size; subtitle.value = size != 20 }
                    delay(150)
                    instrumentation.runOnMainSync {
                        centers += action.center.y
                        assertEquals(expectedTop, body.top, 1.1f)
                        assertEquals(6f * density, body.top - header.bottom, 1.1f)
                        assertTrue(action.width >= 48f * density - 1f)
                        assertTrue(action.right <= 320f * density)
                        assertTrue(action.top >= header.top)
                        assertTrue(action.bottom <= header.bottom - 18f * density + 1f)
                        if (fontScale == 1f) assertEquals(80f * density, rowHeight, 1.1f)
                    }
                }
                assertEquals(centers.min(), centers.max(), 1f)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun fixedHeaderIgnoresScrollVisibilityButPeekStillSuppressesIt(): Unit = runBlocking {
        val autoHide = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val bars = mutableStateOf(false)
        var attached = false
        var bodyTop = 0f
        val activity = host()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalBarsVisible provides bars.value) {
                            TidiloScreenScaffold(
                                header = {
                                    TidiloFrostedHeader(title = { Text("Settings") })
                                    DisposableEffect(Unit) { attached = true; onDispose { attached = false } }
                                }, hideHeaderOnScroll = autoHide.value, headerVisible = visible.value
                            ) { padding ->
                                Box(Modifier.padding(padding).onGloballyPositioned { bodyTop = it.boundsInRoot().top })
                            }
                        }
                    }
                }
            }
            delay(500)
            assertTrue(attached)
            val initialTop = bodyTop
            instrumentation.runOnMainSync { autoHide.value = true }
            delay(900)
            assertFalse(attached)
            instrumentation.runOnMainSync { bars.value = true }
            delay(900)
            assertTrue(attached)
            instrumentation.runOnMainSync { autoHide.value = false; visible.value = false }
            delay(900)
            assertFalse(attached)
            assertEquals(initialTop, bodyTop, 1f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun host() = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
}
