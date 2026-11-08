package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.screens.settings.RssRegistrationDialog
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.SettingsGroup
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.theme.*
import me.bubblespark.tidilo.ui.navigation.LibrarySearchAction
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberWideNavigationRailState
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Real consumers, synthetic content and deterministic palettes; no production data is mutated. */
class ThemeConsumerRegressionTest {
    private val bookmark = BookmarkItem("fixture", "Grid fixture", "", "Summary", Tag("Article", "Tech"), createdAt = 0L)
    private val collection = CollectionWithCount(CollectionEntity("fixture", "Collection fixture", "Description",
        "folder", null, 0, 0L, 0L), 3)

    private fun scene(
        preset: AppThemeStyle = AppThemeStyle.Modern,
        fontScale: Float = 1f,
        width: Dp = 320.dp,
        locale: Locale = Locale.ENGLISH,
        content: @Composable () -> Unit,
        verify: suspend (HeaderTestHost, ComponentActivity) -> Unit
    ) = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main {
                activity.setContent {
                    val context = LocalContext.current
                    val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
                    val density = LocalDensity.current.density
                    TidiloTheme(darkTheme = false, dynamicColor = false, theme = preset.theme) {
                        val surfaces = resolveThemeSurfaces(preset.theme, false, MaterialTheme.colorScheme, preset)
                        CompositionLocalProvider(
                            LocalThemeSurfaces provides surfaces,
                            LocalConfiguration provides configuration,
                            LocalContext provides context.createConfigurationContext(configuration),
                            LocalDensity provides Density(density, fontScale)
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.White).padding(32.dp)) {
                                Column(Modifier.width(width)) { content() }
                            }
                        }
                    }
                }
            }
            host.instrumentation.waitForIdleSync()
            delay(350)
            verify(host, activity)
        } finally { host.main { activity.finish() } }
    }

    private suspend fun tagged(host: HeaderTestHost, activity: ComponentActivity, tag: String): SemanticsNode =
        host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun pixel(host: HeaderTestHost, x: Float, y: Float): Int {
        host.instrumentation.waitForIdleSync()
        val image = host.instrumentation.uiAutomation.takeScreenshot()
        return try { image.getPixel(x.toInt(), y.toInt()) } finally { image.recycle() }
    }

    @Test fun foregroundRemainsOpaqueWithTranslucentBackground() = scene(content = {
        ResolvedTidiloSurface(ResolvedSurfaceStyle(containerColor = Color.Red, opacity = 0.2f,
            treatment = SurfaceTreatment.DECORATED), Modifier.size(160.dp, 90.dp)) {
            Box(Modifier.padding(25.dp)) { Box(Modifier.size(18.dp).background(Color.Black).testTag("foreground")) }
        }
    }) { host, activity ->
        val bounds = tagged(host, activity, "foreground").boundsInWindow
        assertEquals(Color.Black.toArgb(), pixel(host, bounds.center.x, bounds.center.y))
    }

    @Test fun groupedSettingsConsumePresetColors() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.SETTINGS_GROUP to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            SettingsGroup(Modifier.testTag("settings")) { Text("Settings fixture", Modifier.padding(20.dp)) }
        }) { host, activity ->
            val bounds = tagged(host, activity, "settings").boundsInWindow
            assertEquals(Color.Cyan.toArgb(), pixel(host, bounds.center.x, bounds.bottom - 8f))
        }
    }

    @Test fun rssCardsConsumeTheSamePresetOverride() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.SETTINGS_GROUP to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            RssSettingsContent(StorageLoad.Ready(emptyList()), RssSettings(), false, null, true,
                onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                onDelete = {}, onFetch = {}, onDismissError = {})
        }) { host, activity ->
            val text = host.await(activity) { host.text(it, "Not set") }.boundsInWindow
            assertEquals(Color.Cyan.toArgb(), pixel(host, text.center.x, text.top - 6f))
        }
    }

    @Test fun modalConsumesPresetContainerAndRetainsDismissAction() {
        val dismissals = AtomicInteger()
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.DIALOG to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            RssRegistrationDialog(2, false, onConfirm = {}, onDismiss = { dismissals.incrementAndGet() })
        }) { host, activity ->
            val title = host.await(activity) { host.text(it, "Resume this feed?") }
            var position = androidx.compose.ui.geometry.Offset.Zero
            host.main {
                position = title.layoutInfo.coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            }
            assertEquals(Color.Cyan.toArgb(), pixel(host, position.x + 10f, position.y - 6f))
            val cancel = host.await(activity) { host.text(it, "Cancel") }
            host.main {
                val action = generateSequence(cancel) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke())
            }
            assertEquals(1, dismissals.get())
        }
    }

    @Test fun thumbnailUsesOnlyResolvedTopCorners() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.BOOKMARK_CARD to SurfaceStyleOverride(elevation = 0.dp)))
        scene(preset, width = 200.dp, content = {
            BookmarkGridCard(bookmark, Modifier.testTag("grid"))
        }) { host, activity ->
            val bounds = tagged(host, activity, "grid").boundsInWindow
            assertEquals(Color.White.toArgb(), pixel(host, bounds.left + 3f, bounds.top + 3f))
            val imageBottom = bounds.top + bounds.width * 9f / 16f
            assertNotEquals(Color.White.toArgb(), pixel(host, bounds.left + 4f, imageBottom - 4f))
        }
    }

    @Test fun gridAndCollectionShadowsExtendOutsideTheirInputClip() {
        for (deck in listOf(false, true)) {
            val clicks = AtomicInteger()
            val longs = AtomicInteger()
            val role = if (deck) SurfaceRole.DECK_FRONT else SurfaceRole.BOOKMARK_CARD
            val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(role to SurfaceStyleOverride(elevation = 18.dp)))
            scene(preset, width = 220.dp, content = {
                if (deck) CollectionCard(collection, { clicks.incrementAndGet() }, Modifier.testTag("card"),
                    onLongClick = { longs.incrementAndGet() })
                else BookmarkGridCard(bookmark, Modifier.testTag("card"), onClick = { clicks.incrementAndGet() },
                    onLongClick = { longs.incrementAndGet() })
            }) { host, activity ->
                val node = tagged(host, activity, "card")
                val clickable = host.await(activity) {
                    it.config.getOrNull(SemanticsActions.OnLongClick) != null && it.boundsInWindow.overlaps(node.boundsInWindow)
                }
                host.main {
                    assertTrue(clickable.config[SemanticsActions.OnClick].action!!.invoke())
                    assertTrue(clickable.config[SemanticsActions.OnLongClick].action!!.invoke())
                }
                assertEquals(1, clicks.get())
                assertEquals(1, longs.get())
                val bounds = clickable.boundsInWindow
                assertTrue((-5..-1).any { pixel(host, bounds.left + it, bounds.center.y) != Color.White.toArgb() })
            }
        }
    }

    @Test fun narrowLargeJapaneseHeaderAndToggleKeepTheirTargets() {
        scene(fontScale = 2f, locale = Locale.JAPANESE, content = {
            TidiloFrostedHeader(title = {
                Text("長い日本語の見出しと保存したコレクション", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }, navigationIcon = {
                TidiloIconButton({}, SurfaceRole.TOOLBAR_ACTION, Modifier.testTag("header-action")) {
                    Icon(Icons.Default.Favorite, "操作")
                }
            })
            BookmarkTagSection(Tag("Article", "Tech", listOf("長い日本語のキーワード")), "記事", "技術")
            var checked by remember { mutableStateOf(true) }
            TidiloIconToggleButton(checked, { checked = it }, SurfaceRole.LIKE_ACTION,
                Modifier.testTag("toggle-action")) { Icon(Icons.Default.Favorite, "お気に入り") }
        }) { host, activity ->
            for (tag in listOf("header-action", "toggle-action")) {
                val bounds = tagged(host, activity, tag).boundsInWindow
                val density = host.context.resources.displayMetrics.density
                assertTrue(bounds.width >= 48f * density - 1f)
                assertTrue(bounds.height >= 48f * density - 1f)
            }
            val toggle = host.await(activity) { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            host.main { assertTrue(toggle.config[SemanticsActions.OnClick].action!!.invoke()) }
        }
    }

    @Test fun searchActionKeepsCustomEdgeFillAndOpacity() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.PRIMARY_ACTION to SurfaceStyleOverride(containerColor = Color.Black,
                gradient = SurfaceGradient(Color.Blue, Color.Blue), bevel = SurfaceGradient(Color.Green, Color.Green),
                borderWidth = 4.dp, opacity = 0.4f, shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp))))
        scene(preset, content = { NavigationFixture(false) }) { host, activity ->
            val tab = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_search_action" }
            val bounds = tab.boundsInWindow
            val density = host.context.resources.displayMetrics.density
            withTimeout(5000) {
                while (true) {
                    val edge = pixel(host, bounds.left + density, bounds.center.y)
                    if (android.graphics.Color.green(edge) > 200 && android.graphics.Color.red(edge) < 50) break
                    delay(40)
                }
            }
            val fill = pixel(host, bounds.center.x, bounds.top + 9f * density)
            assertTrue(android.graphics.Color.blue(fill) > android.graphics.Color.red(fill) + 20)
            assertTrue(android.graphics.Color.red(fill) > 80)
        }
    }

    @Test fun expandedFilterConsumerKeepsSelectedMeaningAfterNeutralOverride() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.FILTER_ACTION to SurfaceStyleOverride(containerColor = Color.Gray, contentColor = Color.Black)))
        var selectedColor = Color.Unspecified
        scene(preset, content = {
            val color = MaterialTheme.colorScheme.secondaryContainer
            SideEffect { selectedColor = color }
            NavigationFixture(true)
        }) { host, activity ->
            val selected = host.await(activity) {
                it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsActions.OnClick) != null
            }
            val density = host.context.resources.displayMetrics.density
            withTimeout(5000) {
                while (true) {
                    val bounds = selected.boundsInWindow
                    if (pixel(host, bounds.center.x, bounds.top + 8f * density) == selectedColor.toArgb()) break
                    delay(40)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NavigationFixture(expanded: Boolean) {
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            if (expanded) {
                val state = rememberWideNavigationRailState()
                LaunchedEffect(Unit) { state.expand() }
                LibraryNavigationRail(state, LibraryFilterMode.DOMAIN, {}, {}, {}, {}, {})
            } else LibrarySearchAction({}, Modifier.align(Alignment.BottomEnd))
        }
    }
}
