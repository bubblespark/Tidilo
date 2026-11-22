package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.bubblespark.tidilo.data.BookmarkSearchResult
import me.bubblespark.tidilo.data.BookmarkSource
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TagKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private class FakeLibraryStore : LibraryStore {
        override val isInitialized = MutableStateFlow(true)
        override val initializationFailure = MutableStateFlow<Throwable?>(null)
        val domainGroups = MutableStateFlow<List<DomainGroupItem>>(emptyList())
        val rssGroups = MutableStateFlow<List<RssFeedGroup>>(emptyList())
        val collectionRows = MutableStateFlow<List<CollectionWithCount>>(emptyList())

        var pagedBookmarkCalls = 0
        var pagedCollectionCalls = 0
        val pagedSearchQueries = mutableListOf<String>()
        var filterModeWrites = 0
        var viewModeWrites = 0
        var historyWrites = 0
        var savedFilterMode: LibraryFilterMode? = null
        var savedHistory: List<String>? = null
        var storedHistory: List<String> = emptyList()
        var likeFailure: Throwable? = null
        var engagementFailure: Throwable? = null

        override fun retryInitialization() = Unit
        override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>> {
            pagedBookmarkCalls++
            return flowOf(PagingData.empty())
        }
        override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>> {
            pagedCollectionCalls++
            return flowOf(PagingData.empty())
        }
        override fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>> {
            pagedSearchQueries += query
            return flowOf(PagingData.empty())
        }
        override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) = flowOf(0)
        override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) = flowOf(0)
        override fun observeDomainGroups(showPrivate: Boolean) = domainGroups
        override fun observeRssFeedGroups(showPrivate: Boolean) = rssGroups
        override fun observeCollections(showPrivate: Boolean) = collectionRows
        override fun observeMembershipIds(bookmarkId: String) = flowOf(emptyList<String>())
        override fun observeBookmark(id: String) = flowOf<BookmarkItem?>(null)
        override fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind) = flowOf(emptyList<TagCount>())
        override fun observeEntityLibrary() = flowOf(emptyMap<String, String>())
        override suspend fun toggleLike(id: String) { likeFailure?.let { throw it } }
        override suspend fun recordBrowserOpen(id: String) { engagementFailure?.let { throw it } }
        override suspend fun recordDetailView(id: String) { engagementFailure?.let { throw it } }
        override suspend fun recordSummaryPeek(id: String) { engagementFailure?.let { throw it } }
        override suspend fun saveNote(id: String, note: String) = Unit
        override suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) = Unit
        override suspend fun applyAiProposal(id: String, description: String?, tag: Tag?) = true
        override suspend fun moveToTrash(id: String) = true
        override fun loadFilterMode() = LibraryFilterMode.NEWEST_FIRST
        override suspend fun saveFilterMode(mode: LibraryFilterMode) {
            filterModeWrites++
            savedFilterMode = mode
        }
        override fun loadViewMode() = LibraryViewMode.LIST
        override fun saveViewMode(mode: LibraryViewMode) { viewModeWrites++ }
        override fun loadIncludeRssInNewestFirst() = false
        override fun saveIncludeRssInNewestFirst(include: Boolean) = Unit
        override suspend fun loadSearchHistory() = storedHistory
        override suspend fun saveSearchHistory(history: List<String>) {
            historyWrites++
            savedHistory = history
        }
        override suspend fun clearSearchHistory() = Unit
    }

    private class Fixture(
        val model: LibraryViewModel,
        val store: FakeLibraryStore,
        val visible: MutableStateFlow<Boolean>,
        val events: MutableList<LibraryEvent>
    )

    private suspend fun TestScope.fixture(
        store: FakeLibraryStore = FakeLibraryStore(),
        block: suspend TestScope.(Fixture) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val modelScope = CoroutineScope(SupervisorJob() + dispatcher)
        val visible = MutableStateFlow(true)
        val model = LibraryViewModel(store, SavedStateHandle(), visible, modelScope, dispatcher)
        val events = mutableListOf<LibraryEvent>()
        backgroundScope.launch(dispatcher) { model.events.collect { events += it } }
        runCurrent()
        try {
            block(Fixture(model, store, visible, events))
        } finally {
            modelScope.cancel()
        }
    }

    private fun feed(id: String, title: String) = RssFeedGroup(RssFeedEntity(id = id, url = "https://$id.example/feed", title = title), 1)

    @Test fun debouncedQuerySwitchesGenerationAfter250ms() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.searchGeneration.collect {} }
            runCurrent()
            f.model.onSearchQueryChanged("kotlin")
            runCurrent()
            advanceTimeBy(249)
            runCurrent()
            assertEquals(buildSearchQueryKey("", true), f.model.searchGeneration.value.key)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(buildSearchQueryKey("kotlin", true), f.model.searchGeneration.value.key)
        }
    }

    @Test fun blankQueryCancelsPendingDebounce() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.searchGeneration.collect {} }
            runCurrent()
            f.model.onSearchQueryChanged("kotlin")
            runCurrent()
            advanceTimeBy(100)
            f.model.clearSearchQuery()
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertFalse("kotlin" in f.store.pagedSearchQueries)
            assertEquals(buildSearchQueryKey("", true), f.model.searchGeneration.value.key)
        }
    }

    @Test fun privateVisibilityChangeForcesNewGeneration() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.searchGeneration.collect {} }
            runCurrent()
            val before = f.store.pagedSearchQueries.size
            f.visible.value = false
            runCurrent()
            assertEquals(buildSearchQueryKey("", false), f.model.searchGeneration.value.key)
            assertEquals(before + 1, f.store.pagedSearchQueries.size)
        }
    }

    @Test fun updateQueryWithSelectedTagsKeepsWordsAndRecordsHistory() = runTest {
        fixture { f ->
            f.model.onSearchQueryChanged("compose　#AI old")
            f.model.updateQueryWithSelectedTags(setOf("Model"))
            runCurrent()
            assertEquals("compose old #Model", f.model.searchQuery.value)
            assertEquals("compose old #Model", f.model.searchHistory.value.first())
            assertEquals(listOf("compose old #Model"), f.store.savedHistory)
        }
    }

    @Test fun addSearchHistoryDoesNotRewriteUnchangedHistory() = runTest {
        fixture { f ->
            f.model.addSearchHistory("kotlin")
            runCurrent()
            val writes = f.store.historyWrites
            f.model.addSearchHistory("kotlin")
            runCurrent()
            assertEquals(writes, f.store.historyWrites)
        }
    }

    @Test fun searchHistoryLoadsFromStoreOnInit() = runTest {
        val store = FakeLibraryStore().apply { storedHistory = listOf("a", "b") }
        fixture(store) { f ->
            runCurrent()
            assertEquals(listOf("a", "b"), f.model.searchHistory.value)
        }
    }

    @Test fun domainGroupsSortAsciiFirstThenJapaneseCollator() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.domainGroups.collect {} }
            f.store.domainGroups.value = listOf(
                DomainGroupItem("あ.jp", "あいう", 1),
                DomainGroupItem("b.com", "Beta", 1),
                DomainGroupItem("a.com", "alpha", 1)
            )
            runCurrent()
            assertEquals(listOf("a.com", "b.com", "あ.jp"), f.model.domainGroups.value.map { it.domain })
            assertFalse(f.model.domainGroupsLoading.value)
        }
    }

    @Test fun domainGroupsResortOnStoreReemission() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.domainGroups.collect {} }
            f.store.domainGroups.value = listOf(
                DomainGroupItem("あ.jp", "あいう", 1),
                DomainGroupItem("b.com", "Beta", 1),
                DomainGroupItem("a.com", "alpha", 1)
            )
            runCurrent()
            f.store.domainGroups.value = f.store.domainGroups.value.map {
                if (it.domain == "a.com") it.copy(displayName = "zeta") else it
            }
            runCurrent()
            assertEquals(listOf("b.com", "a.com", "あ.jp"), f.model.domainGroups.value.map { it.domain })
        }
    }

    @Test fun rssFeedGroupsSortAsciiFirstThenCollatorThenId() = runTest {
        fixture { f ->
            backgroundScope.launch { f.model.rssFeedGroups.collect {} }
            f.store.rssGroups.value = listOf(feed("j", "日本語"), feed("b2", "Same"), feed("b1", "Same"), feed("a", "alpha"))
            runCurrent()
            assertEquals(listOf("a", "b1", "b2", "j"), f.model.rssFeedGroups.value.map { it.feed.id })
            assertFalse(f.model.rssFeedGroupsLoading.value)
        }
    }

    @Test fun toggleLikeFailureEmitsOneEvent() = runTest {
        fixture { f ->
            f.store.likeFailure = IOException("write")
            f.model.toggleLike("a")
            runCurrent()
            assertEquals(listOf<LibraryEvent>(LibraryEvent.SaveFailed), f.events)
        }
    }

    @Test fun toggleLikeCancellationEmitsNothing() = runTest {
        fixture { f ->
            f.store.likeFailure = CancellationException("cancelled")
            f.model.toggleLike("a")
            runCurrent()
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun recordBrowserOpenFailureEmitsNothing() = runTest {
        fixture { f ->
            f.store.engagementFailure = IOException("write")
            f.model.recordBrowserOpen("a")
            runCurrent()
            assertTrue(f.events.isEmpty())
        }
    }

    @Test fun setFilterModeResetsSubFiltersAndPersists() = runTest {
        fixture { f ->
            f.model.selectDomainFilter("a.com")
            f.model.selectRssFeedFilter(RssFeedEntity(id = "r", url = "https://r.example/feed", title = "R"))
            f.model.selectCollectionFilter("c1")
            f.model.setFilterMode(LibraryFilterMode.RSS)
            runCurrent()
            assertNull(f.model.selectedDomainFilter.value)
            assertNull(f.model.selectedRssFeedFilter.value)
            assertNull(f.model.selectedCollectionId.value)
            assertEquals(LibraryFilterMode.RSS, f.store.savedFilterMode)
        }
    }

    @Test fun setFilterModeSameModeKeepsSubFilters() = runTest {
        fixture { f ->
            f.model.selectDomainFilter("a.com")
            f.model.setFilterMode(f.model.filterMode.value)
            runCurrent()
            assertEquals("a.com", f.model.selectedDomainFilter.value)
            assertEquals(0, f.store.filterModeWrites)
        }
    }

    @Test fun setViewModeSkipsRedundantWrites() = runTest {
        fixture { f ->
            f.model.setViewMode(LibraryViewMode.LIST)
            f.model.setViewMode(LibraryViewMode.LIST)
            assertEquals(0, f.store.viewModeWrites)
            f.model.setViewMode(LibraryViewMode.GRID)
            assertEquals(1, f.store.viewModeWrites)
        }
    }

    @Test fun detailReturnReusesSelectedWindow() = runTest {
        fixture { f ->
            val first = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            f.model.retainLibraryPages(first)
            f.model.releaseLibraryPages(first)
            val returned = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            assertSame("Detail return must reuse the loaded Pager window", first, returned)
            assertTrue(first.isActive)
        }
    }

    @Test fun outgoingWindowLivesUntilExitDisposal() = runTest {
        fixture { f ->
            val first = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            f.model.retainLibraryPages(first)
            f.model.setFilterMode(LibraryFilterMode.RSS)
            assertTrue("Outgoing session must remain alive until exit disposal", first.isActive)
            f.model.releaseLibraryPages(first)
            assertFalse("Retired windows must release their cache", first.isActive)
            f.model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
            assertNotSame("Retired windows cannot be reused", first,
                f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true))
        }
    }

    @Test fun rapidReturnRetainsReusedWindow() = runTest {
        fixture { f ->
            val restored = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            f.model.retainLibraryPages(restored)
            f.model.setFilterMode(LibraryFilterMode.DOMAIN)
            f.model.selectDomainFilter("transition120.example")
            val intermediate = f.model.libraryPages(LibraryFilterMode.DOMAIN, "transition120.example", null, true)
            f.model.retainLibraryPages(intermediate)
            f.model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
            f.model.activateLibraryPages(restored)
            f.model.releaseLibraryPages(intermediate)
            assertFalse(intermediate.isActive)
            f.model.releaseLibraryPages(restored)
            assertSame("A-to-B-to-A must retain A across later detail navigation", restored,
                f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true))
        }
    }

    @Test fun replacementWhileAbsentCancelsRetainedWindow() = runTest {
        fixture { f ->
            val retained = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            f.model.retainLibraryPages(retained)
            f.model.releaseLibraryPages(retained)
            assertTrue(retained.isActive)
            f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, false)
            assertFalse("Replacement while the screen is absent must not leak the retained cache", retained.isActive)
        }
    }

    @Test fun pagesWaitForInitialization() = runTest {
        val store = FakeLibraryStore().apply { isInitialized.value = false }
        fixture(store) { f ->
            val pages = f.model.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, true)
            backgroundScope.launch { pages.flow.collect {} }
            runCurrent()
            assertEquals(0, store.pagedBookmarkCalls)
            store.isInitialized.value = true
            runCurrent()
            assertEquals(1, store.pagedBookmarkCalls)
        }
    }

    @Test fun collectionModeUsesCollectionPaging() = runTest {
        fixture { f ->
            val pages = f.model.libraryPages(LibraryFilterMode.COLLECTION, null, null, true, collectionId = "c1")
            backgroundScope.launch { pages.flow.collect {} }
            runCurrent()
            assertEquals(1, f.store.pagedCollectionCalls)
            assertEquals(0, f.store.pagedBookmarkCalls)
        }
    }
}
