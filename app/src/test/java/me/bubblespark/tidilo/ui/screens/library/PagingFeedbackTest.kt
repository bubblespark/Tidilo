package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.LoadState
import androidx.paging.PagingData
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class PagingFeedbackTest {
    @Test
    fun domainGridIgnoresEveryBookmarkPagingDirection() {
        for (state in listOf(LoadState.Loading, LoadState.Error(IllegalStateException("Timeline failed")))) {
            assertTrue(bookmarkLoadState(state, true) is LoadState.NotLoading)
            assertNull(boundaryFeedback(bookmarkLoadState(state, true), bookmarkLoadState(state, true)))
            assertSame(state, bookmarkLoadState(state, false))
        }
    }

    @Test
    fun reversePagingFailureRemainsRetryableEvenWhenForwardPagingIsLoading() {
        val failure = LoadState.Error(IllegalStateException("Earlier page failed"))
        assertSame(failure, boundaryFeedback(failure, LoadState.NotLoading(false)))
        assertSame(failure, boundaryFeedback(failure, LoadState.Loading))
        assertSame(failure, boundaryFeedback(LoadState.Loading, failure))
        assertNull(boundaryFeedback(LoadState.NotLoading(false), LoadState.NotLoading(true)))
    }

    @Test
    fun bookmarkLoadingTriggersOnlyWhenPagingRefreshesWithEmptyItems() {
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0))
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 1))
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 25))
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 0))
        assertFalse(isBookmarkLoading(LoadState.NotLoading(true), itemCount = 0))
        assertFalse(isBookmarkLoading(LoadState.Error(IllegalStateException("Failed")), itemCount = 0))
    }

    @Test
    fun bookmarkLoadingTriggersWhenFilterIsChangingRegardlessOfRetainedItems() {
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 15, isFilterChanging = true))
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = true))
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 15, isFilterChanging = false))
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 15, isFilterChanging = true))
        assertFalse(isBookmarkLoading(LoadState.Error(IllegalStateException("Failed")), itemCount = 15, isFilterChanging = true))
    }

    @Test
    fun buildLibraryFilterKeyIncludesAllComponents() {
        val key1 = buildLibraryFilterKey(LibraryFilterMode.NEWEST_FIRST, "example.com", "feed1", true)
        val key2 = buildLibraryFilterKey(LibraryFilterMode.NEWEST_FIRST, "example.com", "feed1", false)
        val key3 = buildLibraryFilterKey(LibraryFilterMode.DOMAIN, "example.com", "feed1", true)
        val key4 = buildLibraryFilterKey(LibraryFilterMode.NEWEST_FIRST, "other.com", "feed1", true)
        val key5 = buildLibraryFilterKey(LibraryFilterMode.NEWEST_FIRST, "example.com", "feed2", true)

        assertNotEquals("NSFW flag must distinguish keys", key1, key2)
        assertNotEquals("Filter mode must distinguish keys", key1, key3)
        assertNotEquals("Domain filter must distinguish keys", key1, key4)
        assertNotEquals("Feed ID must distinguish keys", key1, key5)
    }

    @Test
    fun buildSearchQueryKeyNormalizesAndDifferentiatesNsfw() {
        val key1 = buildSearchQueryKey("  android  ", true)
        val key2 = buildSearchQueryKey("android", true)
        val key3 = buildSearchQueryKey("android", false)
        val keyBlank1 = buildSearchQueryKey("   ", true)
        val keyBlank2 = buildSearchQueryKey("", false)

        assertEquals("Whitespace must be trimmed", key1, key2)
        assertNotEquals("NSFW flag must distinguish keys", key2, key3)
        assertEquals("/true", keyBlank1)
        assertEquals("/false", keyBlank2)
        assertNotEquals("NSFW flag must distinguish blank keys", keyBlank1, keyBlank2)
    }

    @Test
    fun clearAndReenterQuerySequenceResetsKeyAndTriggersLoading() {
        var lastLoadedQueryKey: String? = null

        // 1. User enters "foo"
        val query1 = "foo"
        var activeQueryKey = buildSearchQueryKey(query1, isNsfwVisible = true)
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(query1, lastLoadedQueryKey)
        var isQueryChanging = isSearchQueryChanging(query1, activeQueryKey, lastLoadedQueryKey)
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = isQueryChanging))

        // "foo" finishes loading (5 items present)
        lastLoadedQueryKey = activeQueryKey
        isQueryChanging = isSearchQueryChanging(query1, activeQueryKey, lastLoadedQueryKey)
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 5, isFilterChanging = isQueryChanging))

        // Same-key background Room invalidation while viewing "foo" does not trigger loading
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 5, isFilterChanging = isQueryChanging))

        // 2. User clears the query (becomes blank) -> production helper resets key to null
        val query2 = ""
        activeQueryKey = buildSearchQueryKey(query2, isNsfwVisible = true)
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(query2, lastLoadedQueryKey)
        assertNull("Blank query must reset loaded key via production helper", lastLoadedQueryKey)

        // 3. User re-enters "foo" while prior unfiltered items (e.g. 10 items) are retained
        val query3 = "foo"
        activeQueryKey = buildSearchQueryKey(query3, isNsfwVisible = true)
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(query3, lastLoadedQueryKey)
        isQueryChanging = isSearchQueryChanging(query3, activeQueryKey, lastLoadedQueryKey)
        assertTrue("Re-entering foo must be recognized as changing via production helper", isQueryChanging)
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 10, isFilterChanging = isQueryChanging))
    }

    @Test
    fun nsfwVisibilityChangeTriggersLoadingTransition() {
        var lastLoadedFilterKey: String? = null

        val filterMode = LibraryFilterMode.NEWEST_FIRST
        val domain = null
        val feedId = null

        // 1. Initial load with NSFW visible
        var isNsfw = true
        var activeKey = buildLibraryFilterKey(filterMode, domain, feedId, isNsfw)
        var isFilterChanging = activeKey != lastLoadedFilterKey
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = isFilterChanging))

        // Settles with 10 bookmarks (including NSFW)
        lastLoadedFilterKey = activeKey
        isFilterChanging = activeKey != lastLoadedFilterKey
        assertFalse(isFilterChanging)
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 10, isFilterChanging = isFilterChanging))

        // 2. User turns off NSFW visibility
        isNsfw = false
        activeKey = buildLibraryFilterKey(filterMode, domain, feedId, isNsfw)
        isFilterChanging = activeKey != lastLoadedFilterKey
        assertTrue("Turning off NSFW must mark filter as changing", isFilterChanging)
        assertTrue("Must show loading to prevent showing NSFW items during transition",
            isBookmarkLoading(LoadState.Loading, itemCount = 10, isFilterChanging = isFilterChanging))
    }

    @Test
    fun nsfwVisibilityChangeInSearchTriggersQueryChanging() {
        var lastLoadedKey: String? = null

        val query = "android"
        var isNsfw = true
        var activeKey = buildSearchQueryKey(query, isNsfw)

        lastLoadedKey = activeKey
        assertFalse(isSearchQueryChanging(query, activeKey, lastLoadedKey))

        // User toggles NSFW visibility
        isNsfw = false
        activeKey = buildSearchQueryKey(query, isNsfw)
        assertTrue("Toggling NSFW visibility in search must mark query as changing",
            isSearchQueryChanging(query, activeKey, lastLoadedKey))
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 5, isFilterChanging = isSearchQueryChanging(query, activeKey, lastLoadedKey)))
    }

    @Test
    fun blankSearchQueryNeverMarksQueryChanging() {
        val blankQuery = "   "
        val activeKey = buildSearchQueryKey(blankQuery, isNsfwVisible = true)
        val loadedKey = "previous_key"

        assertFalse(
            "Blank query must never be marked as changing since search history is shown",
            isSearchQueryChanging(blankQuery, activeKey, loadedKey)
        )
    }

    @Test
    fun pagingGenerationHoldsKeyAndFlow() {
        val flow = flowOf(PagingData.empty<BookmarkItem>())
        val gen = PagingGeneration("test-key", flow)
        assertEquals("test-key", gen.key)
        assertSame(flow, gen.flow)
    }
}
