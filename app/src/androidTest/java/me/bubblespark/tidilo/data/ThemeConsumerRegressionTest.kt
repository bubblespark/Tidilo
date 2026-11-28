package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberWideNavigationRailState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Real consumers, synthetic content and deterministic palettes; no production data is mutated. */
class ThemeConsumerRegressionTest {
    @get:Rule val composeRule = createComposeRule()

    private val bookmark = BookmarkItem("fixture", "Grid fixture", "", "Summary", Tag("Article", "Tech"), createdAt = 0L)
    private val collection = CollectionWithCount(CollectionEntity("fixture", "Collection fixture", "Description",
        "folder", null, 0, 0L, 0L), 3)
    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun scene(
        preset: AppThemeStyle = AppThemeStyle.Modern,
        fontScale: Float = 1f,
        width: Dp = 320.dp,
        locale: Locale = Locale.ENGLISH,
        content: @Composable () -> Unit,
        verify: suspend () -> Unit
    ) = runBlocking {
        composeRule.setContent { ThemeConsumerScene(preset, fontScale, width, locale, blurEnabled = false, content) }
        composeRule.waitForIdle()
        verify()
    }

    private fun tagged(tag: String): SemanticsNode = composeRule.awaitNode(hasTestTag(tag))

    /** Waits until the pixel at screen ([x], [y]) satisfies [matches]. */
    private suspend fun awaitPixel(label: String, x: Float, y: Float, matches: (Int) -> Boolean) {
        composeRule.sampleScreen(label, describe = { "pixel=${Integer.toHexString(it.getPixel(x.toInt(), y.toInt()))}" }) {
            Unit.takeIf { _ -> matches(it.getPixel(x.toInt(), y.toInt())) }
        }
    }

    private fun invoke(node: SemanticsNode, action: androidx.compose.ui.semantics.SemanticsPropertyKey<androidx.compose.ui.semantics.AccessibilityAction<() -> Boolean>>) {
        composeRule.runOnUiThread { assertTrue(node.config[action].action!!.invoke()) }
        composeRule.waitForIdle()
    }

    @Test fun clickableSurfaceExposesDefaultAndCallerRolesWithBothActions() = scene(content = {
        TidiloSurface(onClick = {}, role = SurfaceRole.TOOLBAR, modifier = Modifier.testTag("tap")) {
            Text("Tap only")
        }
        TidiloSurface(onClick = {}, role = SurfaceRole.TOOLBAR, modifier = Modifier.testTag("longPress"),
            onLongClick = {}, onLongClickLabel = "Edit") {
            Text("Tap and long press")
        }
        TidiloSurface(onClick = {}, role = SurfaceRole.INPUT,
            modifier = Modifier.testTag("customTap").semantics { role = Role.DropdownList }) {
            Text("Custom tap only")
        }
        TidiloSurface(onClick = {}, role = SurfaceRole.INPUT,
            modifier = Modifier.testTag("customLongPress").semantics { role = Role.DropdownList },
            onLongClick = {}, onLongClickLabel = "Edit") {
            Text("Custom tap and long press")
        }
    }, verify = {
        val tap = tagged("tap")
        val longPress = tagged("longPress")
        val customTap = tagged("customTap")
        val customLongPress = tagged("customLongPress")
        for (node in listOf(tap, longPress, customTap, customLongPress)) {
            assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
        }
        assertEquals(Role.Button, tap.config.getOrNull(SemanticsProperties.Role))
        assertEquals(Role.Button, longPress.config.getOrNull(SemanticsProperties.Role))
        assertEquals(Role.DropdownList, customTap.config.getOrNull(SemanticsProperties.Role))
        assertEquals(Role.DropdownList, customLongPress.config.getOrNull(SemanticsProperties.Role))
        assertNull(tap.config.getOrNull(SemanticsActions.OnLongClick))
        assertNull(customTap.config.getOrNull(SemanticsActions.OnLongClick))
        for (node in listOf(longPress, customLongPress)) {
            val action = node.config.getOrNull(SemanticsActions.OnLongClick)
            assertEquals("Edit", action?.label)
            assertNotNull(action?.action)
        }
    })

    @Test fun foregroundRemainsOpaqueWithTranslucentBackground() = scene(content = {
        ResolvedTidiloSurface(ResolvedSurfaceStyle(containerColor = Color.Red, opacity = 0.2f,
            treatment = SurfaceTreatment.DECORATED), Modifier.size(160.dp, 90.dp)) {
            Box(Modifier.padding(25.dp)) { Box(Modifier.size(18.dp).background(Color.Black).testTag("foreground")) }
        }
    }) {
        val bounds = tagged("foreground").screenBounds()
        awaitPixel("opaque foreground", bounds.center.x, bounds.center.y) { it == Color.Black.toArgb() }
    }

