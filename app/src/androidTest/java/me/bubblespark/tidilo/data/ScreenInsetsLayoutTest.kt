package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises inset ownership against actual measured content rather than modifier presence. */
class ScreenInsetsLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    private fun mount(content: @Composable () -> Unit) {
        // MainActivity draws edge to edge; viewport and navigation-bar checks depend on it.
        composeRule.runOnUiThread { activity.enableEdgeToEdge() }
        composeRule.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { content() } }
    }

    @Test fun contentInsetsRespectSafeSidesAndConsumedBottom() {
        var consumed by mutableIntStateOf(0)
        var body = Rect.Zero
        var viewport = Rect.Zero
        var density = 1f
        mount {
            density = LocalDensity.current.density
            Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(bottom = consumed))) {
                TidiloScreenScaffold(header = { Text("Header") }, bottomContentPadding = 8.dp,
                    modifier = Modifier.onGloballyPositioned { viewport = it.boundsInWindow() },
                    contentWindowInsets = WindowInsets(left = 12, right = 18, bottom = 80)) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)
                        .onGloballyPositioned { body = it.boundsInWindow() })
                }
            }
        }
        for (next in listOf(0, 30)) {
            composeRule.runOnUiThread { consumed = next }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                assertEquals(viewport.left + 12 + 20 * density, body.left, 1f)
                assertEquals(viewport.right - 18 - 20 * density, body.right, 1f)
                assertEquals(viewport.bottom - (80 - next) - 8 * density, body.bottom, 1f)
            }
        }
    }

    @Test fun consumedTopInsetDoesNotAddDuplicateClearance() {
        val fixtureTop = 100
        var consumed by mutableIntStateOf(0)
        var scaffoldTop = Float.NaN
        var contentTop = Float.NaN
        var chrome = 0f
        mount {
            val density = LocalDensity.current
            chrome = with(density) { (HeaderDefaults.rowHeight() + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap).toPx() }
            Box(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(top = consumed))) {
                TidiloScreenScaffold(
                    header = {},
                    modifier = Modifier.onGloballyPositioned { scaffoldTop = it.positionInRoot().y },
                    contentWindowInsets = WindowInsets(top = fixtureTop)
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)
                        .onGloballyPositioned { contentTop = it.positionInRoot().y })
                }
            }
        }
        for (next in listOf(0, 40, fixtureTop)) {
            composeRule.runOnUiThread { consumed = next }
            composeRule.waitForIdle()
            composeRule.runOnUiThread {
                assertEquals(
                    "Top clearance with $next px consumed by the parent",
                    chrome + fixtureTop - next,
                    contentTop - scaffoldTop,
                    1f
                )
            }
        }
    }

    @Test fun lazyContentKeepsFullViewportAndClearsCaptionAndNavigationAreas() {
        var viewport = Rect.Zero
        var root = Rect.Zero
        var first = Rect.Zero
        var last = Rect.Zero
        var chrome = 0f
        var density = 1f
        mount {
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
        }
        composeRule.awaitNode(hasText("Inset row 0"))
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            assertEquals(root.height, viewport.height, 1f)
            assertEquals(root.top + 600 + chrome, first.top, 1f)
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.awaitNode(hasText("Inset row 29"))
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertEquals(root.bottom - 80 - 8 * density, last.bottom, 1f) }
    }

    @Test fun storageScrollViewportExtendsBehindNavigationBar() {
        mount {
            me.bubblespark.tidilo.ui.screens.settings.StorageContent(
                me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Loading,
                me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Loading, {})
        }
        val tallScroll = SemanticsMatcher("tall scroll viewport") {
            it.config.getOrNull(SemanticsActions.ScrollBy) != null && it.boundsInWindow.height > 100f
        }
        composeRule.awaitNode(tallScroll)
        composeRule.waitForIdle()
        val current = composeRule.awaitNode(tallScroll)
        composeRule.runOnUiThread { assertEquals(activity.window.decorView.height.toFloat(), current.boundsInWindow.height, 1f) }
    }

    @Test fun rssFloatingActionAvoidsNavigationBars() {
        mount {
            me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent(
                me.bubblespark.tidilo.ui.screens.settings.StorageLoad.Ready(listOf(
                    me.bubblespark.tidilo.data.db.RssFeedEntity("insets", "https://example.invalid/feed", "Example")
                )),
                me.bubblespark.tidilo.data.rss.RssSettings(), false, null, true,
                {}, {}, {}, { _, _ -> }, {}, {}, {})
        }
        val label = hasContentDescription(activity.getString(R.string.rss_add_feed))
        composeRule.awaitNode(label)
        composeRule.waitForIdle()
        var action = composeRule.awaitNode(label)
        while (action.config.getOrNull(SemanticsActions.OnClick) == null) action = requireNotNull(action.parent)
        composeRule.runOnUiThread {
            val decor = activity.window.decorView
            val nav = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.navigationBars())
            val bounds = action.fullWindowBounds()
            assertEquals(true, bounds.width > 0f && bounds.height > 0f &&
                bounds.left >= nav.left - 1f && bounds.right <= decor.width - nav.right + 1f &&
                bounds.bottom <= decor.height - nav.bottom + 1f)
        }
    }
}
