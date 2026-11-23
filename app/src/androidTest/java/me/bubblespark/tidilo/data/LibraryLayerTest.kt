package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Library layer (Discover preview on) over an in-memory store: tabs, plates, decks, Peek, empty states. */
class LibraryLayerTest {
    private val host = HeaderTestHost()
    private val domain = "${UUID.randomUUID().toString().take(8)}.example"
    private val oldDownloader = OgImageManager.imageDownloader
    private val oldIconDownload = DomainIconManager.httpDownloader
    private val oldDiscovery = DomainIconManager.htmlDiscoverer

    @Before fun images() {
        OgImageManager.imageDownloader = { url ->
            val bitmap = Bitmap.createBitmap(1200, 630, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.HSVToColor(floatArrayOf((url.hashCode() and 0xff) * 1.4f, 0.45f, 0.75f)))
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
        }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
    }

    @After fun restore() {
        OgImageManager.imageDownloader = oldDownloader
        DomainIconManager.httpDownloader = oldIconDownload
        DomainIconManager.htmlDiscoverer = oldDiscovery
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
        val feeds = MutableStateFlow(0)
        val likes = CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        val peeks = CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        override fun retryInitialization() = Unit
        override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>> =
            // Explicit load states: a static empty page otherwise leaves LazyPagingItems in its initial Loading.
            items.map { PagingData.from(it, DONE) }
        override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>> = flowOf(PagingData.empty())
        override fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>> = flowOf(PagingData.empty())
        override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) = items.map { it.size }
        override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) = flowOf(0)
        override fun observeDomainGroups(showPrivate: Boolean) = domains
        override fun observeRssFeedGroups(showPrivate: Boolean) = flowOf(emptyList<RssFeedGroup>())
        override fun observeRssFeedCount() = feeds
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

    private fun show(store: FakeStore, calls: Calls = Calls()): Pair<ComponentActivity, LibraryViewModel> {
        val activity = host.launch()
        lateinit var model: LibraryViewModel
        host.main {
            model = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true))
            activity.setContent {
                TidiloTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    LibraryScreen(
                        viewModel = model,
                        onOpenManualImport = { calls.saves.incrementAndGet() },
                        onNavigateToDetail = { calls.opened += it },
                        showRadialMenu = false,
                        discoverPreview = true,
                        onOpenRssSettings = { calls.rssSettings.incrementAndGet() }
                    )
                }
            }
        }
        return activity to model
    }

    private fun tag(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.TestTag) == value
    private fun clickable(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }
    private fun click(node: SemanticsNode) = host.main { check(clickable(node).config[SemanticsActions.OnClick].action?.invoke() == true) }
    private fun checked(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    private suspend fun until(condition: () -> Boolean) = withTimeout(8000) { while (!condition()) delay(30) }
    private suspend fun <T> step(name: String, block: suspend () -> T): T =
        try { block() } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out at: $name", e) }
    private fun describes(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value in it } == true

    @Test fun tabsSwitchModesAndHideInDrillDown(): Unit = runBlocking {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        store.domains.value = listOf(DomainGroupItem(domain, domain, 1))
        val (activity, model) = show(store)
        try {
            host.await(activity) { tag(it, "library_tab_NEWEST_FIRST") && checked(it) }
            host.await(activity) { host.text(it, host.context.getString(R.string.library_header_title)) }
            click(host.await(activity) { tag(it, "library_tab_DOMAIN") })
            until { model.filterMode.value == LibraryFilterMode.DOMAIN }
            host.await(activity) { tag(it, "library_tab_DOMAIN") && checked(it) }
            // An unnamed domain shows its name once (no duplicate subtitle) and a singular count.
            host.awaitAbsent(activity) { tag(it, "library_card_a") }
            val count = host.context.resources.getQuantityString(R.plurals.domain_card_item_count, 1, 1)
            val deck = host.await(activity) { host.text(it, count) }
            host.main { assertEquals(1, host.nodes(activity).count { host.text(it, domain) }) }
            click(deck)
            until { model.selectedDomainFilter.value == domain }
            host.awaitAbsent(activity) { tag(it, "library_tabs") }
            host.await(activity) { tag(it, "library_card_a") }
            click(host.await(activity) { host.description(it, host.context.getString(R.string.library_back_to_domains)) })
            host.await(activity) { tag(it, "library_tabs") }
            host.capture("library-layer-domain")
        } finally { activity.finish() }
    }

    @Test fun newestShowsPlatesTogglesListAndOpensDetail(): Unit = runBlocking {
        val calls = Calls()
        val store = FakeStore(includeRss = true)
        store.items.value = listOf(item("a"), item("poster", image = false), item("feed", rss = true))
        val (activity, model) = show(store, calls)
        try {
            host.await(activity) { tag(it, "library_card_a") }
            host.await(activity) { tag(it, "library_card_poster") }
            val rss = host.await(activity) { tag(it, "library_card_feed") }
            assertTrue(describes(rss, host.context.getString(R.string.rss_title)))
            host.capture("library-layer-grid")
            click(host.await(activity) { tag(it, "library_card_a") })
            assertEquals(listOf("a"), calls.opened.toList())
            click(host.await(activity) { tag(it, "library_view_toggle") })
            until { model.viewMode.value == LibraryViewMode.LIST }
            host.await(activity) { tag(it, "library_row_a") }
            host.awaitAbsent(activity) { tag(it, "library_card_a") }
            host.capture("library-layer-list")
        } finally { activity.finish() }
    }

    @Test fun longPressOpensLibraryPeekSheet(): Unit = runBlocking {
        val store = FakeStore()
        store.items.value = listOf(item("a"))
        val (activity, _) = show(store)
        try {
            val card = host.await(activity) { tag(it, "library_card_a") }
            host.main {
                assertEquals(host.context.getString(R.string.discover_peek), card.config[SemanticsActions.OnLongClick].label)
                card.config[SemanticsActions.OnLongClick].action?.invoke()
            }
            host.await(activity) { tag(it, "discover_peek_sheet") }
            host.main {
                val nodes = host.nodes(activity)
                assertTrue("Library Peek has no Not now", nodes.none { tag(it, "discover_peek_snooze") })
                assertTrue("Library Peek has no Don't show again", nodes.none { tag(it, "discover_peek_never") })
            }
            until { store.peeks.toList() == listOf("a" to EngagementSurface.LIBRARY) }
            click(host.await(activity) { tag(it, "discover_peek_like") })
            until { store.likes.toList() == listOf("a" to EngagementSurface.LIBRARY) }
            host.await(activity) { host.text(it, host.context.getString(R.string.discover_deck_unlike)) }
            host.capture("library-layer-peek")
            click(host.await(activity) { tag(it, "discover_peek_collection") })
            host.await(activity) { host.text(it, "Choose collections") }
            host.awaitAbsent(activity) { tag(it, "discover_peek_sheet") }
        } finally { activity.finish() }
    }

    @Test fun emptyStatesOfferTheirActions(): Unit = runBlocking {
        val calls = Calls()
        val store = FakeStore()
        val (activity, _) = show(store, calls)
        try {
            step("1") { host.await(activity) { tag(it, "library_empty_NEWEST") } }
            click(step("2") { host.await(activity) { tag(it, "library_empty_action") } })
            assertEquals(1, calls.saves.get())

            click(step("3") { host.await(activity) { tag(it, "library_tab_DOMAIN") } })
            step("4") { host.await(activity) { tag(it, "library_empty_DOMAIN") } }
            host.awaitAbsent(activity) { tag(it, "library_empty_NEWEST") }
            click(step("5") { host.await(activity) { tag(it, "library_empty_action") } })
            assertEquals(2, calls.saves.get())

            click(step("6") { host.await(activity) { tag(it, "library_tab_RSS") } })
            step("7") { host.await(activity) { tag(it, "library_empty_RSS") } }
            host.awaitAbsent(activity) { tag(it, "library_empty_DOMAIN") }
            click(step("8") { host.await(activity) { tag(it, "library_empty_action") } })
            assertEquals(1, calls.rssSettings.get())
            store.feeds.value = 2
            step("9") { host.await(activity) { tag(it, "library_empty_RSS_IDLE") } }
            host.capture("library-layer-rss-idle")

            click(step("10") { host.await(activity) { tag(it, "library_tab_COLLECTION") } })
            step("11") { host.await(activity) { tag(it, "library_empty_COLLECTION") } }
            host.awaitAbsent(activity) { tag(it, "library_empty_RSS_IDLE") }
            host.main { assertTrue("No dashed add card while empty",
                host.nodes(activity).none { host.description(it, host.context.getString(R.string.collection_create_title)) }) }
            click(step("12") { host.await(activity) { tag(it, "library_empty_action") } })
            step("13") { host.await(activity) { host.text(it, host.context.getString(R.string.collection_create_title)) } }
        } finally { activity.finish() }
    }
}
