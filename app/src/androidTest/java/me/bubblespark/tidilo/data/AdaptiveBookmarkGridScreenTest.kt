package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkCollectionCrossRef
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.util.TimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Grid layout, scroll, and font reflow in fixed portrait and landscape viewports over an in-memory Library. */
class AdaptiveBookmarkGridScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var viewport: Viewport

    @Test fun firstCardFitsInEveryLandscapeBookmarkGrid() = verifyGrids(Viewport.LANDSCAPE, 1.15f)

    @Test fun largeTextShowsTheEntireInfoAreaInPortraitGrids() = verifyGrids(Viewport.PORTRAIT, 2f)

    @Test fun baselinePortraitShowsACompleteGridCard() =
        verifyGrids(Viewport.PORTRAIT, 1.15f, listOf(Destination.TIMELINE))

    @Test fun largeTextInLandscapeCanScrollToTheCompleteSearchCard() =
        verifyGrids(Viewport.LANDSCAPE, 2f, listOf(Destination.SEARCH), scrollToFit = true)

    @Test fun searchFontReflowKeepsTheBookmarkWhenTheInputIsOffscreen() = withFixture { fixture ->
        viewport = Viewport.PORTRAIT
        val scale = mutableFloatStateOf(1.15f)
        val model = fixture.library.viewModel()
        composeRule.runOnUiThread { model.setViewMode(LibraryViewMode.GRID) }
        composeRule.setContent {
            ViewportBox(viewport.widthDp, viewport.heightDp, scale.floatValue) { Theme { SearchScreen(viewModel = model) } }
        }
        awaitNode(hasText(context.getString(R.string.search_header_title)))
        composeRule.runOnIdle { model.onSearchQueryChanged(SEARCH_TOKEN) }
        awaitNode(hasText(context.getString(R.string.search_header_subtitle_results, fixture.bookmarks.size)))
        awaitTitle(fixture, 0)
        val bookmark = 8
        scrollToIndex(bookmark + 1)
        awaitTopRow("Search anchor before font reflow") { bookmark in it }
        val anchor = cardFor(titleNode(fixture.bookmarks[bookmark]))
        scrollBy(anchor.size.height * 0.7f, within = anchor)
        assertFalse("Search input should be outside the viewport before reflow", hasVisibleSearchInput())
        composeRule.runOnIdle { scale.floatValue = 2f }
        awaitTopRow("Search anchor after font reflow") { it == listOf(bookmark) }
    }

    @Test fun listAndGridKeepIndependentScrollPositions() = withFixture { fixture ->
        viewport = Viewport.PORTRAIT
        val model = fixture.library.viewModel()
        composeRule.runOnUiThread {
            model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
            model.setViewMode(LibraryViewMode.GRID)
        }
        composeRule.setContent {
            ViewportBox(viewport.widthDp, viewport.heightDp, 1.15f) { Theme { LibraryScreen(viewModel = model) } }
        }
        awaitTitle(fixture, 0)
        scrollToIndex(10)
        awaitTitle(fixture, 10)
        val gridPosition = cardTop(fixture, 10)

        setMode(model, LibraryViewMode.LIST)
        awaitTitle(fixture, 0)
        scrollToIndex(20)
        awaitTitle(fixture, 20)
        val listPosition = cardTop(fixture, 20)

        setMode(model, LibraryViewMode.GRID)
        awaitTitle(fixture, 10)
        assertEquals(gridPosition, cardTop(fixture, 10), 2f)
        setMode(model, LibraryViewMode.LIST)
        awaitTitle(fixture, 20)
        assertEquals(listPosition, cardTop(fixture, 20), 2f)
    }

    private fun verifyGrids(
        viewport: Viewport,
        fontScale: Float,
        destinations: List<Destination> = Destination.entries,
        scrollToFit: Boolean = false
    ) = withFixture { fixture ->
        this.viewport = viewport
        var search by mutableStateOf(false)
        val model = fixture.library.viewModel()
        composeRule.runOnUiThread {
            model.setViewMode(LibraryViewMode.GRID)
            model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
        }
        composeRule.setContent {
            ViewportBox(viewport.widthDp, viewport.heightDp, fontScale) {
                Theme {
                    if (search) SearchScreen(viewModel = model, onNavigateBack = {})
                    else LibraryScreen(viewModel = model)
                }
            }
        }
        for (destination in destinations) {
            val header = when (destination) {
                Destination.TIMELINE -> context.getString(R.string.library_header_title)
                Destination.DOMAIN -> fixture.domain
                Destination.RSS -> fixture.feed.title
                Destination.COLLECTION -> fixture.collection.title
                Destination.SEARCH -> context.getString(R.string.search_header_title)
            }
            composeRule.runOnIdle {
                when (destination) {
                    Destination.TIMELINE -> model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                    Destination.DOMAIN -> {
                        model.setFilterMode(LibraryFilterMode.DOMAIN)
                        model.selectDomainFilter(fixture.domain)
                    }
                    Destination.RSS -> {
                        model.setFilterMode(LibraryFilterMode.RSS)
                        model.selectRssFeedFilter(fixture.feed)
                    }
                    Destination.COLLECTION -> {
                        model.setFilterMode(LibraryFilterMode.COLLECTION)
                        model.selectCollectionFilter(fixture.collection.id)
                    }
                    Destination.SEARCH -> {
                        model.onSearchQueryChanged(SEARCH_TOKEN)
                        search = true
                    }
                }
            }
            val first = fixture.firstBookmark(destination)
            try {
                awaitNode(hasText(header))
                if (destination == Destination.SEARCH) {
                    // Results arrive after the query debounce; wait for them rather than the pre-search list.
                    awaitNode(hasText(context.getString(R.string.search_header_subtitle_results, fixture.bookmarks.size)))
                }
                awaitNode(hasText(first.title)) { it.boundsInRoot.height > 0 }
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("$destination did not show '$header' and its first bookmark", timeout)
            }
            val initialSearchCardSize = if (destination == Destination.SEARCH) cardFor(titleNode(first)).size else null
            composeRule.waitForIdle()
            if (initialSearchCardSize != null) {
                val settled = cardFor(titleNode(first)).size
                assertTrue("Search card must not reflow: $initialSearchCardSize to $settled",
                    abs(initialSearchCardSize.width - settled.width) <= 1 &&
                        abs(initialSearchCardSize.height - settled.height) <= 1)
            }
            if (scrollToFit) {
                val card = cardFor(titleNode(first))
                val overflow = card.boundsInRoot.top + card.size.height - viewportBounds().bottom
                if (overflow > 0f) scrollBy(overflow + 8 * viewportDensity(), within = card)
            }
            assertFirstCardVisible(destination, first, scrollToFit)
        }
    }

    private fun assertFirstCardVisible(destination: Destination, bookmark: BookmarkEntity, afterScroll: Boolean) {
        val viewport = viewportBounds()
        val title = titleNode(bookmark)
        val card = cardFor(title)
        val bounds = card.boundsInRoot
        val relativeTime = TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
        val date = nodes(hasText(relativeTime)).first {
            generateSequence(it) { node -> node.parent }.any { node -> node.id == card.id }
        }
        val input = nodes(hasSetTextAction()).firstOrNull()
        assertEquals("$destination card is clipped: $bounds in viewport $viewport", card.size.height.toFloat(), bounds.height, 2f)
        assertTrue("$destination card top $bounds is above the viewport $viewport", bounds.top >= viewport.top)
        assertTrue("$destination card $bounds extends below the viewport $viewport, " +
            "input ${input?.boundsInRoot}, title ${title.boundsInRoot}, date ${date.boundsInRoot}",
            bounds.bottom <= viewport.bottom + 1f)
        assertRenderedText(title, "$destination title, all ${nodes(hasText(bookmark.title)).map { it.boundsInRoot }}")
        assertRenderedText(date, "$destination date")
        assertTrue("$destination date is visible below the title",
            date.boundsInRoot.top >= title.boundsInRoot.bottom && date.boundsInRoot.bottom <= bounds.bottom)
        if (destination == Destination.SEARCH && !afterScroll) {
            checkNotNull(input)
            assertTrue("The Search field is visible above the first card",
                input.boundsInRoot.height > 0 && input.boundsInRoot.bottom <= bounds.top)
        } else if (destination != Destination.SEARCH) {
            val trigger = checkNotNull(nodes(hasTestTag("library_radial_trigger")).firstOrNull()) {
                "Library shortcut missing for $destination"
            }
            assertFalse("First card overlaps the Library shortcut", bounds.overlaps(trigger.boundsInRoot))
        }
    }

    private fun setMode(model: LibraryViewModel, mode: LibraryViewMode) {
        composeRule.runOnIdle {
            model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
            model.setViewMode(mode)
        }
        composeRule.waitForIdle()
    }

    private fun awaitTitle(fixture: Fixture, index: Int) {
        try {
            awaitNode(hasText(fixture.bookmarks[index].title)) { it.boundsInRoot.height > 0 }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Bookmark $index did not become visible", timeout)
        }
    }

    private fun awaitNode(matcher: SemanticsMatcher, accept: (SemanticsNode) -> Boolean = { true }) {
        composeRule.waitUntil(8_000) { nodes(matcher).any(accept) }
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    /** The drawn title of [bookmark]; an outgoing destination can still hold a zero-size copy. */
    private fun titleNode(bookmark: BookmarkEntity): SemanticsNode =
        nodes(hasText(bookmark.title)).let { titles -> titles.firstOrNull { it.boundsInRoot.height > 0 } ?: titles.first() }

    private fun cardFor(title: SemanticsNode): SemanticsNode =
        generateSequence(title) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun cardTop(fixture: Fixture, index: Int): Float =
        cardFor(titleNode(fixture.bookmarks[index])).boundsInRoot.top

    private fun viewportBounds(): Rect = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    /** Pixels per dp inside the viewport, which [ViewportBox] may scale below the device density. */
    private fun viewportDensity(): Float = viewportBounds().width / viewport.widthDp

    private fun hasVisibleSearchInput(): Boolean = nodes(hasSetTextAction()).any { it.boundsInRoot.height > 0 }

    private fun scrollToIndex(index: Int) {
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    /** Scrolls the vertical container that holds [within] by [pixels]. */
    private fun scrollBy(pixels: Float, within: SemanticsNode) {
        val container = generateSequence(within) { it.parent }.first {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                it.config.getOrNull(SemanticsActions.ScrollBy) != null
        }
        composeRule.runOnUiThread { assertTrue(container.config[SemanticsActions.ScrollBy].action?.invoke(0f, pixels) == true) }
        composeRule.waitForIdle()
    }

    /** Waits until the leading visible card row below the header satisfies [predicate]; one semantics pass per check. */
    private fun awaitTopRow(stage: String, predicate: (List<Int>) -> Boolean): List<Int> {
        var row = emptyList<Int>()
        try {
            composeRule.waitUntil(8_000) {
                row = topRow()
                row.isNotEmpty() && predicate(row)
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("$stage did not settle. Last top row was $row", timeout)
        }
        return row
    }

    private fun topRow(): List<Int> {
        val clearance = HeaderDefaults.ContentHeight + HeaderDefaults.FadeHeight + HeaderDefaults.ClearanceGap
        val bodyTop = viewportBounds().top + clearance.value * viewportDensity()
        val visibleCards = nodes(GRID_TITLE).mapNotNull { title ->
            val index = title.config[SemanticsProperties.Text].firstNotNullOfOrNull {
                it.text.removePrefix(TITLE_PREFIX).toIntOrNull()
            } ?: return@mapNotNull null
            val bounds = cardFor(title).boundsInRoot
            if (bounds.height > 0 && bounds.top >= bodyTop - 2f) index to bounds.top else null
        }
        val rowTop = visibleCards.minOfOrNull { it.second } ?: return emptyList()
        return visibleCards.filter { abs(it.second - rowTop) < 2 }.map { it.first }.sorted()
    }

    private fun assertRenderedText(node: SemanticsNode, label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        val text = layouts.single()
        val bottom = text.getLineBottom(text.lineCount - 1)
        assertTrue("$label: '${text.layoutInput.text}' ends at $bottom, below its ${node.boundsInRoot} bounds (layout ${text.size})",
            bottom <= node.boundsInRoot.height + 1f)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit): Unit = runBlocking {
        val fixture = withContext(Dispatchers.IO) { Fixture(context).also { it.seed() } }
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    @Composable
    private fun Theme(content: @Composable () -> Unit) =
        TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false, content = content)

    /** The device's own screen in dp, so portrait and landscape match what a rotation would give. */
    private data class Viewport(val widthDp: Int, val heightDp: Int) {
        companion object {
            private val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
            private val shortSide = (minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
            private val longSide = (maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
            val PORTRAIT = Viewport(shortSide, longSide)
            val LANDSCAPE = Viewport(longSide, shortSide)
        }
    }

    private enum class Destination { TIMELINE, DOMAIN, RSS, COLLECTION, SEARCH }

    private class Fixture(context: Context) : AutoCloseable {
        val library = InMemoryLibrary(context)
        private val caches = OfflineVisualCaches()
        private val now = System.currentTimeMillis()
        val domain = "grid.invalid"
        val feed = RssFeedEntity("grid-feed", "https://feed.invalid/rss", "Grid RSS", enabled = false)
        val collection = CollectionEntity("grid-collection", "Grid Collection", "", null, null, 0, now, now)
        val bookmarks = List(30) { index ->
            BookmarkEntity("grid-$index", "$TITLE_PREFIX$index", "https://$domain/$index", "Summary $SEARCH_TOKEN",
                createdAt = now - index, updatedAt = now)
        }
        private val rssBookmark = BookmarkEntity("grid-rss", "Grid RSS card", "https://feed.invalid/article", "Summary",
            createdAt = now - 1_000, updatedAt = now, rssFeedId = feed.id)

        suspend fun seed() {
            library.awaitReady()
            val db = library.db
            db.rssDao().putFeed(feed)
            db.collectionDao().insert(collection)
            db.bookmarkDao().insertAll(bookmarks + rssBookmark)
            db.withTransaction {
                bookmarks.forEach { db.collectionDao().insertMembership(BookmarkCollectionCrossRef(it.id, collection.id)) }
            }
        }

        fun firstBookmark(destination: Destination): BookmarkEntity =
            if (destination == Destination.RSS) rssBookmark else bookmarks.first()

        override fun close() {
            caches.close()
            library.close()
        }
    }

    private companion object {
        const val TITLE_PREFIX = "Grid card "
        const val SEARCH_TOKEN = "gridfixture"
        val GRID_TITLE = SemanticsMatcher("grid card title") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(TITLE_PREFIX) } == true
        }
    }
}
