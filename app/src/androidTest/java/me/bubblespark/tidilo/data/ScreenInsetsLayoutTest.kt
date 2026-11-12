package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises inset ownership against actual measured content rather than modifier presence. */
class ScreenInsetsLayoutTest {
    @Test fun contentInsetsRespectSafeSidesAndConsumedBottom(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (consumed in listOf(0, 30)) {
                var body = Rect.Zero
                var viewport = Rect.Zero
                var density = 1f
                host.main { activity.setContent { MaterialTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(bottom = consumed))) {
                        TidiloScreenScaffold(header = { Text("Header") }, bottomContentPadding = 8.dp,
                            modifier = Modifier.onGloballyPositioned { viewport = it.boundsInWindow() },
                            contentWindowInsets = WindowInsets(left = 12, right = 18, bottom = 80)) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding)
                                .onGloballyPositioned { body = it.boundsInWindow() })
                        }
                    }
                } } }
                delay(500)
                host.main {
                    assertEquals(viewport.left + 12 + 20 * density, body.left, 1f)
                    assertEquals(viewport.right - 18 - 20 * density, body.right, 1f)
                    assertEquals(viewport.bottom - (80 - consumed) - 8 * density, body.bottom, 1f)
                }
            }
        } finally { host.main { activity.finish() } }
    }
    @Test fun consumedTopInsetDoesNotAddDuplicateClearance(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            for (consumed in listOf(0, 100)) {
                var top = 0f
                var chrome = 0f
                host.main { activity.setContent { MaterialTheme {
                    val density = LocalDensity.current
                    chrome = with(density) { (HeaderDefaults.rowHeight() + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap).toPx() }
                    Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(top = consumed))) {
                        TidiloScreenScaffold(header = {}, contentWindowInsets = WindowInsets(top = 100)) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding).onGloballyPositioned { top = it.boundsInWindow().top })
                        }
                    }
                } } }
                delay(400)
                host.main { assertEquals(chrome + 100 - consumed, top, 1f) }
            }
        } finally { host.main { activity.finish() } }
    }
    @Test fun lazyContentKeepsFullViewportAndClearsCaptionAndNavigationAreas(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var viewport = Rect.Zero
        var root = Rect.Zero
        var first = Rect.Zero
        var last = Rect.Zero
        var chrome = 0f
        var density = 1f
        try {
            host.main { activity.setContent { MaterialTheme {
                val d = LocalDensity.current
                density = d.density
                chrome = with(d) { (HeaderDefaults.rowHeight() + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap).toPx() }
                TidiloScreenScaffold(header = { Text("Header") }, bottomContentPadding = 8.dp,
                    modifier = Modifier.onGloballyPositioned { root = it.boundsInWindow() },
                    contentWindowInsets = WindowInsets(top = 600, bottom = 80)) { padding ->
                    LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInWindow() },
                        contentPadding = padding) {
                        items(30, key = { it }) { index ->
                            Text("Inset row $index", Modifier.fillMaxWidth().height(80.dp).onGloballyPositioned {
                                if (index == 0) first = it.boundsInWindow()
                                if (index == 29) last = it.boundsInWindow()
                            })
                        }
                    }
                }
            } } }
            host.await(activity) { host.text(it, "Inset row 0") }
            delay(200)
            host.main {
                assertEquals(root.height, viewport.height, 1f)
                assertEquals(root.top + 600 + chrome, first.top, 1f)
            }
            host.scroll(activity, 100000f)
            host.await(activity) { host.text(it, "Inset row 29") }
            delay(200)
            host.main { assertEquals(root.bottom - 80 - 8 * density, last.bottom, 1f) }
        } finally { host.main { activity.finish() } }
    }
    @Test fun storageScrollViewportExtendsBehindNavigationBar(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.StorageContent(
                    me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Loading,
                    me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Loading, {})
            } } }
            val scroll = host.await(activity) {
                it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null && it.boundsInWindow.height > 100f
            }
            delay(200)
            host.main { val current = host.nodes(activity).first { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null && it.boundsInWindow.height > 100f }; assertEquals(activity.window.decorView.height.toFloat(), current.boundsInWindow.height, 1f) }
        } finally { host.main { activity.finish() } }
    }
    @Test fun rssFloatingActionAvoidsNavigationBars(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme {
                me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent(
                    me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Ready(emptyList()),
                    me.bubblespark.tidilo.data.rss.RssSettings(), false, null, true,
                    {}, {}, {}, { _, _ -> }, {}, {}, {})
            } } }
            host.await(activity) { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.rss_add_feed)) }
            delay(400)
            host.main {
                var action = host.nodes(activity).first { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.rss_add_feed)) }
                while (action.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) == null) action = requireNotNull(action.parent)
                val nav = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)).getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                val bounds = action.fullWindowBounds()
                assertEquals(true, bounds.width > 0f && bounds.height > 0f &&
                    bounds.left >= nav.left - 1f && bounds.right <= activity.window.decorView.width - nav.right + 1f &&
                    bounds.bottom <= activity.window.decorView.height - nav.bottom + 1f)
            }
        } finally { host.main { activity.setContent {}; activity.finish() } }
    }
}
