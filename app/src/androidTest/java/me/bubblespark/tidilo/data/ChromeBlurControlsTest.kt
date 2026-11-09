package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.screenCanvasSource
import androidx.compose.foundation.background
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.navigation.LibrarySearchAction
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.theme.LocalOverlayBlurSetting
import me.bubblespark.tidilo.ui.theme.OverlayBlurPolicy
import me.bubblespark.tidilo.ui.theme.resolveOverlayBlur
import org.junit.Assert.*
import org.junit.Test

class ChromeBlurControlsTest {
    @Test fun realDetailToolbarAndBrowserActionSampleCanvas() = runBlocking {
        val host = HeaderTestHost()
        val fixture = ChromePerformanceFixture(host)
        var activity: androidx.activity.ComponentActivity? = null
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        try {
            fixture.prepare()
            val screen = host.launch()
            activity = screen
            host.main { screen.setContent { TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().screenCanvasSource().background(body))
                    BookmarkDetailScreen(fixture.detailId, fixture.vm, {})
                }
            } } }
            suspend fun pixels(): List<Int> {
                val nodes = listOf(me.bubblespark.tidilo.R.string.detail_action_ai_summary, me.bubblespark.tidilo.R.string.bookmark_open_in_browser).map { resource ->
                    host.await(screen) { host.description(it, host.context.getString(resource)) }
                }
                val points = nodes.map { node ->
                    var click = node
                    while (click.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null && click.parent != null) click = requireNotNull(click.parent)
                    click.boundsInWindow
                }
                val image = host.instrumentation.uiAutomation.takeScreenshot()
                return try { points.map { image.getPixel(it.center.x.toInt(), (it.top + it.height * .14f).toInt()) } }
                finally { image.recycle() }
            }
            for (preset in AppTheme.entries) for (night in listOf(false, true)) {
                host.main { theme = preset; dark = night; enabled = false; body = Color.Red }
                delay(500)
                val off = pixels()
                host.main { body = Color.Blue }
                delay(300)
                assertEquals("OFF surfaces retain opaque theme drawing for $preset/$night", off, pixels())
                host.main { enabled = true }
                delay(500)
                val on = pixels()
                host.main { body = Color.Red }
                delay(300)
                val changed = pixels()
                on.indices.forEach { assertNotEquals("Real Detail consumer $it must sample its canvas for $preset/$night", on[it], changed[it]) }
            }
        } finally {
            activity?.let { screen -> host.main { screen.setContent {}; screen.finish() } }
            fixture.close()
        }
    }

    @Test fun realSearchActionUsesBodyInBothThemesAndRetainsClick() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(false)
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var dark by mutableStateOf(false)
        var body by mutableStateOf(Color.Red)
        var bounds = Rect.Zero
        var clicks = 0
        var policy by mutableStateOf(OverlayBlurPolicy.USER_CHOICE)
        try {
            host.main { activity.setContent { TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme, blurEnabled = enabled) {
                CompositionLocalProvider(LocalOverlayBlurSetting provides resolveOverlayBlur(policy, enabled)) {
                TidiloScreenScaffold(header = {}, overlay = {
                    LibrarySearchAction({ clicks++ }, Modifier.align(Alignment.Center).onGloballyPositioned { bounds = it.boundsInWindow() })
                }) { Box(Modifier.fillMaxSize().drawBehind { drawRect(body) }) }
                }
            } } }
            fun pixel(): Int {
                val frame = host.instrumentation.uiAutomation.takeScreenshot()
                return try { frame.getPixel((bounds.left + bounds.width * .25f).toInt(), (bounds.top + bounds.height * .4f).toInt()) }
                finally { frame.recycle() }
            }
            for (preset in AppTheme.entries) for (night in listOf(false, true)) {
                host.main { theme = preset; dark = night; enabled = false; body = Color.Red }
                delay(350)
                val plain = pixel()
                host.main { body = Color.Blue }
                delay(150)
                assertEquals("OFF retains the themed opaque surface", plain, pixel())
                host.main { enabled = true }
                delay(350)
                val blue = pixel()
                host.main { body = Color.Red }
                delay(250)
                assertNotEquals("Search must sample its body for $preset/$night", blue, pixel())
                val node = host.await(activity) { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.nav_search)) }
                host.main {
                    var clickable = node
                    while (clickable.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null && clickable.parent != null) clickable = requireNotNull(clickable.parent)
                    check(clickable.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true)
                }
            }
            assertEquals(AppTheme.entries.size * 2, clicks)
            for (fixed in listOf(OverlayBlurPolicy.FORCE_ON, OverlayBlurPolicy.FORCE_OFF)) for (saved in listOf(false, true)) {
                host.main { policy = fixed; enabled = saved; body = Color.Blue }
                delay(300)
                val first = pixel()
                host.main { body = Color.Red }
                delay(250)
                if (fixed == OverlayBlurPolicy.FORCE_ON) assertNotEquals(first, pixel()) else assertEquals(first, pixel())
            }
        } finally { host.main { activity.finish() } }
    }
}
