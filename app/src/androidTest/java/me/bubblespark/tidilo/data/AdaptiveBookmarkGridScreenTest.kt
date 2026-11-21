package me.bubblespark.tidilo.data

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
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
import org.junit.Assert.*
import org.junit.Test

class AdaptiveBookmarkGridScreenTest {
    @Test fun firstCardFitsInEveryLandscapeBookmarkGrid(): Unit = runBlocking {
        verifyGrids(Configuration.ORIENTATION_LANDSCAPE, fontScale = 1.15f)
    }

    @Test fun largeTextShowsTheEntireInfoAreaInPortraitGrids(): Unit = runBlocking {
        verifyGrids(Configuration.ORIENTATION_PORTRAIT, fontScale = 2f)
    }

    @Test fun baselinePortraitShowsACompleteGridCard(): Unit = runBlocking {
        verifyGrids(Configuration.ORIENTATION_PORTRAIT, 1.15f, listOf(Destination.TIMELINE))
    }

    @Test fun searchFontReflowKeepsTheBookmarkWhenTheInputIsOffscreen(): Unit = runBlocking {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requestOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
                val activity = awaitActivity(scenario, Configuration.ORIENTATION_PORTRAIT)
                var scale by mutableStateOf(1.15f)
                lateinit var model: LibraryViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[LibraryViewModel::class.java]
                    model.setViewMode(LibraryViewMode.GRID)
                    it.setContent {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            TidiloTheme { SearchScreen(viewModel = model) }
                        }
                    }
                }
                val header = activity.getString(R.string.search_header_title)
                fixture.awaitNode(activity) { fixture.host.text(it, header) }
                fixture.host.main { model.onSearchQueryChanged(fixture.searchToken) }
                val resultCount = activity.getString(R.string.search_header_subtitle_results, fixture.bookmarkCount)
                fixture.awaitNode(activity) { fixture.host.text(it, resultCount) }
                fixture.awaitTitle(activity, 0)
                val bookmark = 8
                fixture.scrollToItem(activity, bookmark + 1)
                fixture.awaitTopRow(scenario, "Search anchor before font reflow") { bookmark in it }
                fixture.scroll(activity, fixture.cardHeight(activity, bookmark) * 0.7f)
                delay(500)
                assertFalse("Search input should be outside the viewport before reflow",
                    fixture.hasVisibleSearchInput(activity))
                fixture.host.main { scale = 2f }
                fixture.awaitTopRow(scenario, "Search anchor after font reflow") { it == listOf(bookmark) }
            }
        }
    }

    @Test fun largeTextInLandscapeCanScrollToTheCompleteSearchCard(): Unit = runBlocking {
        verifyGrids(Configuration.ORIENTATION_LANDSCAPE, 2f, listOf(Destination.SEARCH), scrollToFit = true)
    }

    @Test fun listAndGridKeepIndependentScrollPositions(): Unit = runBlocking {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requestOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
                val activity = awaitActivity(scenario, Configuration.ORIENTATION_PORTRAIT)
                setMode(scenario, LibraryViewMode.GRID)
                fixture.awaitTitle(activity, 0)
                fixture.scrollToItem(activity, 10)
                fixture.awaitTitle(activity, 10)
                val gridPosition = fixture.cardTop(activity, 10)

                setMode(scenario, LibraryViewMode.LIST)
                fixture.awaitTitle(activity, 0)
                fixture.scrollToItem(activity, 20)
                fixture.awaitTitle(activity, 20)
                val listPosition = fixture.cardTop(activity, 20)

                setMode(scenario, LibraryViewMode.GRID)
                fixture.awaitTitle(activity, 10)
                assertEquals(gridPosition, fixture.cardTop(activity, 10), 2f)
                setMode(scenario, LibraryViewMode.LIST)
                fixture.awaitTitle(activity, 20)
                assertEquals(listPosition, fixture.cardTop(activity, 20), 2f)
            }
        }
    }

    @Test fun rotationKeepsTheLeadingBookmarkAndPagingCanAppend(): Unit = runBlocking {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requestOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
                val activity = awaitActivity(scenario, Configuration.ORIENTATION_PORTRAIT)
                setMode(scenario, LibraryViewMode.GRID)
                fixture.awaitTitle(activity, 0)
                val startRow = fixture.awaitTopRow(scenario, "initial portrait row") { 0 in it }
                val bookmark = 8
                fixture.scrollToItem(activity, bookmark)
                fixture.awaitTitle(activity, bookmark)
                // Move close to the bottom of the taller portrait row before rotating.
                fixture.scroll(activity, fixture.cardHeight(activity, bookmark) * 0.9f)
                delay(500)

                requestOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
                awaitActivity(scenario, Configuration.ORIENTATION_LANDSCAPE)
                val landscapeRow = fixture.awaitTopRow(scenario, "landscape anchor $bookmark") { bookmark in it }
                assertTrue("Rotation must change the leading row width", landscapeRow.size > startRow.size)
                requestOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
                val rotated = awaitActivity(scenario, Configuration.ORIENTATION_PORTRAIT)
                fixture.awaitTopRow(scenario, "portrait anchor ${landscapeRow.first()}") { landscapeRow.first() in it }

                withTimeout(20_000) {
                    while (!fixture.hasVisibleTitle(rotated, 74)) {
                        fixture.scroll(rotated, 1400f)
                        delay(140)
                    }
                }
                assertTrue("The final bookmark must come from an appended page", fixture.hasVisibleTitle(rotated, 74))
            }
        }
    }

    private suspend fun setMode(scenario: ActivityScenario<MainActivity>, mode: LibraryViewMode) {
        scenario.onActivity {
            ViewModelProvider(it)[LibraryViewModel::class.java].apply {
                setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                setViewMode(mode)
            }
        }
        delay(400)
    }

    private suspend fun verifyGrids(
        orientation: Int,
        fontScale: Float,
        destinations: List<Destination> = Destination.entries,
        scrollToFit: Boolean = false
    ) {
        withFixture { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                requestOrientation(scenario, orientation)
                val activity = awaitActivity(scenario, orientation)
                val args = InstrumentationRegistry.getArguments()
                val form = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
                val capturePrefix = args.getString("gridCapturePrefix")?.let { "$it-$form-${(fontScale * 100).toInt()}" }
                var theme by mutableStateOf(AppTheme.DEFAULT to false)
                var search by mutableStateOf(false)
                lateinit var model: LibraryViewModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[LibraryViewModel::class.java]
                    model.setViewMode(LibraryViewMode.GRID)
                    model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                    it.setContent {
                        val density = LocalDensity.current
                        val testDensity = if (density.fontScale == fontScale) density else Density(density.density, fontScale)
                        CompositionLocalProvider(LocalDensity provides testDensity) {
                            TidiloTheme(darkTheme = theme.second, theme = theme.first) {
                                if (search) SearchScreen(viewModel = model, onNavigateBack = {})
                                else LibraryScreen(viewModel = model)
                            }
                        }
                    }
                }
                for (destination in destinations) {
                    val header = when (destination) {
                        Destination.TIMELINE -> activity.getString(R.string.library_header_title)
                        Destination.DOMAIN -> fixture.domain
                        Destination.RSS -> fixture.feed.title
                        Destination.COLLECTION -> fixture.collection.title
                        Destination.SEARCH -> activity.getString(R.string.search_header_title)
                    }
                    fixture.host.main {
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
                                model.onSearchQueryChanged(fixture.searchToken)
                                search = true
                            }
                        }
                    }
                    val first = fixture.firstBookmark(destination)
                    try {
                        fixture.awaitNode(activity) { fixture.host.text(it, header) }
                        fixture.awaitNode(activity) {
                            fixture.host.text(it, first.title) && it.boundsInWindow.height > 0
                        }
                    } catch (timeout: TimeoutCancellationException) {
                        throw AssertionError("$destination did not show '$header' and its first bookmark", timeout)
                    }
                    val initialSearchCardSize = if (destination == Destination.SEARCH) {
                        fixture.cardSize(activity, first)
                    } else null
                    delay(350)
                    if (initialSearchCardSize != null) {
                        val settled = fixture.cardSize(activity, first)
                        assertTrue("Search card must not reflow: $initialSearchCardSize to $settled",
                            abs(initialSearchCardSize.width - settled.width) <= 1 &&
                                abs(initialSearchCardSize.height - settled.height) <= 1)
                    }
                    if (scrollToFit) {
                        val overflow = fixture.firstCardBottomOverflow(activity, first)
                        if (overflow > 0f) fixture.scroll(activity, overflow + 8 * activity.resources.displayMetrics.density)
                        delay(450)
                    }
                    fixture.assertFirstCardVisible(activity, destination, scrollToFit)
                    if (capturePrefix != null || destination == Destination.TIMELINE || destination == Destination.SEARCH) {
                        val schemes = if (destination == Destination.TIMELINE || destination == Destination.SEARCH) {
                            AppTheme.entries.flatMap { appTheme -> listOf(appTheme to false, appTheme to true) }
                        } else {
                            listOf(if (fontScale == 2f) AppTheme.MODERN to true else AppTheme.DEFAULT to false)
                        }
                        for (scheme in schemes) {
                            fixture.host.main { theme = scheme }
                            fixture.host.instrumentation.waitForIdleSync()
                            fixture.assertFirstCardVisible(activity, destination, scrollToFit)
                            val appearance = if (scheme.second) "dark" else "light"
                            if (capturePrefix != null) {
                                fixture.host.capture("$capturePrefix-${destination.name.lowercase()}-${scheme.first.code}-$appearance")
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = withContext(Dispatchers.IO) { Fixture() }
        val oldDiscoverer = DomainIconManager.htmlDiscoverer
        val oldDownloader = DomainIconManager.httpDownloader
        try {
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
            fixture.seed()
            block(fixture)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                DomainIconManager.htmlDiscoverer = oldDiscoverer
                DomainIconManager.httpDownloader = oldDownloader
                fixture.clear()
            }
        }
    }

    private fun requestOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) {
        scenario.onActivity {
            it.requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private suspend fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        orientation: Int
    ): MainActivity {
        var activity: MainActivity? = null
        var observedOrientation = Configuration.ORIENTATION_UNDEFINED
        try {
            withTimeout(8_000) {
                while (activity == null) {
                    scenario.onActivity {
                        observedOrientation = it.resources.configuration.orientation
                        if (observedOrientation == orientation) activity = it
                    }
                    if (activity == null) delay(30)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Expected orientation $orientation, observed $observedOrientation", timeout)
        }
        return requireNotNull(activity)
    }

    private enum class Destination { TIMELINE, DOMAIN, RSS, COLLECTION, SEARCH }

    private class Fixture {
        val host = HeaderTestHost()
        val searchToken = "gridfixture${UUID.randomUUID().toString().replace("-", "")}"
        val domain = "$searchToken.invalid"
        val feed = RssFeedEntity(searchToken, "https://$domain/rss", "Adaptive Grid RSS", enabled = false)
        private val now = System.currentTimeMillis()
        val collection = CollectionEntity(searchToken, "Adaptive Grid Collection", "", null, null, 0, now, now)
        private val bookmarks = List(75) { index ->
            BookmarkEntity(
                "$searchToken-$index", "Adaptive grid card $index $searchToken", "https://$domain/$index", "Summary",
                createdAt = now + 60_000L - index, updatedAt = now
            )
        }
        val bookmarkCount get() = bookmarks.size
        private val rssBookmark = BookmarkEntity(
            "$searchToken-rss", "Adaptive RSS card", "https://rss-${UUID.randomUUID()}.invalid/article", "Summary",
            createdAt = now + 60_000L, updatedAt = now, rssFeedId = feed.id
        )
        private val db = AppDatabase.getInstance(host.context)
        private val prefs = host.context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
        private val savedPrefs = listOf("library_filter_mode", "library_view_mode").associateWith { prefs.getString(it, null) }

        suspend fun seed() {
            db.rssDao().putFeed(feed)
            db.collectionDao().insert(collection)
            db.bookmarkDao().insertAll(bookmarks + rssBookmark)
            bookmarks.forEach { db.collectionDao().insertMembership(BookmarkCollectionCrossRef(it.id, collection.id)) }
        }

        suspend fun clear() {
            try {
                db.collectionDao().delete(collection.id)
                bookmarks.forEach { db.bookmarkDao().deleteById(it.id) }
                db.bookmarkDao().deleteById(rssBookmark.id)
                db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(feed.id))
            } finally {
                val editor = prefs.edit()
                savedPrefs.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                check(editor.commit())
            }
        }

        suspend fun awaitTitle(activity: ComponentActivity, index: Int) =
            try {
                awaitNode(activity) { host.text(it, bookmarks[index].title) && it.boundsInWindow.height > 0 }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Bookmark $index did not become visible", timeout)
            }

        fun hasVisibleSearchInput(activity: ComponentActivity): Boolean {
            var visible = false
            host.main {
                visible = nodes(activity).any {
                    it.config.getOrNull(SemanticsActions.SetText) != null && it.boundsInWindow.height > 0
                }
            }
            return visible
        }

        suspend fun awaitNode(activity: ComponentActivity, predicate: (SemanticsNode) -> Boolean): SemanticsNode =
            withTimeout(8_000) {
                var node: SemanticsNode? = null
                while (node == null) {
                    host.main { node = nodes(activity).firstOrNull(predicate) }
                    if (node == null) delay(30)
                }
                requireNotNull(node)
            }

        private fun nodes(activity: ComponentActivity): List<SemanticsNode> =
            host.nodes(activity, includeOtherWindows = false)

        suspend fun scroll(activity: ComponentActivity, pixels: Float) {
            val scroll = awaitNode(activity) {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null
            }
            host.main { assertTrue(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, pixels) == true) }
        }

        suspend fun scrollToItem(activity: ComponentActivity, index: Int) {
            val scroll = awaitNode(activity) { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
            host.main { assertTrue(scroll.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true) }
            delay(400)
        }

        fun firstBookmark(destination: Destination): BookmarkEntity =
            if (destination == Destination.RSS) rssBookmark else bookmarks.first()

        fun hasVisibleTitle(activity: ComponentActivity, index: Int): Boolean {
            var visible = false
            host.main {
                visible = nodes(activity).any { host.text(it, bookmarks[index].title) && it.boundsInWindow.height > 0 }
            }
            return visible
        }

        fun firstCardBottomOverflow(activity: ComponentActivity, bookmark: BookmarkEntity): Float {
            var overflow = 0f
            host.main {
                val card = cardFor(nodes(activity).first { host.text(it, bookmark.title) })
                overflow = card.boundsInWindow.top + card.size.height - safeBottom(activity)
            }
            return overflow
        }

        private fun safeBottom(activity: ComponentActivity): Int = activity.window.decorView.height -
            activity.window.decorView.rootWindowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            ).bottom

        fun assertFirstCardVisible(activity: ComponentActivity, destination: Destination, afterScroll: Boolean) {
            host.main {
                val nodes = nodes(activity)
                val bookmark = firstBookmark(destination)
                val title = nodes.first { host.text(it, bookmark.title) }
                val card = cardFor(title)
                val bounds = card.boundsInWindow
                val safe = activity.window.decorView.rootWindowInsets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val relativeTime = TimeFormatter.formatRelativeTime(bookmark.createdAt, activity)
                val date = nodes.first {
                    host.text(it, relativeTime) && generateSequence(it) { node -> node.parent }.any { node -> node.id == card.id }
                }
                val input = nodes.firstOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertEquals("$destination card is clipped: $bounds", card.size.height.toFloat(), bounds.height, 2f)
                assertTrue("$destination card top overlaps a system bar", bounds.top >= safe.top)
                assertTrue("$destination card $bounds overlaps bottom inset ${safe.bottom}, " +
                    "window height ${activity.window.decorView.height}, top inset ${safe.top}, " +
                    "input ${input?.boundsInWindow}, title ${title.boundsInWindow}, date ${date.boundsInWindow}",
                    bounds.bottom <= safeBottom(activity) + 1f)
                assertRenderedText(title)
                assertRenderedText(date)
                assertTrue("$destination date is visible below the title",
                    date.boundsInWindow.top >= title.boundsInWindow.bottom && date.boundsInWindow.bottom <= bounds.bottom)
                if (destination == Destination.SEARCH && !afterScroll) {
                    checkNotNull(input)
                    assertTrue("The Search field is visible above the first card",
                        input.boundsInWindow.height > 0 && input.boundsInWindow.bottom <= bounds.top)
                } else if (destination != Destination.SEARCH) {
                    val trigger = checkNotNull(nodes.firstOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger"
                    }) {
                        "Library shortcut missing for $destination"
                    }
                    assertFalse("First card overlaps the Library shortcut", bounds.overlaps(trigger.boundsInWindow))
                }
            }
        }

        fun cardTop(activity: ComponentActivity, index: Int): Float {
            var top = 0f
            host.main { top = cardFor(nodes(activity).first { host.text(it, bookmarks[index].title) }).boundsInWindow.top }
            return top
        }

        fun cardSize(activity: ComponentActivity, bookmark: BookmarkEntity): androidx.compose.ui.unit.IntSize {
            var size = androidx.compose.ui.unit.IntSize.Zero
            host.main { size = cardFor(nodes(activity).first { host.text(it, bookmark.title) }).size }
            return size
        }

        fun cardHeight(activity: ComponentActivity, index: Int): Int {
            var height = 0
            host.main { height = cardFor(nodes(activity).first { host.text(it, bookmarks[index].title) }).size.height }
            return height
        }

        suspend fun awaitTopRow(
            scenario: ActivityScenario<MainActivity>,
            stage: String,
            predicate: (List<Int>) -> Boolean
        ): List<Int> {
            var row = emptyList<Int>()
            try {
                withTimeout(8_000) {
                    do {
                        scenario.onActivity { activity ->
                            val nodes = nodes(activity)
                            val topInset = activity.window.decorView.rootWindowInsets.getInsets(
                                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                            ).top
                            val headerClearance = HeaderDefaults.ContentHeight + HeaderDefaults.FadeHeight +
                                HeaderDefaults.ClearanceGap
                            val bodyTop = topInset + headerClearance.value * activity.resources.displayMetrics.density
                            val visibleCards = bookmarks.mapIndexedNotNull { index, item ->
                                val title = nodes.firstOrNull { host.text(it, item.title) } ?: return@mapIndexedNotNull null
                                val bounds = cardFor(title).boundsInWindow
                                if (bounds.height > 0 && bounds.top >= bodyTop - 2f) index to bounds.top else null
                            }
                            val rowTop = visibleCards.minOfOrNull { it.second }
                            row = if (rowTop == null) emptyList() else
                                visibleCards.filter { abs(it.second - rowTop) < 2 }.map { it.first }
                        }
                        if (row.isNotEmpty() && predicate(row)) break
                        delay(30)
                    } while (true)
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$stage did not settle. Last top row was $row", timeout)
            }
            return row
        }

        private fun cardFor(title: SemanticsNode): SemanticsNode =
            generateSequence(title) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }

        private fun assertRenderedText(node: SemanticsNode) {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
            val text = layouts.single()
            assertTrue(text.getLineBottom(text.lineCount - 1) <= node.boundsInWindow.height + 1f)
        }
    }
}
