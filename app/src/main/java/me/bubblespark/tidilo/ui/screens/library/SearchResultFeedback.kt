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

/** Search loading/retry feedback occupies the remaining body viewport. */
@Composable
internal fun SearchResultFeedback(
    viewportHeight: Dp,
    contentPadding: PaddingValues,
    searchInputHeight: Dp,
    itemSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val remainingHeight = (viewportHeight - contentPadding.calculateTopPadding() -
        contentPadding.calculateBottomPadding() - searchInputHeight - itemSpacing).coerceAtLeast(0.dp)
    Box(modifier.fillMaxWidth().heightIn(min = remainingHeight), contentAlignment = Alignment.Center) {
        content()
    }
}
