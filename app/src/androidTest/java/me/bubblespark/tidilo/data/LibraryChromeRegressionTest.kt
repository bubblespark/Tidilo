package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu
import me.bubblespark.tidilo.ui.navigation.rememberLibraryRadialMenuState
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.DomainGroupItem
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryStore
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LibraryChromeRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var oldDiscoverer: ((String) -> DomainIconManager.HtmlDiscoveryResult)? = null
    private var oldDownloader: ((String) -> DomainIconManager.DownloadResult)? = null
    private var oldOgDownloader: (suspend (String) -> ByteArray?)? = null

    @Before
    fun stubMediaCaches() {
        oldDiscoverer = DomainIconManager.htmlDiscoverer
        oldDownloader = DomainIconManager.httpDownloader
        oldOgDownloader = OgImageManager.imageDownloader
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
    }

    @After
    fun restoreMediaCaches() {
        DomainIconManager.htmlDiscoverer = oldDiscoverer
        DomainIconManager.httpDownloader = oldDownloader
        OgImageManager.imageDownloader = oldOgDownloader
    }

    private class FakeChromeLibraryStore : LibraryStore {
        override val isInitialized = MutableStateFlow(true)
        override val initializationFailure = MutableStateFlow<Throwable?>(null)
        override fun retryInitialization() = Unit
        override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>> =
            flowOf(PagingData.from(emptyList(), DONE))
        override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>> =
            flowOf(PagingData.from(emptyList(), DONE))
        override fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>> =
            flowOf(PagingData.from(emptyList(), DONE))
        override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) = flowOf(0)
        override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) = flowOf(0)
        override fun observeDomainGroups(showPrivate: Boolean) = flowOf(emptyList<DomainGroupItem>())
        override fun observeRssFeedGroups(showPrivate: Boolean) = flowOf(emptyList<RssFeedGroup>())
        override fun observeRssFeedCount() = flowOf(0)
        override fun observeCollections(showPrivate: Boolean) = flowOf(emptyList<CollectionWithCount>())
        override fun observeMembershipIds(bookmarkId: String) = flowOf(emptyList<String>())
        override fun observeBookmark(id: String) = flowOf<BookmarkItem?>(null)
        override fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind) = flowOf(emptyList<TagCount>())
        override fun observeEntityLibrary() = flowOf(emptyMap<String, String>())
        override suspend fun toggleLike(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordBrowserOpen(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordDetailView(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordSummaryPeek(id: String, surface: EngagementSurface) = Unit
        override suspend fun saveNote(id: String, note: String) = Unit
        override suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) = Unit
        override suspend fun applyAiProposal(id: String, description: String?, tag: Tag?) = true
        override suspend fun moveToTrash(id: String) = true
        override fun loadFilterMode() = LibraryFilterMode.NEWEST_FIRST
        override suspend fun saveFilterMode(mode: LibraryFilterMode) = Unit
        override fun loadViewMode() = LibraryViewMode.LIST
        override fun saveViewMode(mode: LibraryViewMode) = Unit
        override fun loadIncludeRssInNewestFirst() = false
        override fun saveIncludeRssInNewestFirst(include: Boolean) = Unit
        override suspend fun loadSearchHistory() = emptyList<String>()
        override suspend fun saveSearchHistory(history: List<String>) = Unit
        override suspend fun clearSearchHistory() = Unit

        companion object {
            val DONE = LoadStates(
                LoadState.NotLoading(true),
                LoadState.NotLoading(true),
                LoadState.NotLoading(true)
            )
        }
    }

    private fun newViewModel() = LibraryViewModel(FakeChromeLibraryStore(), SavedStateHandle(), MutableStateFlow(true))

    private fun mountLibrary() {
        val model = newViewModel()
        composeRule.setContent {
            TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.DEFAULT, blurEnabled = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LibraryScreen(model)
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_dashboard_2_gear),
                        null,
                        Modifier.align(Alignment.BottomStart).padding(20.dp).size(24.dp).testTag("reference_view_mode"),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun screenBounds(node: SemanticsNode): Rect {
        var offset = Offset.Zero
        composeRule.runOnUiThread {
            offset = node.layoutInfo.coordinates.localToScreen(Offset.Zero)
        }
        return Rect(offset, Size(node.size.width.toFloat(), node.size.height.toFloat()))
    }

    @Test
    fun searchBackButtonUsesReadableForegroundOnDarkHeader() {
        val model = newViewModel()
        composeRule.setContent {
            TidiloTheme(darkTheme = true, dynamicColor = false, theme = AppTheme.DEFAULT, blurEnabled = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SearchScreen(model)
                }
            }
        }
        composeRule.waitForIdle()
        val backDesc = context.getString(R.string.common_back)
        val headerTitle = context.getString(R.string.search_header_title)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("back icon") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(backDesc) == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val icon = composeRule.onNodeWithContentDescription(backDesc, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = screenBounds(icon)
        composeRule.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            var foreground = 0
            var glyphPixel = AndroidColor.BLACK
            for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
                for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (AndroidColor.red(pixel) >= 50 && AndroidColor.green(pixel) >= 50) {
                        foreground++
                        if (glyphPixel == AndroidColor.BLACK) glyphPixel = pixel
                    }
                }
            }
            val title = composeRule.onNode(SemanticsMatcher("search header title") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == headerTitle } == true
            }, useUnmergedTree = true).fetchSemanticsNode()
            val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val trailingSeparation = title.boundsInWindow.left - button.boundsInWindow.right
            val density = context.resources.displayMetrics.density

            assertNotEquals("The back button icon must not be black (0xFF000000) on a dark header", AndroidColor.BLACK, glyphPixel)
            assertTrue("The back button glyph must contrast with a dark header on SearchScreen (foreground pixels: $foreground)", foreground > 30)
            assertTrue("Back navigation slot must retain 4dp trailing separation (actual: ${trailingSeparation / density}dp)", trailingSeparation >= 3.5f * density)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun menuGlyphUsesReadableForegroundOnDarkHeader() {
        mountLibrary()
        val navOpen = context.getString(R.string.navigation_open)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("menu icon") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(navOpen) == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val icon = composeRule.onNodeWithContentDescription(navOpen, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = screenBounds(icon)
        composeRule.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            var foreground = 0
            for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt()) {
                for (x in bounds.left.roundToInt() until bounds.right.roundToInt()) {
                    val pixel = bitmap.getPixel(x, y)
                    if (AndroidColor.red(pixel) > 180 && AndroidColor.green(pixel) > 180) foreground++
                }
            }
            assertTrue("The menu glyph must contrast with a dark Library header", foreground > 40)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun viewModeGlyphRetainsTheExistingDashboardAsset() {
        mountLibrary()
        val viewModeDesc = context.getString(R.string.library_view_mode_menu_desc)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("view mode icon") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(viewModeDesc) == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val actual = composeRule.onNodeWithContentDescription(viewModeDesc, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val reference = composeRule.onNodeWithTag("reference_view_mode", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        composeRule.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            val width = actual.width.roundToInt()
            val height = actual.height.roundToInt()
            var differences = 0
            var referenceInk = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val a = AndroidColor.red(bitmap.getPixel(actual.left.roundToInt() + x, actual.top.roundToInt() + y)) > 150
                    val b = AndroidColor.red(bitmap.getPixel(reference.left.roundToInt() + x, reference.top.roundToInt() + y)) > 150
                    if (a != b) differences++
                    if (b) referenceInk++
                }
            }
            assertTrue("View mode must render the existing dashboard/gear glyph: $differences differing pixels", differences < referenceInk / 5)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun viewModePopupAlignsToItsHeaderButton() {
        mountLibrary()
        val viewModeDesc = context.getString(R.string.library_view_mode_menu_desc)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("view mode icon") {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(viewModeDesc) == true
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val icon = composeRule.onNodeWithContentDescription(viewModeDesc, useUnmergedTree = true).fetchSemanticsNode()
        val button = generateSequence(icon) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        val anchor = screenBounds(button)

        composeRule.onNodeWithContentDescription(viewModeDesc, useUnmergedTree = true).performClick()
        val popupMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsPopup)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(popupMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
        val popup = composeRule.onAllNodes(popupMatcher, useUnmergedTree = true).fetchSemanticsNodes().first()
        val popupBounds = screenBounds(popup)
        assertEquals("Popup right edge must align with its trigger", anchor.right, popupBounds.right, 2f)
        assertTrue("Popup must open below the header trigger", popupBounds.top >= anchor.bottom - 2f)
    }

    @Test
    fun radialTriggerRippleDoesNotPaintOutsideItsRoundSurface() {
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, theme = AppTheme.DEFAULT, blurEnabled = false) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(64.dp)) {
                    LibraryRadialMenu(rememberLibraryRadialMenuState(), LibraryFilterMode.NEWEST_FIRST, {}, {})
                }
            }
        }
        composeRule.waitForIdle()
        val triggerNode = composeRule.onNodeWithTag("library_radial_trigger", useUnmergedTree = true)
        val bounds = triggerNode.fetchSemanticsNode().boundsInWindow
        val density = context.resources.displayMetrics.density
        val inset = 3f * density
        val x = (bounds.left + inset).roundToInt()
        val y = (bounds.top + inset).roundToInt()
        val insideX = bounds.center.x.roundToInt()
        val insideY = (bounds.top + 8f * density).roundToInt()

        val before = instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            try { bitmap.getPixel(x, y) to bitmap.getPixel(insideX, insideY) } finally { bitmap.recycle() }
        }

        triggerNode.performTouchInput { down(center) }
        val pressed = try {
            composeRule.waitForIdle()
            runBlocking {
                var sample = before
                for (attempt in 0 until 6) {
                    sample = instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        try { bitmap.getPixel(x, y) to bitmap.getPixel(insideX, insideY) } finally { bitmap.recycle() }
                    }
                    if (sample.second != before.second) break
                    if (attempt < 5) delay(100)
                }
                sample
            }
        } finally {
            runCatching { triggerNode.performTouchInput { up() } }
        }

        val difference = maxOf(
            abs(AndroidColor.red(before.first) - AndroidColor.red(pressed.first)),
            abs(AndroidColor.green(before.first) - AndroidColor.green(pressed.first)),
            abs(AndroidColor.blue(before.first) - AndroidColor.blue(pressed.first))
        )
        assertTrue("The pointer must actually press and paint the action", before.second != pressed.second)
        assertTrue("Pressed ripple leaked into the square corner outside the round Surface ($difference)", difference <= 5)
    }
}
