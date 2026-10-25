package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.LoadState
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
    fun clearAndReenterQuerySequenceResetsKeyAndTriggersLoading() {
        var lastLoadedQueryKey: String? = null

        // 1. User enters "foo"
        var activeQueryKey = "foo"
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(activeQueryKey, lastLoadedQueryKey)
        var isQueryChanging = isSearchQueryChanging(activeQueryKey, lastLoadedQueryKey)
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = isQueryChanging))

        // "foo" finishes loading (5 items present)
        lastLoadedQueryKey = activeQueryKey
        isQueryChanging = isSearchQueryChanging(activeQueryKey, lastLoadedQueryKey)
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 5, isFilterChanging = isQueryChanging))

        // Same-key background Room invalidation while viewing "foo" does not trigger loading
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 5, isFilterChanging = isQueryChanging))

        // 2. User clears the query (becomes blank) -> production helper resets key to null
        activeQueryKey = ""
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(activeQueryKey, lastLoadedQueryKey)
        assertNull("Blank query must reset loaded key via production helper", lastLoadedQueryKey)

        // 3. User re-enters "foo" while prior unfiltered items (e.g. 10 items) are retained
        activeQueryKey = "foo"
        lastLoadedQueryKey = resolveLoadedQueryKeyOnInput(activeQueryKey, lastLoadedQueryKey)
        isQueryChanging = isSearchQueryChanging(activeQueryKey, lastLoadedQueryKey)
        assertTrue("Re-entering foo must be recognized as changing via production helper", isQueryChanging)
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 10, isFilterChanging = isQueryChanging))
    }

    @Test
    fun nsfwVisibilityChangeTriggersLoadingTransition() {
        var lastLoadedFilterKey: String? = null

        val filterMode = "NEWEST_FIRST"
        val domain = ""
        val feedId = ""

        // 1. Initial load with NSFW visible
        var isNsfw = true
        var activeKey = "$filterMode/$domain/$feedId/$isNsfw"
        var isFilterChanging = activeKey != lastLoadedFilterKey
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = isFilterChanging))

        // Settles with 10 bookmarks (including NSFW)
        lastLoadedFilterKey = activeKey
        isFilterChanging = activeKey != lastLoadedFilterKey
        assertFalse(isFilterChanging)
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 10, isFilterChanging = isFilterChanging))

        // 2. User turns off NSFW visibility
        isNsfw = false
        activeKey = "$filterMode/$domain/$feedId/$isNsfw"
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
        var activeKey = "$query/$isNsfw"

        lastLoadedKey = activeKey
        assertFalse(isSearchQueryChanging(activeKey, lastLoadedKey))

        // User toggles NSFW visibility
        isNsfw = false
        activeKey = "$query/$isNsfw"
        assertTrue("Toggling NSFW visibility in search must mark query as changing",
            isSearchQueryChanging(activeKey, lastLoadedKey))
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 5, isFilterChanging = isSearchQueryChanging(activeKey, lastLoadedKey)))
    }
}
