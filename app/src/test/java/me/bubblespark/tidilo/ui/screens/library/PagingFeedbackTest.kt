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
        var isQueryChanging = activeQueryKey.isNotBlank() && activeQueryKey != lastLoadedQueryKey
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 0, isFilterChanging = isQueryChanging))

        // "foo" finishes loading (5 items present)
        lastLoadedQueryKey = activeQueryKey
        isQueryChanging = activeQueryKey.isNotBlank() && activeQueryKey != lastLoadedQueryKey
        assertFalse(isBookmarkLoading(LoadState.NotLoading(false), itemCount = 5, isFilterChanging = isQueryChanging))

        // Same-key background Room invalidation while viewing "foo" does not trigger loading
        assertFalse(isBookmarkLoading(LoadState.Loading, itemCount = 5, isFilterChanging = isQueryChanging))

        // 2. User clears the query (becomes blank)
        activeQueryKey = ""
        if (activeQueryKey.isBlank()) {
            lastLoadedQueryKey = null
        }

        // 3. User re-enters "foo" while prior unfiltered items (e.g. 10 items) are retained
        activeQueryKey = "foo"
        isQueryChanging = activeQueryKey.isNotBlank() && activeQueryKey != lastLoadedQueryKey
        assertTrue(isQueryChanging)
        assertTrue(isBookmarkLoading(LoadState.Loading, itemCount = 10, isFilterChanging = isQueryChanging))
    }
}
