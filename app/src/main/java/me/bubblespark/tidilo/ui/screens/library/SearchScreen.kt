package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

/**
 * Main Tab Search Screen
 * - Edge-to-Edge full screen layout matching Library & Queue
 * - Shared header with scrolling body search input & TagSelection shortcut
 * - Responds to floating navigation bar search action button to focus search input
 */
@Composable
fun SearchScreen(
    viewModel: LibraryViewModel,
    onNavigateToTagSelection: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val initializationFailure by viewModel.initializationFailure.collectAsStateWithLifecycle()
    val searchCount by viewModel.searchCount.collectAsStateWithLifecycle()
    val searchGeneration by viewModel.searchGeneration.collectAsStateWithLifecycle()
    val filteredBookmarks = searchGeneration.flow.collectAsLazyPagingItems()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val historyState = rememberLazyGridState()
    val resultListState = rememberLazyGridState()
    val resultGridState = rememberLazyGridState()
    var lastScrollQuery by rememberSaveable { mutableStateOf(searchQuery) }
    var peekBookmark by remember { mutableStateOf<BookmarkItem?>(null) }
    LaunchedEffect(searchQuery) {
        if (lastScrollQuery != searchQuery) {
            resultListState.requestScrollToItem(0)
            resultGridState.requestScrollToItem(0)
            lastScrollQuery = searchQuery
            peekBookmark = null
        }
    }
    BackHandler(enabled = peekBookmark != null) { peekBookmark = null }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }

    var inputAttachment by remember { mutableStateOf<Any?>(null) }
    val density = LocalDensity.current
    var searchInputHeight by remember { mutableStateOf(0.dp) }
    val currentQuery by rememberUpdatedState(searchQuery)
    val currentViewMode by rememberUpdatedState(viewMode)
    // Lazy input may be detached: bring its item back before requesting keyboard focus.
    LaunchedEffect(viewModel) {
        viewModel.searchFocusTrigger.collect {
            peekBookmark = null
            when {
                currentQuery.isBlank() -> historyState.scrollToItem(0)
                currentViewMode == LibraryViewMode.GRID -> resultGridState.scrollToItem(0)
                else -> resultListState.scrollToItem(0)
            }
            snapshotFlow { inputAttachment }.first { it != null }
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Pre-parse and memoize search tokens once per screen to eliminate scroll overhead
    val (wordKeywords, tagKeywords) = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            emptyList<String>() to emptyList<String>()
        } else {
            val tokens = searchQuery.replace("　", " ").split(" ").filter { it.isNotBlank() }
            val words = tokens.filter { !it.startsWith("#") }
            val tags = tokens.filter { it.startsWith("#") && it.length > 1 }
                .map { it.removePrefix("#").trim() }
            words to tags
        }
    }

    val headerHeight = HeaderDefaults.bodyTopPadding()

    val isNsfwVisible by AppConfig.isNsfwContentVisible.collectAsStateWithLifecycle()
    val activeQueryKey = remember(searchQuery, isNsfwVisible) {
        buildSearchQueryKey(searchQuery, isNsfwVisible)
    }
    val isQueryChanging = isSearchQueryChanging(searchQuery, activeQueryKey, searchGeneration.key)

    val searchInput: @Composable () -> Unit = {
        val attachment = remember { Any() }
        DisposableEffect(attachment) {
            onDispose { if (inputAttachment === attachment) inputAttachment = null }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    inputAttachment = attachment
                    searchInputHeight = with(density) { it.size.height.toDp() }
                }
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (hadFocus && !focusState.isFocused) {
                        if (searchQuery.isNotBlank()) {
                            viewModel.addSearchHistory(searchQuery)
                        }
                    }
                    hadFocus = focusState.isFocused
                },
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_content_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            if (searchQuery.isNotBlank()) {
                                viewModel.addSearchHistory(searchQuery)
                            }
                            viewModel.clearSearchQuery()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.common_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            viewModel.addSearchHistory(searchQuery)
                        }
                        onNavigateToTagSelection()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = stringResource(R.string.search_tag_selection_content_desc),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (searchQuery.isNotBlank()) {
                        viewModel.addSearchHistory(searchQuery)
                    }
                    focusManager.clearFocus()
                }
            ),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            ),
            singleLine = true
        )
    }
    val loadingResults = isBookmarkLoading(filteredBookmarks.loadState.refresh, filteredBookmarks.itemCount, isQueryChanging)
    val centerFeedback = initializationFailure != null || loadingResults || filteredBookmarks.loadState.refresh is LoadState.Error
    val feedback: (@Composable () -> Unit)? = when {
        initializationFailure != null -> ({ PagingFeedback(LoadState.Error(requireNotNull(initializationFailure)), viewModel::retryInitialization) })
        loadingResults -> ({ androidx.compose.material3.CircularProgressIndicator() })
        filteredBookmarks.loadState.refresh is LoadState.Error -> ({ PagingFeedback(filteredBookmarks.loadState.refresh, filteredBookmarks::retry) })
        filteredBookmarks.itemCount == 0 -> ({ EmptySearchResult(query = searchQuery) })
        else -> null
    }
    TidiloScreenScaffold(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null
        ) { focusManager.clearFocus() },
        hideHeaderOnScroll = true,
        headerVisible = peekBookmark == null,
        bottomContentPadding = HeaderDefaults.BottomNavigationPadding,
        header = { TidiloFrostedHeader(scrollState = when {
            searchQuery.isBlank() -> historyState
            viewMode == LibraryViewMode.GRID -> resultGridState
            else -> resultListState
        }, title = {
            Text(
                text = stringResource(R.string.search_header_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (searchQuery.isBlank()) {
                    stringResource(R.string.search_header_subtitle_keywords)
                } else {
                    stringResource(R.string.search_header_subtitle_results, searchCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }) },
        overlay = { BookmarkSummaryPeekOverlay(peekBookmark, headerHeight) { peekBookmark = null } }
    ) { contentPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val showingHistory = searchQuery.isBlank()
            val showingGrid = !showingHistory && viewMode == LibraryViewMode.GRID
            val itemSpacing = if (showingHistory) 4.dp else if (showingGrid) 10.dp else 16.dp
            // One lazy parent keeps the input node focused across blank/nonblank queries.
            // Separate states retain history, list, and grid scroll windows independently.
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (showingGrid) 2 else 1),
                state = when {
                    showingHistory -> historyState
                    showingGrid -> resultGridState
                    else -> resultListState
                },
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                item(key = "search_input", span = { GridItemSpan(maxLineSpan) }, contentType = "search_input") { searchInput() }
                if (showingHistory) {
                    item(key = "search_history_header", contentType = "history_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.search_recent_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            if (searchHistory.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearSearchHistory() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.common_clear),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    if (searchHistory.isEmpty()) {
                        item(key = "search_history_empty", contentType = "history_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.search_no_recent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        items(
                            items = searchHistory.take(10),
                            key = { "history:$it" },
                            contentType = { "history_item" }
                        ) { historyItem ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        viewModel.onSearchQueryChanged(historyItem)
                                        viewModel.addSearchHistory(historyItem)
                                        focusManager.clearFocus()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = historyItem,
                                    style = MaterialTheme.typography.bodyMedium,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { viewModel.removeSearchHistoryItem(historyItem) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.common_remove),
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (feedback != null) {
                    item(key = "search_feedback", span = { GridItemSpan(maxLineSpan) }, contentType = "search_feedback") {
                        if (centerFeedback) {
                            SearchResultFeedback(maxHeight, contentPadding, searchInputHeight, itemSpacing) { feedback() }
                        } else {
                            feedback()
                        }
                    }
                } else {
                    items(count = filteredBookmarks.itemCount,
                        key = filteredBookmarks.itemKey { "bookmark:${it.id}" },
                        contentType = { if (showingGrid) "bookmark_grid_card" else "bookmark_card" }
                    ) { index ->
                        val item = filteredBookmarks[index] ?: return@items
                        if (showingGrid) {
                            BookmarkGridCard(
                                bookmark = item,
                                onToggleLike = { viewModel.toggleLike(item.id) },
                                onLongClick = {
                                    peekBookmark = item
                                    viewModel.recordSummaryPeek(item.id)
                                },
                                onClick = {
                                    viewModel.addSearchHistory(searchQuery)
                                    onNavigateToDetail(item.id)
                                }
                            )
                        } else {
                            BookmarkItemCard(
                                bookmark = item,
                                onToggleLike = { viewModel.toggleLike(item.id) },
                                wordKeywords = wordKeywords,
                                tagKeywords = tagKeywords,
                                currentLanguage = currentLanguage,
                                onClick = {
                                    viewModel.addSearchHistory(searchQuery)
                                    onNavigateToDetail(item.id)
                                }
                            )
                        }
                    }
                }
            }
            boundaryFeedback(filteredBookmarks.loadState.prepend, filteredBookmarks.loadState.append)?.let { state ->
                PagingFeedback(state, filteredBookmarks::retry,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp))
            }
        }
    }
}
