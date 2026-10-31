package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.rememberFeedIcon
import me.bubblespark.tidilo.data.db.RssFeedEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private sealed interface LibraryDisplayState {
    data object NewestFirst : LibraryDisplayState
    data object DomainGrid : LibraryDisplayState
    data class DomainDrillDown(val domain: String) : LibraryDisplayState
    data object RssFeedGrid : LibraryDisplayState
    data class RssFeedDrillDown(val feed: RssFeedEntity) : LibraryDisplayState
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    val selectedDomainFilter by viewModel.selectedDomainFilter.collectAsStateWithLifecycle()
    val selectedRssFeedFilter by viewModel.selectedRssFeedFilter.collectAsStateWithLifecycle()
    val rssFeedGroups by viewModel.rssFeedGroups.collectAsStateWithLifecycle()
    val rssFeedGroupsLoading by viewModel.rssFeedGroupsLoading.collectAsStateWithLifecycle()
    val selectedFeed = rssFeedGroups.firstOrNull { it.feed.id == selectedRssFeedFilter?.id }?.feed ?: selectedRssFeedFilter
    val selectedFeedIcon = if (selectedFeed != null) rememberFeedIcon(selectedFeed).value else null
    val domainGroups by viewModel.domainGroups.collectAsStateWithLifecycle()
    val initializationFailure by viewModel.initializationFailure.collectAsStateWithLifecycle()
    val initialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val domainGroupsLoading by viewModel.domainGroupsLoading.collectAsStateWithLifecycle()
    val isNsfwVisible by AppConfig.isNsfwContentVisible.collectAsStateWithLifecycle()
    val isLoading = !initialized ||
        (filterMode == LibraryFilterMode.DOMAIN && domainGroupsLoading) ||
        (filterMode == LibraryFilterMode.RSS && rssFeedGroupsLoading)
    val context = LocalContext.current
    val localConfig = LocalConfiguration.current
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    var isViewModeMenuExpanded by remember { mutableStateOf(false) }
    val selectedDomainColor = rememberDomainAccentColor(selectedDomainFilter)

    val domainGridState = rememberLazyGridState()
    val newestFirstListState = rememberLazyListState()
    val newestFirstGridState = rememberLazyGridState()
    val domainDetailListState = rememberLazyListState()
    val domainDetailGridState = rememberLazyGridState()
    val rssFeedGridState = rememberLazyGridState()
    val rssDetailListState = rememberLazyListState()
    val rssDetailGridState = rememberLazyGridState()

    LaunchedEffect(filterMode) {
        newestFirstListState.requestScrollToItem(0)
        newestFirstGridState.requestScrollToItem(0)
    }

    // Detail return restores the same domain; only an actual filter change resets its position.
    var lastScrollDomain by rememberSaveable { mutableStateOf<String?>(null) }
    var lastScrollFeed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFeed?.id) {
        if (selectedFeed != null && selectedFeed.id != lastScrollFeed) {
            rssDetailListState.requestScrollToItem(0)
            rssDetailGridState.requestScrollToItem(0)
        }
        lastScrollFeed = selectedFeed?.id
    }
    LaunchedEffect(selectedDomainFilter) {
        if (selectedDomainFilter != null && selectedDomainFilter != lastScrollDomain) {
            // The other layout may be detached; request both positions without awaiting layout.
            domainDetailListState.requestScrollToItem(0)
            domainDetailGridState.requestScrollToItem(0)
        }
        lastScrollDomain = selectedDomainFilter
    }

    val displayState = remember(filterMode, selectedDomainFilter, selectedFeed?.id) {
        when {
            filterMode == LibraryFilterMode.RSS && selectedFeed != null ->
                LibraryDisplayState.RssFeedDrillDown(selectedFeed)
            filterMode == LibraryFilterMode.RSS -> LibraryDisplayState.RssFeedGrid
            filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                LibraryDisplayState.DomainDrillDown(selectedDomainFilter!!)
            filterMode == LibraryFilterMode.DOMAIN ->
                LibraryDisplayState.DomainGrid
            else ->
                LibraryDisplayState.NewestFirst
        }
    }

    val activeScrollState: ScrollableState = when (displayState) {
        LibraryDisplayState.NewestFirst -> if (viewMode == LibraryViewMode.LIST) newestFirstListState else newestFirstGridState
        LibraryDisplayState.DomainGrid -> domainGridState
        is LibraryDisplayState.DomainDrillDown -> if (viewMode == LibraryViewMode.LIST) domainDetailListState else domainDetailGridState
        LibraryDisplayState.RssFeedGrid -> rssFeedGridState
        is LibraryDisplayState.RssFeedDrillDown -> if (viewMode == LibraryViewMode.LIST) rssDetailListState else rssDetailGridState
    }
    val headerScope = key(activeScrollState) { rememberCoroutineScope() }

    val observedCount = key(displayState, isNsfwVisible) {
        val initialCount = when (displayState) {
            LibraryDisplayState.DomainGrid, LibraryDisplayState.RssFeedGrid -> 0
            is LibraryDisplayState.DomainDrillDown -> domainGroups.firstOrNull { it.domain == selectedDomainFilter }?.count
            is LibraryDisplayState.RssFeedDrillDown -> rssFeedGroups.firstOrNull { it.feed.id == selectedFeed?.id }?.count
            LibraryDisplayState.NewestFirst -> null
        }
        val count by remember(displayState) {
            if (displayState == LibraryDisplayState.DomainGrid || displayState == LibraryDisplayState.RssFeedGrid) flowOf(0)
            else viewModel.libraryCount(
                filterMode, selectedDomainFilter.takeIf { filterMode == LibraryFilterMode.DOMAIN },
                selectedFeed?.id, isNsfwVisible
            )
        }.collectAsStateWithLifecycle(initialValue = initialCount)
        count
    }
    val libraryCount = observedCount ?: 0

    val headerHeight = HeaderDefaults.bodyTopPadding()

    var peekBookmark by remember { mutableStateOf<BookmarkItem?>(null) }
    LaunchedEffect(filterMode, selectedDomainFilter, selectedFeed?.id) { peekBookmark = null }

    // Dismiss summary peek on system back gesture
    BackHandler(enabled = peekBookmark != null) {
        peekBookmark = null
    }

    // Restore to domain grid from domain drilldown on system back gesture (disabled during peek)
    val isDrillDown = (filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null) ||
        (filterMode == LibraryFilterMode.RSS && selectedFeed != null)
    fun clearCollectionFilter() {
        if (filterMode == LibraryFilterMode.RSS) viewModel.selectRssFeedFilter(null) else viewModel.clearDomainFilter()
    }
    if (isDrillDown) {
        BackHandler(enabled = peekBookmark == null) {
            clearCollectionFilter()
        }
    }

    TidiloScreenScaffold(
        modifier = modifier,
        hideHeaderOnScroll = true,
        bottomContentPadding = HeaderDefaults.BottomNavigationPadding,
        headerVisible = peekBookmark == null,
        overlay = { BookmarkSummaryPeekOverlay(peekBookmark, headerHeight) { peekBookmark = null } },
        // Expressive Header (slides out/in from top screen edge linked with scrolling, hidden during peek)
        header = {
            TidiloFrostedHeader(
                scrollState = activeScrollState,
                onTitleClick = {
                    headerScope.launch {
                        when (val state = activeScrollState) {
                            is LazyListState -> if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) {
                                state.animateScrollToItem(0)
                            }
                            is LazyGridState -> if (state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) {
                                state.animateScrollToItem(0)
                            }
                        }
                    }
                },
                title = {
                    val defaultTitle = stringResource(R.string.library_header_title)
                    val headerTitle = when {
                        filterMode == LibraryFilterMode.RSS -> selectedFeed?.title ?: stringResource(R.string.rss_title)
                        filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                            DomainPolicyManager.getDisplayName(selectedDomainFilter)
                        else -> defaultTitle
                    }
                    val headerSubtitle = when {
                        isLoading || observedCount == null ->
                            stringResource(R.string.library_subtitle_loading)
                        filterMode == LibraryFilterMode.RSS && selectedFeed != null ->
                            pluralStringResource(R.plurals.rss_subtitle_bookmarks, libraryCount, libraryCount)
                        filterMode == LibraryFilterMode.RSS ->
                            pluralStringResource(R.plurals.rss_subtitle_feeds, rssFeedGroups.size, rssFeedGroups.size)
                        filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                            stringResource(R.string.library_subtitle_domain_bookmarks, libraryCount)
                        filterMode == LibraryFilterMode.DOMAIN ->
                            stringResource(R.string.library_subtitle_domains, domainGroups.size)
                        else ->
                            stringResource(R.string.library_subtitle_tagged_bookmarks, libraryCount)
                    }

                    AnimatedContent(
                        targetState = headerTitle to isDrillDown,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                        },
                        label = "header_title"
                    ) { (title, drillDown) ->
                        val titleLength = title.codePointCount(0, title.length)
                        Text(
                            text = title,
                            modifier = if (drillDown) Modifier.basicMarquee(initialDelayMillis = 2000)
                                else Modifier,
                            style = when {
                                !drillDown || titleLength <= 6 -> MaterialTheme.typography.headlineMedium
                                titleLength <= 9 -> MaterialTheme.typography.headlineSmall.copy(
                                    fontSize = 23.sp, lineHeight = 28.sp
                                )
                                titleLength <= 13 -> MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp, lineHeight = 25.sp
                                )
                                else -> MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 17.sp, lineHeight = 22.sp
                                )
                            },
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = if (drillDown) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                    }
                    val subtitlePresentation = LibrarySubtitle(
                        headerSubtitle,
                        selectedFeedIcon?.color ?: selectedDomainColor ?: MaterialTheme.colorScheme.primary
                    )
                    AnimatedContent(
                        targetState = subtitlePresentation,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                        },
                        label = "header_subtitle"
                    ) { subtitle ->
                        Text(
                            text = subtitle.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = subtitle.color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    AnimatedVisibility(
                        visible = isDrillDown,
                        enter = fadeIn(animationSpec = tween(200)) + expandHorizontally(),
                        exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally()
                    ) {
                        IconButton(
                            onClick = { if (peekBookmark == null) clearCollectionFilter() else peekBookmark = null },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(if (filterMode == LibraryFilterMode.RSS) R.string.rss_back_to_feeds else R.string.library_back_to_domains),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                actions = {
                    // View mode toggle menu button
                    Box {
                        IconButton(
                            onClick = { if (peekBookmark == null) isViewModeMenuExpanded = true else peekBookmark = null },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_dashboard_2_gear),
                                contentDescription = stringResource(R.string.library_view_mode_menu_desc),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        val menuWidth = 200.dp
                        DropdownMenu(
                            expanded = isViewModeMenuExpanded,
                            onDismissRequest = { isViewModeMenuExpanded = false },
                            offset = DpOffset(x = 96.dp - menuWidth, y = 0.dp),
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 6.dp,
                            modifier = Modifier.width(menuWidth)
                        ) {
                            CompositionLocalProvider(
                                LocalContext provides context,
                                LocalConfiguration provides localConfig
                            ) {
                                LibraryViewMode.entries.forEach { mode ->
                                    val isSelected = mode == viewMode
                                    DropdownMenuItem(
                                        modifier = Modifier.semantics { this.selected = isSelected },
                                        text = {
                                            Text(
                                                text = stringResource(mode.labelRes),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        leadingIcon = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.size(18.dp))
                                                }
                                                Icon(
                                                    imageVector = mode.icon,
                                                    contentDescription = null,
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setViewMode(mode)
                                            isViewModeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Settings button
                    IconButton(
                        onClick = { if (peekBookmark == null) onNavigateToSettings() else peekBookmark = null },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.title_settings),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        if (initializationFailure != null) {
            Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                PagingFeedback(LoadState.Error(initializationFailure!!), viewModel::retryInitialization)
            }
        } else {
            key(isNsfwVisible) {
                AnimatedContent(
                    targetState = displayState,
                    transitionSpec = {
                        // Filter mode change, Domain Grid <-> DrillDown, or refresh: smooth Crossfade
                        fadeIn(animationSpec = tween(220, easing = EaseInOutCubic)) togetherWith
                                fadeOut(animationSpec = tween(180, easing = EaseInOutCubic))
                    },
                    label = "library_display_state",
                    modifier = Modifier.fillMaxSize()
                ) { state ->
                    // Keep the selected window across details and release outgoing windows after exit.
                    val collectionGrid = state == LibraryDisplayState.DomainGrid || state == LibraryDisplayState.RssFeedGrid
                    val session = remember(state) {
                        if (collectionGrid) null
                        else viewModel.libraryPages(
                            when (state) {
                                is LibraryDisplayState.RssFeedDrillDown -> LibraryFilterMode.RSS
                                LibraryDisplayState.NewestFirst -> LibraryFilterMode.NEWEST_FIRST
                                else -> LibraryFilterMode.DOMAIN
                            },
                            (state as? LibraryDisplayState.DomainDrillDown)?.domain,
                            (state as? LibraryDisplayState.RssFeedDrillDown)?.feed?.id,
                            isNsfwVisible
                        )
                    }
                    DisposableEffect(session) {
                        session?.let { it.users++ }
                        onDispose { session?.let(viewModel::releaseLibraryPages) }
                    }
                    SideEffect {
                        if (state == displayState) session?.let(viewModel::activateLibraryPages)
                    }
                    val pages = session?.flow ?: remember { flowOf(PagingData.empty<BookmarkItem>()) }
                    val bookmarks = pages.collectAsLazyPagingItems()
                    val refreshState = bookmarkLoadState(bookmarks.loadState.refresh, collectionGrid)
                    val loading = !initialized || isBookmarkLoading(refreshState, bookmarks.itemCount) ||
                        (state == LibraryDisplayState.DomainGrid && domainGroupsLoading) ||
                        (state == LibraryDisplayState.RssFeedGrid && rssFeedGroupsLoading)
                    if (loading) {
                        SkeletonBookmarkList(Modifier.fillMaxSize().padding(top = headerHeight, start = 20.dp, end = 20.dp))
                    } else if (refreshState is LoadState.Error) {
                        Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                            PagingFeedback(refreshState, bookmarks::retry)
                        }
                    } else when (state) {
                        is LibraryDisplayState.DomainGrid, is LibraryDisplayState.RssFeedGrid -> {
                            val rss = state is LibraryDisplayState.RssFeedGrid
                            if (if (rss) rssFeedGroups.isEmpty() else domainGroups.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                                ) {
                                    EmptyLibraryMessage()
                                }
                            } else {
                                LazyVerticalGrid(
                                    state = if (rss) rssFeedGridState else domainGridState,
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = contentPadding,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (rss) {
                                        items(items = rssFeedGroups, key = { it.feed.id }, contentType = { "rss_feed_card" }) { group ->
                                            RssFeedCard(group, onClick = { viewModel.selectRssFeedFilter(group.feed) })
                                        }
                                    } else items(
                                        items = domainGroups,
                                        key = { it.domain },
                                        contentType = { "domain_card" }
                                    ) { group ->
                                        DomainCard(
                                            item = group,
                                            onClick = {
                                                viewModel.selectDomainFilter(group.domain)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        is LibraryDisplayState.DomainDrillDown, is LibraryDisplayState.RssFeedDrillDown -> {
                            val feed = (state as? LibraryDisplayState.RssFeedDrillDown)?.feed?.let { snapshot ->
                                rssFeedGroups.firstOrNull { it.feed.id == snapshot.id }?.feed ?: snapshot
                            }
                            val feedIcon = if (feed != null) rememberFeedIcon(feed).value else null
                            val currentDomain = (state as? LibraryDisplayState.DomainDrillDown)?.domain
                                ?: feed?.let(DomainIconManager::feedPublisherDomain).orEmpty()
                            val domainColor = feedIcon?.color ?: rememberDomainAccentColor(currentDomain)
                                ?: DomainIconManager.getCachedDominantColor(currentDomain)
                                ?: DomainIconManager.generateDomainAccentColor(currentDomain)

                            val domainBookmarks = bookmarks

                            CompositionLocalProvider(LocalCardAccentColor provides domainColor) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .drawWithCache {
                                            // Radiant ambient beam from top-right corner spreading across ~2/3 of the screen towards bottom-left
                                            val ambientBeam = Brush.linearGradient(
                                                colorStops = arrayOf(
                                                    0.00f to domainColor.copy(alpha = 0.32f),
                                                    0.22f to domainColor.copy(alpha = 0.22f),
                                                    0.45f to domainColor.copy(alpha = 0.13f),
                                                    0.68f to domainColor.copy(alpha = 0.05f),
                                                    0.84f to domainColor.copy(alpha = 0.015f),
                                                    1.00f to Color.Transparent
                                                ),
                                                start = Offset(size.width * 1.05f, -size.height * 0.02f),
                                                end = Offset(-size.width * 0.15f, size.height * 0.90f)
                                            )

                                            // Deep spherical glow in top-right shoulder spreading across ~2/3 of screen
                                            val cornerGlow = Brush.radialGradient(
                                                colorStops = arrayOf(
                                                    0.00f to domainColor.copy(alpha = 0.22f),
                                                    0.30f to domainColor.copy(alpha = 0.13f),
                                                    0.60f to domainColor.copy(alpha = 0.05f),
                                                    0.82f to domainColor.copy(alpha = 0.012f),
                                                    1.00f to Color.Transparent
                                                ),
                                                center = Offset(size.width, 0f),
                                                radius = size.height * 0.72f
                                            )
                                            onDrawBehind {
                                                drawRect(brush = ambientBeam)
                                                drawRect(brush = cornerGlow)
                                            }
                                        }
                                ) {
                                    if (domainBookmarks.itemCount == 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                                        ) {
                                            EmptyLibraryMessage()
                                        }
                                    } else {
                                        AnimatedContent(
                                            targetState = viewMode,
                                            transitionSpec = {
                                                fadeIn(animationSpec = tween(220, easing = EaseInOutCubic)) togetherWith
                                                        fadeOut(animationSpec = tween(180, easing = EaseInOutCubic))
                                            },
                                            label = "domain_view_mode_transition"
                                        ) { mode ->
                                            when (mode) {
                                                LibraryViewMode.LIST -> {
                                                    LazyColumn(
                                                        state = if (feed != null) rssDetailListState else domainDetailListState,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentPadding = contentPadding,
                                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                                    ) {
                                                        items(
                                                        count = domainBookmarks.itemCount,
                                                        key = domainBookmarks.itemKey { it.id },
                                                        contentType = { "bookmark_card" }
                                                    ) { index ->
                                                        val item = domainBookmarks[index] ?: return@items
                                                            BookmarkItemCard(
                                                                onBrowserOpened = { viewModel.recordBrowserOpen(item.id) },
                                                                bookmark = item,
                                                                wordKeywords = emptyList(),
                                                                tagKeywords = emptyList(),
                                                                currentLanguage = currentLanguage,
                                                                onClick = { onNavigateToDetail(item.id) }
                                                            )
                                                        }
                                                    }
                                                }
                                                LibraryViewMode.GRID -> {
                                                    LazyVerticalGrid(
                                                        state = if (feed != null) rssDetailGridState else domainDetailGridState,
                                                        columns = GridCells.Fixed(2),
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentPadding = contentPadding,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        items(
                                                        count = domainBookmarks.itemCount,
                                                        key = domainBookmarks.itemKey { it.id },
                                                        contentType = { "bookmark_grid_card" }
                                                    ) { index ->
                                                        val item = domainBookmarks[index] ?: return@items
                                                            BookmarkGridCard(
                                                                onBrowserOpened = { viewModel.recordBrowserOpen(item.id) },
                                                                bookmark = item,
                                                                onClick = { onNavigateToDetail(item.id) },
                                                                onLongClick = { peekBookmark = item; viewModel.recordSummaryPeek(item.id) }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is LibraryDisplayState.NewestFirst -> {
                            if (bookmarks.itemCount == 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                                ) {
                                    EmptyLibraryMessage()
                                }
                            } else {
                                AnimatedContent(
                                    targetState = viewMode,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(220, easing = EaseInOutCubic)) togetherWith
                                                fadeOut(animationSpec = tween(180, easing = EaseInOutCubic))
                                    },
                                    label = "newest_first_view_mode_transition"
                                ) { mode ->
                                    when (mode) {
                                        LibraryViewMode.LIST -> {
                                            LazyColumn(
                                                state = newestFirstListState,
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = contentPadding,
                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                items(
                                                    count = bookmarks.itemCount,
                                                    key = bookmarks.itemKey { it.id },
                                                    contentType = { "bookmark_card" }
                                                ) { index ->
                                                    val item = bookmarks[index] ?: return@items
                                                    BookmarkItemCard(
                                                                onBrowserOpened = { viewModel.recordBrowserOpen(item.id) },
                                                        bookmark = item,
                                                        wordKeywords = emptyList(),
                                                        tagKeywords = emptyList(),
                                                        currentLanguage = currentLanguage,
                                                        onClick = { onNavigateToDetail(item.id) }
                                                    )
                                                }
                                            }
                                        }
                                        LibraryViewMode.GRID -> {
                                            LazyVerticalGrid(
                                                state = newestFirstGridState,
                                                columns = GridCells.Fixed(2),
                                                modifier = Modifier.fillMaxSize(),
                                                contentPadding = contentPadding,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                items(
                                                    count = bookmarks.itemCount,
                                                    key = bookmarks.itemKey { it.id },
                                                    contentType = { "bookmark_grid_card" }
                                                ) { index ->
                                                    val item = bookmarks[index] ?: return@items
                                                    BookmarkGridCard(
                                                                onBrowserOpened = { viewModel.recordBrowserOpen(item.id) },
                                                        bookmark = item,
                                                        onClick = { onNavigateToDetail(item.id) },
                                                        onLongClick = { peekBookmark = item; viewModel.recordSummaryPeek(item.id) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!collectionGrid) {
                        boundaryFeedback(bookmarks.loadState.prepend, bookmarks.loadState.append)?.let { feedback ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                PagingFeedback(feedback, bookmarks::retry, Modifier.padding(bottom = 110.dp))
                            }
                        }
                    }
                }
            }
        }


    }
}

@Composable
private fun EmptyLibraryMessage() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.library_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.library_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}


/** Shared borderless summary preview for library and search grid cards. */
@Composable
internal fun BookmarkSummaryPeekOverlay(bookmark: BookmarkItem?, headerHeight: androidx.compose.ui.unit.Dp, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = bookmark != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
        modifier = Modifier.fillMaxSize()
    ) {
        val currentPeek = bookmark

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss() }
                )
        ) {
            if (currentPeek != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight)
                        .padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState())
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onDismiss() }
                        )
                ) {
                    // Badge: AI Summary
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.detail_action_ai_summary),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = currentPeek.description.ifBlank {
                            stringResource(R.string.ai_review_no_summary)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 24.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(140.dp))
                }
            }
        }
    }
}




/** Text and accent must remain paired while an outgoing subtitle fades away. */
internal data class LibrarySubtitle(val text: String, val color: Color)

@Composable
private fun rememberDomainAccentColor(domain: String?): Color? = key(domain) {
    val context = LocalContext.current
    val updates by remember(domain) { DomainIconManager.observeIconUpdates(domain) }
        .collectAsStateWithLifecycle(initialValue = 0L)
    val color by produceState(
        initialValue = domain?.let {
            DomainIconManager.getCachedDominantColor(it) ?: DomainIconManager.generateDomainAccentColor(it)
        }, key1 = updates
    ) {
        if (domain != null) value = withContext(Dispatchers.IO) {
            DomainIconManager.getDominantColor(context, domain)
        }
    }
    color
}
