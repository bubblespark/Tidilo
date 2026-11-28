package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.engagement.EngagementSurface
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The Library layer over an in-memory store: tabs, plates, decks, Peek, empty states. */
class LibraryLayerTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val domain = "${UUID.randomUUID().toString().take(8)}.example"
    private val modelScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var caches: OfflineVisualCaches

    @Before fun images() {
        caches = OfflineVisualCaches { tinyPng(16, 9) }
    }

    @After fun restore() {
        modelScope.cancel()
        caches.close()
    }

    private fun item(id: String, image: Boolean = true, rss: Boolean = false) = BookmarkItem(
        id, "Title of $id", "https://$domain/$id", "Summary of $id", Tag.createEmpty(),
        createdAt = System.currentTimeMillis() - 3L * 86_400_000,
        ogImageUrl = if (image) "https://$domain/wide-$id.png" else null,
        rssFeedId = if (rss) "feed" else null
    )

    private companion object {
        val DONE = androidx.paging.LoadStates(
            androidx.paging.LoadState.NotLoading(true), androidx.paging.LoadState.NotLoading(true), androidx.paging.LoadState.NotLoading(true))
    }

    private class FakeStore(
        private val viewMode: LibraryViewMode = LibraryViewMode.GRID,
        private val includeRss: Boolean = false
    ) : LibraryStore {
        override val isInitialized = MutableStateFlow(true)
        override val initializationFailure = MutableStateFlow<Throwable?>(null)
        val items = MutableStateFlow<List<BookmarkItem>>(emptyList())
        val domains = MutableStateFlow<List<DomainGroupItem>>(emptyList())
        /** `null` until the subscription count query has emitted. */
        val feeds = MutableStateFlow<Int?>(0)
        val likes = CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        val peeks = CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        override fun retryInitialization() = Unit
        override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>> =
            // Explicit load states: a static empty page otherwise leaves LazyPagingItems in its initial Loading.
            items.map { PagingData.from(it, DONE) }
        override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>> = flowOf(PagingData.empty())
        override fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>> =
            items.map { list -> PagingData.from(if (query.isBlank()) emptyList() else list.map { BookmarkSearchResult(it, emptySet()) }, DONE) }
        override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) = items.map { it.size }
        override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) = flowOf(0)
        override fun observeDomainGroups(showPrivate: Boolean) = domains
        override fun observeRssFeedGroups(showPrivate: Boolean) = flowOf(emptyList<RssFeedGroup>())
        override fun observeRssFeedCount() = feeds.filterNotNull()
        override fun observeCollections(showPrivate: Boolean) = flowOf(emptyList<CollectionWithCount>())
        override fun observeMembershipIds(bookmarkId: String) = flowOf(emptyList<String>())
        override fun observeBookmark(id: String) = items.map { list -> list.firstOrNull { it.id == id } }
        override fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind) = flowOf(emptyList<TagCount>())
        override fun observeEntityLibrary() = flowOf(emptyMap<String, String>())
        override suspend fun toggleLike(id: String, surface: EngagementSurface) {
            likes += id to surface
            items.value = items.value.map { if (it.id == id) it.copy(isLiked = !it.isLiked) else it }
        }
        override suspend fun recordBrowserOpen(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordDetailView(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordSummaryPeek(id: String, surface: EngagementSurface) { peeks += id to surface }
        override suspend fun saveNote(id: String, note: String) = Unit
        override suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) = Unit
        override suspend fun applyAiProposal(id: String, description: String?, tag: Tag?) = true
        override suspend fun moveToTrash(id: String) = true
        override fun loadFilterMode() = LibraryFilterMode.NEWEST_FIRST
        override suspend fun saveFilterMode(mode: LibraryFilterMode) = Unit
        override fun loadViewMode() = viewMode
        override fun saveViewMode(mode: LibraryViewMode) = Unit
        override fun loadIncludeRssInNewestFirst() = includeRss
        override fun saveIncludeRssInNewestFirst(include: Boolean) = Unit
        override suspend fun loadSearchHistory() = emptyList<String>()
        override suspend fun saveSearchHistory(history: List<String>) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    private class Calls {
        val opened = CopyOnWriteArrayList<String>()
        val saves = AtomicInteger()
        val rssSettings = AtomicInteger()
    }

    private fun show(store: FakeStore, calls: Calls = Calls(), fontScale: Float = 1f): LibraryViewModel {
        lateinit var model: LibraryViewModel
        composeRule.runOnUiThread { model = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true), modelScope) }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    LibraryScreen(
                        viewModel = model,
                        onOpenManualImport = { calls.saves.incrementAndGet() },
                        onNavigateToDetail = { calls.opened += it },
                        onOpenRssSettings = { calls.rssSettings.incrementAndGet() }
                    )
                }
            }
        }
        return model
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }
    private fun awaitAbsent(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) { nodes(matcher).isEmpty() }
    private fun until(condition: () -> Boolean) = composeRule.waitUntil(8_000, condition)
    private fun text(value: String) = hasText(value)
    private fun tag(value: String) = hasTestTag(value)
    private val checked = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)
    private fun clickable(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }
    private fun click(node: SemanticsNode) {
        composeRule.runOnUiThread { check(clickable(node).config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.waitForIdle()
    }
    private fun longClick(node: SemanticsNode) {
        composeRule.runOnUiThread { node.config[SemanticsActions.OnLongClick].action?.invoke() }
        composeRule.waitForIdle()
    }
    private fun isButton(node: SemanticsNode) =
        clickable(node).config.getOrNull(SemanticsProperties.Role) == Role.Button
    private fun <T> step(name: String, block: () -> T): T =
        try { block() } catch (e: ComposeTimeoutException) { throw AssertionError("Timed out at: $name", e) }
    private fun describes(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value in it } == true

    @Test fun tabsSwitchModesAndHideInDrillDown() {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        store.domains.value = listOf(DomainGroupItem(domain, domain, 1))
        val model = show(store)
        await(tag("library_tab_NEWEST_FIRST") and checked)
        await(text(context.getString(R.string.library_header_title)))
        click(await(tag("library_tab_DOMAIN")))
        until { model.filterMode.value == LibraryFilterMode.DOMAIN }
        await(tag("library_tab_DOMAIN") and checked)
        // An unnamed domain shows its name once (no duplicate subtitle) and a singular count.
        awaitAbsent(tag("library_card_a"))
        val count = context.resources.getQuantityString(R.plurals.domain_card_item_count, 1, 1)
        val deck = await(text(count))
        assertEquals(1, nodes(text(domain)).size)
        click(deck)
        until { model.selectedDomainFilter.value == domain }
        awaitAbsent(tag("library_tabs"))
        await(tag("library_card_a"))
        click(await(hasContentDescription(context.getString(R.string.library_back_to_domains))))
        await(tag("library_tabs"))
    }

    @Test fun newestShowsPlatesTogglesListAndOpensDetail() {
        val calls = Calls()
        val store = FakeStore(includeRss = true)
        store.items.value = listOf(item("a"), item("poster", image = false), item("feed", rss = true))
        val model = show(store, calls)
        await(tag("library_card_a"))
        await(tag("library_card_poster"))
        val rss = await(tag("library_card_feed"))
        assertTrue(describes(rss, context.getString(R.string.rss_title)))
        click(await(tag("library_card_a")))
        assertEquals(listOf("a"), calls.opened.toList())
        click(await(tag("library_view_toggle")))
        until { model.viewMode.value == LibraryViewMode.LIST }
        await(tag("library_row_a"))
        awaitAbsent(tag("library_card_a"))
    }

    @Test fun longPressOpensLibraryPeekSheet() {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        show(store)
        val card = await(tag("library_card_a"))
        assertEquals(context.getString(R.string.discover_peek), card.config[SemanticsActions.OnLongClick].label)
        longClick(card)
        await(tag("discover_peek_sheet"))
        assertTrue("Library Peek has no Not now", nodes(tag("discover_peek_snooze")).isEmpty())
        assertTrue("Library Peek has no Don't show again", nodes(tag("discover_peek_never")).isEmpty())
        until { store.peeks.toList() == listOf("a" to EngagementSurface.LIBRARY) }
        click(await(tag("discover_peek_like")))
        until { store.likes.toList() == listOf("a" to EngagementSurface.LIBRARY) }
        await(text(context.getString(R.string.discover_deck_unlike)))
        click(await(tag("discover_peek_collection")))
        await(text("Choose collections"))
        awaitAbsent(tag("discover_peek_sheet"))
    }

    @Test fun searchLongPressOpensPeekSheet() {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        lateinit var model: LibraryViewModel
        composeRule.runOnUiThread { model = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true), modelScope) }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { SearchScreen(viewModel = model) }
        }
        val peekSheet = tag("discover_peek_sheet")
        val searchCard = SemanticsMatcher("Search card with Peek") {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == context.getString(R.string.library_summary_peek_open)
        }
        composeRule.runOnUiThread { model.onSearchQueryChanged("plate") }
        step("long-press opens Peek") {
            longClick(await(searchCard))
            await(peekSheet)
        }
        until { store.peeks.toList() == listOf("a" to EngagementSurface.SEARCH) }
        assertTrue("Search Peek has no Not now", nodes(tag("discover_peek_snooze")).isEmpty())
        assertTrue("Search Peek has no Don't show again", nodes(tag("discover_peek_never")).isEmpty())
        click(await(tag("discover_peek_like")))
        until { store.likes.toList() == listOf("a" to EngagementSurface.SEARCH) }
        step("Back closes only the sheet") {
            // System Back reaches the sheet's own window first.
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            awaitAbsent(peekSheet)
            await(text(context.getString(R.string.search_header_title)))
        }
        step("a new query closes the sheet") {
            longClick(await(searchCard))
            await(peekSheet)
            composeRule.runOnUiThread { model.onSearchQueryChanged("plates") }
            awaitAbsent(peekSheet)
        }
    }

    @Test fun emptyStatesOfferTheirActions() {
        val calls = Calls()
        val store = FakeStore()
        show(store, calls)
        step("1") { await(tag("library_empty_NEWEST")) }
        click(step("2") { await(tag("library_empty_action")) })
        assertEquals(1, calls.saves.get())

        click(step("3") { await(tag("library_tab_DOMAIN")) })
        step("4") { await(tag("library_empty_DOMAIN")) }
        awaitAbsent(tag("library_empty_NEWEST"))
        click(step("5") { await(tag("library_empty_action")) })
        assertEquals(2, calls.saves.get())

        click(step("6") { await(tag("library_tab_RSS")) })
        step("7") { await(tag("library_empty_RSS")) }
        awaitAbsent(tag("library_empty_DOMAIN"))
        click(step("8") { await(tag("library_empty_action")) })
        assertEquals(1, calls.rssSettings.get())
        store.feeds.value = 2
        step("9") { await(tag("library_empty_RSS_IDLE")) }

        click(step("10") { await(tag("library_tab_COLLECTION")) })
        step("11") { await(tag("library_empty_COLLECTION")) }
        awaitAbsent(tag("library_empty_RSS_IDLE"))
        assertTrue("No dashed add card while empty",
            nodes(hasContentDescription(context.getString(R.string.collection_create_title))).isEmpty())
        click(step("12") { await(tag("library_empty_action")) })
        step("13") { await(text(context.getString(R.string.collection_create_title))) }
    }

    /** PR #335 R-001: plate, deck, and collection cards are announced as buttons. */
    @Test fun libraryCardsExposeButtonRole() {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        store.domains.value = listOf(DomainGroupItem(domain, "Named site", 1))
        show(store)
        assertTrue("Plate card", isButton(await(tag("library_card_a"))))
        click(await(tag("library_tab_DOMAIN")))
        assertTrue("Domain deck", isButton(await(text("Named site"))))
    }

    /** PR #335 R-002: at 200% font the deck's 2-line title, subtitle, and count pill stay inside the card. */
    @Test fun galleryDeckFitsAtDoubleFontScale() {
        val store = FakeStore()
        store.domains.value = listOf(DomainGroupItem(domain, "A long site name that wraps onto two lines", 12))
        val model = show(store, fontScale = 2f)
        composeRule.runOnIdle { model.setFilterMode(LibraryFilterMode.DOMAIN) }
        val count = context.resources.getQuantityString(R.plurals.domain_card_item_count, 12, 12)
        val pill = await(text(count))
        val card = clickable(pill)
        val subtitle = nodes(text(domain)).first()
        assertTrue("Count pill bottom ${pill.boundsInRoot.bottom} inside card ${card.boundsInRoot.bottom}",
            pill.boundsInRoot.bottom <= card.boundsInRoot.bottom)
        assertTrue("Subtitle must not overlap the count pill", subtitle.boundsInRoot.bottom <= pill.boundsInRoot.top)
        // labelMedium is 16sp tall; at 2x it needs ~32dp. A squashed pill measures far less.
        val minHeight = 28f * context.resources.displayMetrics.density
        assertTrue("Count pill text squashed to ${pill.boundsInRoot.height}px", pill.boundsInRoot.height >= minHeight)
    }

    /** PR #335 R-003: subscribed feeds with nothing visible never flash the "no feeds" state. */
    @Test fun rssEmptyStateWaitsForSubscriptionCount() {
        val store = FakeStore()
        store.feeds.value = null
        val model = show(store)
        composeRule.runOnIdle { model.setFilterMode(LibraryFilterMode.RSS) }
        await(tag("library_tab_RSS") and checked)
        composeRule.mainClock.autoAdvance = false
        try {
            repeat(5) {
                composeRule.mainClock.advanceTimeByFrame()
                assertTrue("No-feeds state shown before the count is known", nodes(tag("library_empty_RSS")).isEmpty())
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        store.feeds.value = 2
        await(tag("library_empty_RSS_IDLE"))
    }
}
