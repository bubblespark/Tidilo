package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.R

/** Displays a user-facing retry action without exposing database implementation details. */
@Composable
internal fun PagingFeedback(state: LoadState, retry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state) {
                is LoadState.Loading -> CircularProgressIndicator()
                is LoadState.Error -> {
                    Text(stringResource(R.string.library_load_failed))
                    TextButton(onClick = retry) { Text(stringResource(R.string.common_retry)) }
                }
                else -> Unit
            }
        }
    }
}

/** Both directions can load again after maxSize evicts pages; errors take priority. */
internal fun boundaryFeedback(prepend: LoadState, append: LoadState): LoadState? =
    listOf(prepend, append).firstOrNull { it is LoadState.Error }
        ?: listOf(prepend, append).firstOrNull { it is LoadState.Loading }

/** Domain cards use aggregate state, independently of any retained bookmark pages. */
internal fun bookmarkLoadState(state: LoadState, isDomainGrid: Boolean): LoadState =
    if (isDomainGrid) LoadState.NotLoading(false) else state

/**
 * Only treat refresh loading as initial/placeholder loading when there are no items rendered yet,
 * or when the active filter/query has changed and the new generation has not finished loading.
 * When items exist under the same filter, Paging retains the previous snapshot and diffs updates seamlessly in place.
 */
internal fun isBookmarkLoading(
    refreshState: LoadState,
    itemCount: Int,
    isFilterChanging: Boolean = false
): Boolean = refreshState is LoadState.Loading && (isFilterChanging || itemCount == 0)

/**
 * Resolves the updated loaded search key when input changes.
 * Resets to null on blank queries to guarantee that re-entering the same term triggers loading.
 */
internal fun resolveLoadedQueryKeyOnInput(query: String, currentLoadedKey: String?): String? =
    if (query.isBlank()) null else currentLoadedKey

/**
 * Determines whether the active query differs from the loaded search generation.
 * Always returns false for blank queries since the history list is displayed instead of search results.
 */
internal fun isSearchQueryChanging(activeQuery: String, loadedQueryKey: String?): Boolean =
    activeQuery.isNotBlank() && activeQuery.trim() != loadedQueryKey

/**
 * Builds the unique tracking key for a library filter generation, incorporating filter mode,
 * specific domain or RSS feed filter, and NSFW content visibility.
 */
internal fun buildLibraryFilterKey(
    filterMode: LibraryFilterMode,
    selectedDomainFilter: String?,
    selectedFeedId: String?,
    isNsfwVisible: Boolean
): String = "$filterMode/${selectedDomainFilter.orEmpty()}/${selectedFeedId.orEmpty()}/$isNsfwVisible"

/**
 * Builds the unique tracking key for a search query generation, incorporating normalized query
 * and NSFW content visibility. Blank queries yield an empty string key.
 */
internal fun buildSearchQueryKey(
    searchQuery: String,
    isNsfwVisible: Boolean
): String {
    val trimmed = searchQuery.trim()
    return if (trimmed.isBlank()) "" else "$trimmed/$isNsfwVisible"
}

/**
 * Associates an active Pager flow with its immutable generation tracking key.
 * UI components observe this generation state to collect matching PagingData
 * without handoff race conditions across filter transitions.
 */
data class PagingGeneration(
    val key: String,
    val flow: Flow<PagingData<BookmarkItem>>
)
