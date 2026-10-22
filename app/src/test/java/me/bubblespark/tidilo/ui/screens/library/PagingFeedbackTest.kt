package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.LoadState
import org.junit.Assert.*
import org.junit.Test

class PagingFeedbackTest {
    @Test
    fun reversePagingFailureRemainsRetryableEvenWhenForwardPagingIsLoading() {
        val failure = LoadState.Error(IllegalStateException("Earlier page failed"))
        assertSame(failure, boundaryFeedback(failure, LoadState.NotLoading(false)))
        assertSame(failure, boundaryFeedback(failure, LoadState.Loading))
        assertSame(failure, boundaryFeedback(LoadState.Loading, failure))
        assertNull(boundaryFeedback(LoadState.NotLoading(false), LoadState.NotLoading(true)))
    }
}
