package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canonical height for the single-line search input field in [SearchScreen], matching
 * the Material 3 [androidx.compose.material3.OutlinedTextField] standard minimum height (56.dp).
 * Using this constant avoids layout measurement phase-backwards writes into composition.
 */
internal val SEARCH_INPUT_HEIGHT: Dp = 56.dp

/**
 * Calculates the remaining viewport height available for centering feedback content
 * below the search input field.
 */
internal fun calculateRemainingFeedbackHeight(
    viewportHeight: Dp,
    contentPadding: PaddingValues,
    searchInputHeight: Dp = SEARCH_INPUT_HEIGHT,
    itemSpacing: Dp
): Dp {
    return (viewportHeight - contentPadding.calculateTopPadding() -
        contentPadding.calculateBottomPadding() - searchInputHeight - itemSpacing).coerceAtLeast(0.dp)
}

/** Search loading/retry feedback occupies the remaining body viewport. */
@Composable
internal fun SearchResultFeedback(
    viewportHeight: Dp,
    contentPadding: PaddingValues,
    searchInputHeight: Dp = SEARCH_INPUT_HEIGHT,
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val remainingHeight = calculateRemainingFeedbackHeight(
        viewportHeight = viewportHeight,
        contentPadding = contentPadding,
        searchInputHeight = searchInputHeight,
        itemSpacing = itemSpacing
    )
    Box(modifier.fillMaxWidth().heightIn(min = remainingHeight), contentAlignment = Alignment.Center) {
        content()
    }
}