    @Test fun groupedSettingsConsumePresetColors() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.SETTINGS_GROUP to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            SettingsGroup(Modifier.testTag("settings")) { Text("Settings fixture", Modifier.padding(20.dp)) }
        }) {
            val bounds = tagged("settings").screenBounds()
            awaitPixel("cyan settings group", bounds.center.x, bounds.bottom - 8f) { it == Color.Cyan.toArgb() }
        }
    }

    @Test fun rssCardsConsumeTheSamePresetOverride() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.SETTINGS_GROUP to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            RssSettingsContent(StorageLoad.Ready(emptyList()), RssSettings(), false, null, true,
                onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                onDelete = {}, onFetch = {}, onDismissError = {})
        }) {
            val text = composeRule.awaitNode(hasText("Not set")).screenBounds()
            awaitPixel("cyan RSS card", text.center.x, text.top - 6f) { it == Color.Cyan.toArgb() }
        }
    }

    @Test fun modalConsumesPresetContainerAndRetainsDismissAction() {
        val dismissals = AtomicInteger()
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.DIALOG to SurfaceStyleOverride(containerColor = Color.Cyan)))
        scene(preset, content = {
            RssRegistrationDialog(2, false, onConfirm = {}, onDismiss = { dismissals.incrementAndGet() })
        }) {
            val position = composeRule.awaitNode(hasText("Resume this feed?")).positionOnScreen
            // Exact color comparisons must wait for the native window fade to finish.
            awaitPixel("cyan dialog container", position.x + 10f, position.y - 6f) { it == Color.Cyan.toArgb() }
            composeRule.clickNode(composeRule.awaitNode(hasText("Cancel")))
            assertEquals(1, dismissals.get())
        }
    }

    @Test fun thumbnailUsesOnlyResolvedTopCorners() {
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.BOOKMARK_CARD to SurfaceStyleOverride(elevation = 0.dp)))
        scene(preset, width = 200.dp, content = {
            BookmarkGridCardFixture(bookmark, Modifier.testTag("grid"))
        }) {
            val bounds = tagged("grid").screenBounds()
            val imageBottom = bounds.top + bounds.width * 9f / 16f
            composeRule.sampleScreen("rounded top corner over a square image bottom") { frame ->
                Unit.takeIf {
                    frame.getPixel((bounds.left + 3f).toInt(), (bounds.top + 3f).toInt()) == Color.White.toArgb() &&
                        frame.getPixel((bounds.left + 4f).toInt(), (imageBottom - 4f).toInt()) != Color.White.toArgb()
                }
            }
        }
    }

    @Test fun gridAndCollectionShadowsExtendOutsideTheirInputClip() {
        val clicks = AtomicInteger()
        val longs = AtomicInteger()
        var deck by mutableStateOf(false)
        val preset = AppThemeStyle.Modern.copy(surfaceOverrides = mapOf(
            SurfaceRole.BOOKMARK_CARD to SurfaceStyleOverride(elevation = 18.dp),
            SurfaceRole.DECK_FRONT to SurfaceStyleOverride(elevation = 18.dp)))
        scene(preset, width = 220.dp, content = {
            if (deck) CollectionCard(collection, { clicks.incrementAndGet() }, Modifier.testTag("card"),
                onLongClick = { longs.incrementAndGet() })
            else BookmarkGridCardFixture(bookmark, Modifier.testTag("card"), onClick = { clicks.incrementAndGet() },
                onLongClick = { longs.incrementAndGet() })
        }) {
            for ((index, title) in listOf("Grid fixture", "Collection fixture").withIndex()) {
                composeRule.runOnUiThread { deck = index == 1 }
                composeRule.awaitNode(hasText(title))
                val card = tagged("card")
                val clickable = composeRule.awaitNode(SemanticsMatcher("long-clickable over the card") {
                    it.config.getOrNull(SemanticsActions.OnLongClick) != null && it.boundsInRoot.overlaps(card.boundsInRoot)
                })
                invoke(clickable, SemanticsActions.OnClick)
                invoke(clickable, SemanticsActions.OnLongClick)
                assertEquals(index + 1, clicks.get())
                assertEquals(index + 1, longs.get())
                val bounds = clickable.screenBounds()
                composeRule.sampleScreen("$title shadow outside its input clip") { frame ->
                    Unit.takeIf { (-5..-1).any { frame.getPixel((bounds.left + it).toInt(), bounds.center.y.toInt()) != Color.White.toArgb() } }
                }
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
        }) {
            for (tag in listOf("header-action", "toggle-action")) {
                val bounds = tagged(tag).boundsInWindow
                assertTrue(bounds.width >= 48f * density - 1f)
                assertTrue(bounds.height >= 48f * density - 1f)
            }
            val toggle = composeRule.awaitNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            invoke(toggle, SemanticsActions.OnClick)
        }
    }

}

/** Screen-coordinate bounds, matching `uiAutomation.takeScreenshot()` pixels. */
internal fun SemanticsNode.screenBounds(): Rect =
    Rect(positionOnScreen, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))

/** Light, locale- and font-scale-controlled host shared by the theme consumer suites. */
@Composable
internal fun ThemeConsumerScene(
    preset: AppThemeStyle,
    fontScale: Float = 1f,
    width: Dp = 320.dp,
    locale: Locale = Locale.ENGLISH,
    blurEnabled: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
    val density = LocalDensity.current.density
    TidiloTheme(darkTheme = false, dynamicColor = false, theme = preset.theme, blurEnabled = blurEnabled) {
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
