package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Measures actual layouts rather than asserting on the geometry implementation. */
class HeaderScaffoldTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mount(content: @Composable () -> Unit) {
        // MainActivity draws edge to edge; header clearance includes the status-bar inset.
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { content() } }
    }

    @Test fun pageHeaderKeepsSubtitleAndActionSlots() {
        var resets = 0
        mount {
            TidiloPageHeader("Debug", {}, subtitle = { Text("Device details") }, actions = {
                IconButton({ resets++ }) { Text("Reset") }
            })
        }
        composeRule.awaitNode(hasText("Device details"))
        composeRule.clickNode(composeRule.awaitNode(hasText("Reset")))
        assertEquals(1, resets)
    }

    @Test fun stableGeometryAcrossTitleSizesSubtitlesAndFontScales() {
        val scale = mutableStateOf(1f)
        val titleSize = mutableStateOf(28)
        val subtitle = mutableStateOf(true)
        var action = Rect.Zero
        var header = Rect.Zero
        var body = Rect.Zero
        var expectedTop = 0f
        var rowHeight = 0f
        var density = 1f
        var titleLeft = 0f
        mount {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                val d = LocalDensity.current
                density = d.density
                expectedTop = with(d) { HeaderDefaults.bodyTopPadding().toPx() }
                rowHeight = with(d) { HeaderDefaults.rowHeight().toPx() }
                Box(Modifier.size(320.dp, 500.dp)) {
                    TidiloScreenScaffold(header = {
                        TidiloFrostedHeader(
                            title = { Text("日本語 Long collection", modifier = Modifier.onGloballyPositioned { titleLeft = it.boundsInRoot().left }, fontSize = titleSize.value.sp, maxLines = 1) },
                            modifier = Modifier.onGloballyPositioned { header = it.boundsInRoot() },
                            subtitle = if (subtitle.value) ({ Text("12 bookmarks") }) else null,
                            navigationIcon = if (titleSize.value == 23) ({
                                IconButton({}, Modifier.padding(end = 4.dp).size(48.dp)) { Text("B") }
                            }) else null,
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
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            composeRule.runOnUiThread { scale.value = fontScale }
            composeRule.waitForIdle()
            val centers = mutableListOf<Float>()
            for (size in listOf(28, 23, 20, 17)) {
                composeRule.runOnUiThread { titleSize.value = size; subtitle.value = size != 20 }
                composeRule.waitForIdle()
                composeRule.runOnUiThread {
                    centers += action.center.y
                    assertEquals(expectedTop, body.top, 1.1f)
                    assertEquals((if (size == 23) 72f else 20f) * density, titleLeft, 1.1f)
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
    }

    @Test fun fixedHeaderIgnoresScrollVisibilityButPeekStillSuppressesIt() {
        val autoHide = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val bars = mutableStateOf(false)
        var attached = false
        var bodyTop = 0f
        mount {
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
        composeRule.waitForIdle()
        assertTrue(attached)
        val initialTop = bodyTop
        composeRule.runOnUiThread { autoHide.value = true }
        composeRule.waitForIdle()
        assertFalse(attached)
        composeRule.runOnUiThread { bars.value = true }
        composeRule.waitForIdle()
        assertTrue(attached)
        composeRule.runOnUiThread { autoHide.value = false; visible.value = false }
        composeRule.waitForIdle()
        assertFalse(attached)
        assertEquals(initialTop, bodyTop, 1f)
    }
}
