package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.theme.themeCorner
import me.bubblespark.tidilo.ui.theme.modernRaisedTextStyle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.LazyPagingItems
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Check
import me.bubblespark.tidilo.ui.components.TidiloDropdownMenu
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.ChromeBlurBackground
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.components.RememberAmbientLighting
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import androidx.compose.ui.semantics.clearAndSetSemantics
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.rememberDomainVisual
import me.bubblespark.tidilo.ui.components.rememberFeedVisual
import me.bubblespark.tidilo.data.db.RssFeedEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private sealed interface LibraryDisplayState {
    data object NewestFirst : LibraryDisplayState
    data object DomainGrid : LibraryDisplayState
    data class DomainDrillDown(val domain: String) : LibraryDisplayState
    data object RssFeedGrid : LibraryDisplayState
    data class RssFeedDrillDown(val feed: RssFeedEntity) : LibraryDisplayState
    data object CollectionList : LibraryDisplayState
    data class CollectionDrillDown(val collectionId: String) : LibraryDisplayState
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onOpenNavigation: () -> Unit = {},
    onOpenManualImport: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    showRadialMenu: Boolean = true,
    discoverPreview: Boolean = false,
    onOpenRssSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    val selectedDomainFilter by viewModel.selectedDomainFilter.collectAsStateWithLifecycle()
    val selectedRssFeedFilter by viewModel.selectedRssFeedFilter.collectAsStateWithLifecycle()
    val collectionState by viewModel.collectionState.collectAsStateWithLifecycle()
    val collectionRows = collectionState.rows
    val collectionGroups = remember(collectionRows) { collectionRows.map { it.cardItem() } }
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsStateWithLifecycle()
    val collectionLoading = collectionState.loading
    val collectionError = collectionState.error
    val selectedCollection = collectionGroups.firstOrNull { it.id == selectedCollectionId }
    var editingCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCollectionEditorSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(collectionRows, collectionLoading, collectionError, selectedCollectionId) {
        if (!collectionLoading && collectionError == null && selectedCollectionId != null && selectedCollection == null) viewModel.selectCollectionFilter(null)
    }
    val visualSource = LocalCacheVisualSource.current
    val rssFeedGroups by viewModel.rssFeedGroups.collectAsStateWithLifecycle()
    val rssFeedGroupsLoading by viewModel.rssFeedGroupsLoading.collectAsStateWithLifecycle()
    val rssSubscriptionCount by viewModel.rssSubscriptionCount.collectAsStateWithLifecycle()
    val selectedFeed = rssFeedGroups.firstOrNull { it.feed.id == selectedRssFeedFilter?.id }?.feed ?: selectedRssFeedFilter
    val selectedFeedIcon = if (selectedFeed != null) rememberFeedVisual(selectedFeed, context = context, source = visualSource).value else null
    val domainGroups by viewModel.domainGroups.collectAsStateWithLifecycle()
    val initializationFailure by viewModel.initializationFailure.collectAsStateWithLifecycle()
    val initialized by viewModel.isInitialized.collectAsStateWithLifecycle()
    val domainGroupsLoading by viewModel.domainGroupsLoading.collectAsStateWithLifecycle()
    val isPrivateVisible by AppConfig.isPrivateContentVisible.collectAsStateWithLifecycle()
    val isLoading = !initialized ||
        (filterMode == LibraryFilterMode.DOMAIN && domainGroupsLoading) ||
        (filterMode == LibraryFilterMode.RSS && rssFeedGroupsLoading) ||
        (filterMode == LibraryFilterMode.COLLECTION && collectionLoading)
    val localConfig = LocalConfiguration.current
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val includeRss by viewModel.includeRssInNewestFirst.collectAsStateWithLifecycle()
    var isViewModeMenuExpanded by remember { mutableStateOf(false) }
    val selectedDomainColor = rememberDomainAccentColor(selectedDomainFilter)

    val domainGridState = rememberLazyGridState()
    val newestFirstListState = rememberLazyListState()
    val newestFirstGridState = rememberLazyGridState()
    val newestFirstGridAnchor = rememberBookmarkGridAnchor()
    val domainDetailListState = rememberLazyListState()
    val domainDetailGridState = rememberLazyGridState()
    val domainDetailGridAnchor = rememberBookmarkGridAnchor()
    val rssFeedGridState = rememberLazyGridState()
    val rssDetailListState = rememberLazyListState()
    val rssDetailGridState = rememberLazyGridState()
    val rssDetailGridAnchor = rememberBookmarkGridAnchor()
    val collectionListState = rememberLazyListState()
    val collectionDetailListState = rememberLazyListState()
    val collectionDetailGridState = rememberLazyGridState()
    val collectionDetailGridAnchor = rememberBookmarkGridAnchor()

    var lastScrollCollection by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedCollectionId) {
        if (selectedCollectionId != lastScrollCollection) {
            collectionDetailGridAnchor.clear()
            collectionDetailListState.requestScrollToItem(0)
            collectionDetailGridState.requestScrollToItem(0)
        }
        lastScrollCollection = selectedCollectionId
    }

    var lastScrollFilterMode by rememberSaveable { mutableStateOf(filterMode.name) }
    LaunchedEffect(filterMode) {
        if (lastScrollFilterMode != filterMode.name) {
            newestFirstGridAnchor.clear()
            newestFirstListState.requestScrollToItem(0)
            newestFirstGridState.requestScrollToItem(0)
        }
        lastScrollFilterMode = filterMode.name
    }

    // A scope change starts the new result at the top; Detail return keeps the saved scope and position.
    var lastScrollIncludeRss by rememberSaveable { mutableStateOf(includeRss) }
    LaunchedEffect(includeRss) {
        if (lastScrollIncludeRss != includeRss) {
            newestFirstGridAnchor.clear()
            newestFirstListState.requestScrollToItem(0)
            newestFirstGridState.requestScrollToItem(0)
        }
        lastScrollIncludeRss = includeRss
    }

    // Detail return restores the same domain; only an actual filter change resets its position.
    var lastScrollDomain by rememberSaveable { mutableStateOf<String?>(null) }
    var lastScrollFeed by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFeed?.id) {
        if (selectedFeed != null && selectedFeed.id != lastScrollFeed) {
            rssDetailGridAnchor.clear()
            rssDetailListState.requestScrollToItem(0)
            rssDetailGridState.requestScrollToItem(0)
        }
        lastScrollFeed = selectedFeed?.id
    }
    LaunchedEffect(selectedDomainFilter) {
        if (selectedDomainFilter != null && selectedDomainFilter != lastScrollDomain) {
            domainDetailGridAnchor.clear()
            // The other layout may be detached; request both positions without awaiting layout.
            domainDetailListState.requestScrollToItem(0)
            domainDetailGridState.requestScrollToItem(0)
        }
        lastScrollDomain = selectedDomainFilter
    }

    val displayState = remember(filterMode, selectedDomainFilter, selectedFeed?.id, selectedCollection?.id) {
        when {
            filterMode == LibraryFilterMode.COLLECTION && selectedCollection != null ->
                LibraryDisplayState.CollectionDrillDown(selectedCollection.id)
            filterMode == LibraryFilterMode.COLLECTION ->
                LibraryDisplayState.CollectionList
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
        LibraryDisplayState.CollectionList -> collectionListState
        is LibraryDisplayState.CollectionDrillDown -> if (viewMode == LibraryViewMode.LIST) collectionDetailListState else collectionDetailGridState
    }
    val headerScope = key(activeScrollState) { rememberCoroutineScope() }

    val observedCount = key(displayState, isPrivateVisible, includeRss) {
        val initialCount = when (displayState) {
            LibraryDisplayState.DomainGrid, LibraryDisplayState.RssFeedGrid, LibraryDisplayState.CollectionList -> 0
            is LibraryDisplayState.CollectionDrillDown -> selectedCollection?.count ?: 0
            is LibraryDisplayState.DomainDrillDown -> domainGroups.firstOrNull { it.domain == selectedDomainFilter }?.count
            is LibraryDisplayState.RssFeedDrillDown -> rssFeedGroups.firstOrNull { it.feed.id == selectedFeed?.id }?.count
            LibraryDisplayState.NewestFirst -> null
        }
        val count by remember(displayState, includeRss) {
            if (displayState == LibraryDisplayState.DomainGrid || displayState == LibraryDisplayState.RssFeedGrid || displayState == LibraryDisplayState.CollectionList) flowOf(0)
            else if (displayState is LibraryDisplayState.CollectionDrillDown) viewModel.libraryCount(LibraryFilterMode.COLLECTION, null, null, isPrivateVisible, displayState.collectionId)
            else viewModel.libraryCount(
                filterMode, selectedDomainFilter.takeIf { filterMode == LibraryFilterMode.DOMAIN },
                selectedFeed?.id, isPrivateVisible,
                includeRss = displayState == LibraryDisplayState.NewestFirst && includeRss
            )
        }.collectAsStateWithLifecycle(initialValue = initialCount)
        count
    }
    val libraryCount = observedCount ?: 0

    var peekBookmark by remember { mutableStateOf<BookmarkItem?>(null) }
    val libraryPeek = rememberLibraryPeekState()
    // The Library layer opens the Peek sheet (P5-Q1); the shipped Library keeps Summary Peek.
    fun openPeek(item: BookmarkItem) {
        if (discoverPreview) libraryPeek.peekId = item.id else peekBookmark = item
        viewModel.recordSummaryPeek(item.id, EngagementSurface.LIBRARY)
    }
    val radialMenuState = me.bubblespark.tidilo.ui.navigation.rememberLibraryRadialMenuState()
    val radialMenuOpen = radialMenuState.isOpen
    LaunchedEffect(peekBookmark != null, showCollectionEditorSheet) {
        if (peekBookmark != null || showCollectionEditorSheet) radialMenuState.cancel()
    }
    BackHandler(enabled = radialMenuOpen) { radialMenuState.cancel() }
    LaunchedEffect(filterMode, selectedDomainFilter, selectedFeed?.id) { peekBookmark = null }

    // Dismiss summary peek on system back gesture
    BackHandler(enabled = peekBookmark != null && !radialMenuOpen) {
        peekBookmark = null
    }

    // Restore to domain grid from domain drilldown on system back gesture (disabled during peek)
    val isDrillDown = (filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null) ||
        (filterMode == LibraryFilterMode.RSS && selectedFeed != null) ||
        (filterMode == LibraryFilterMode.COLLECTION && selectedCollection != null)
    val activeDrillDownColor = when {
        filterMode == LibraryFilterMode.COLLECTION && selectedCollection != null -> selectedCollection.accentColor
        filterMode == LibraryFilterMode.RSS && selectedFeed != null -> {
            val publisherDomain = visualSource.feedPublisherDomain(selectedFeed).orEmpty()
            selectedFeedIcon?.color ?: visualSource.resolveInstantDomainAccentColor(publisherDomain)
        }
        filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null -> {
            selectedDomainColor ?: visualSource.resolveInstantDomainAccentColor(selectedDomainFilter!!)
        }
        else -> null
    }
    RememberAmbientLighting(activeDrillDownColor, ownerId = "library_drilldown")
    val showTabs = discoverPreview && !isDrillDown
    val headerExtraHeight = if (showTabs) LibraryTabsHeight else 0.dp
    val headerHeight = HeaderDefaults.bodyTopPadding() + headerExtraHeight
    fun clearCollectionFilter() {
        when (filterMode) {
            LibraryFilterMode.RSS -> viewModel.selectRssFeedFilter(null)
            LibraryFilterMode.COLLECTION -> viewModel.selectCollectionFilter(null)
            else -> viewModel.clearDomainFilter()
        }
    }
    if (isDrillDown) {
        BackHandler(enabled = peekBookmark == null && !radialMenuOpen) {
            clearCollectionFilter()
        }
    }

    val bottomClearance = if (showRadialMenu) HeaderDefaults.LibraryActionClearance else HeaderDefaults.FloatingNavigationClearance
    val backgroundSemantics = if (radialMenuOpen || peekBookmark != null) Modifier.clearAndSetSemantics {} else Modifier
    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(),
        modifier = modifier,
        bodyModifier = backgroundSemantics,
        hideHeaderOnScroll = true,
        bottomContentPadding = bottomClearance,
        headerVisible = peekBookmark == null,
        headerExtraHeight = headerExtraHeight,
        overlay = {
            if (showRadialMenu && peekBookmark == null && !showCollectionEditorSheet) {
                me.bubblespark.tidilo.ui.navigation.LibraryRadialMenu(radialMenuState, filterMode,
                    viewModel::setFilterMode, onNavigateToSearch)
            }
            if (discoverPreview) LibraryPeekSheets(libraryPeek, viewModel)
            else BookmarkSummaryPeekOverlay(peekBookmark, headerHeight) { peekBookmark = null }
            if (showCollectionEditorSheet) {
                CollectionEditorBottomSheet(
                    collectionId = editingCollectionId,
                    onDismiss = {
                        showCollectionEditorSheet = false
                        editingCollectionId = null
                    }
                )
            }
        },
        // Expressive Header (slides out/in from top screen edge linked with scrolling, hidden during peek)
        header = {
            Box(backgroundSemantics) {
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
                        showTabs -> defaultTitle
                        filterMode == LibraryFilterMode.COLLECTION -> selectedCollection?.title ?: stringResource(R.string.collection_title)
                        filterMode == LibraryFilterMode.RSS -> selectedFeed?.title ?: stringResource(R.string.rss_title)
                        filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                            DomainPolicyManager.getDisplayName(selectedDomainFilter)
                        else -> defaultTitle
                    }
                    val headerSubtitle = when {
                        isLoading || observedCount == null ->
                            stringResource(R.string.library_subtitle_loading)
                        filterMode == LibraryFilterMode.COLLECTION && selectedCollection != null ->
                            pluralStringResource(R.plurals.rss_subtitle_bookmarks, libraryCount, libraryCount)
                        filterMode == LibraryFilterMode.COLLECTION ->
                            pluralStringResource(R.plurals.collection_subtitle_count, collectionGroups.size, collectionGroups.size)
            filterMode == LibraryFilterMode.RSS && selectedFeed != null ->
                            pluralStringResource(R.plurals.rss_subtitle_bookmarks, libraryCount, libraryCount)
                        filterMode == LibraryFilterMode.RSS ->
                            pluralStringResource(R.plurals.rss_subtitle_feeds, rssFeedGroups.size, rssFeedGroups.size)
                        filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                            stringResource(R.string.library_subtitle_domain_bookmarks, libraryCount)
                        filterMode == LibraryFilterMode.DOMAIN ->
                            stringResource(R.string.library_subtitle_domains, domainGroups.size)
                        includeRss ->
                            stringResource(R.string.library_subtitle_bookmarks_with_rss, libraryCount)
                        else ->
                            stringResource(R.string.library_subtitle_bookmarks, libraryCount)
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
                            style = modernRaisedTextStyle(when {
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
                            }),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = if (drillDown) TextOverflow.Clip else TextOverflow.Ellipsis
                        )
                    }
                    val subtitlePresentation = LibrarySubtitle(
                        headerSubtitle,
                        selectedCollection?.accentColor ?: selectedFeedIcon?.color ?: selectedDomainColor ?: MaterialTheme.colorScheme.primary
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
                    LibraryNavigationButton(
                        isDrillDown = isDrillDown,
                        backContentDescription = stringResource(when (filterMode) {
                            LibraryFilterMode.COLLECTION -> R.string.collection_back_to_collections
                            LibraryFilterMode.RSS -> R.string.rss_back_to_feeds
                            else -> R.string.library_back_to_domains
                        }),
                        onOpenNavigation = onOpenNavigation,
                        onNavigateUp = { clearCollectionFilter() }
                    )
                },
                bottomContent = if (showTabs) {
                    { LibraryTabs(filterMode, viewModel::setFilterMode) }
                } else null,
                actions = {
                    if (discoverPreview) {
                        // §9: one Grid/List toggle where bookmarks are listed; deck tabs have none.
                        if (displayState == LibraryDisplayState.NewestFirst || isDrillDown) {
                            val toList = viewMode == LibraryViewMode.GRID
                            IconButton(
                                onClick = { viewModel.setViewMode(if (toList) LibraryViewMode.LIST else LibraryViewMode.GRID) },
                                modifier = Modifier.size(48.dp).testTag("library_view_toggle")
                            ) {
                                Icon(
                                    imageVector = (if (toList) LibraryViewMode.LIST else LibraryViewMode.GRID).icon,
                                    contentDescription = stringResource(if (toList) R.string.library_show_as_list else R.string.library_show_as_grid),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else Box {
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
                        TidiloDropdownMenu(
                            expanded = isViewModeMenuExpanded,
                            onDismissRequest = { isViewModeMenuExpanded = false },
                            shape = themeCorner(ThemeCorner.IMAGE),
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


                }
            )
            }
        }
    ) { contentPadding ->
        if (initializationFailure != null) {
            Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                PagingFeedback(LoadState.Error(initializationFailure!!), viewModel::retryInitialization)
            }
        } else {
            key(isPrivateVisible) {
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
                    val collectionGrid = state == LibraryDisplayState.DomainGrid || state == LibraryDisplayState.RssFeedGrid || state == LibraryDisplayState.CollectionList
                    val isNewestFirst = state == LibraryDisplayState.NewestFirst
                    val sessionIncludesRss = isNewestFirst && includeRss
                    val session = remember(state, sessionIncludesRss) {
                        if (collectionGrid) null
                        else viewModel.libraryPages(
                            when (state) {
                                is LibraryDisplayState.RssFeedDrillDown -> LibraryFilterMode.RSS
                                is LibraryDisplayState.CollectionDrillDown -> LibraryFilterMode.COLLECTION
                                LibraryDisplayState.NewestFirst -> LibraryFilterMode.NEWEST_FIRST
                                else -> LibraryFilterMode.DOMAIN
                            },
                            (state as? LibraryDisplayState.DomainDrillDown)?.domain,
                            (state as? LibraryDisplayState.RssFeedDrillDown)?.feed?.id,
                            isPrivateVisible,
                            (state as? LibraryDisplayState.CollectionDrillDown)?.collectionId,
                            sessionIncludesRss
                        )
                    }
                    val scopeRow: @Composable () -> Unit = {
                        NewestFirstScopeRow(includeRss, viewModel::setIncludeRssInNewestFirst)
                    }
                    DisposableEffect(session) {
                        session?.let(viewModel::retainLibraryPages)
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
                        (state == LibraryDisplayState.RssFeedGrid && rssFeedGroupsLoading) ||
                        (state == LibraryDisplayState.CollectionList && collectionLoading)
                    val scopeStatePadding = newestFirstStatePadding(contentPadding, LocalLayoutDirection.current)
                    if (loading && isNewestFirst) {
                        // The scope stays adjustable while a new scope loads.
                        Column(Modifier.fillMaxSize().padding(scopeStatePadding)) {
                            scopeRow()
                            SkeletonBookmarkList(Modifier.fillMaxSize())
                        }
                    } else if (loading) {
                        SkeletonBookmarkList(Modifier.fillMaxSize().padding(top = headerHeight, start = 20.dp, end = 20.dp))
                    } else if (state == LibraryDisplayState.CollectionList && collectionError != null) {
                        Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                            PagingFeedback(LoadState.Error(requireNotNull(collectionError)), viewModel::retryCollections)
                        }
                    } else if (refreshState is LoadState.Error && isNewestFirst) {
                        Column(Modifier.fillMaxSize().padding(scopeStatePadding)) {
                            scopeRow()
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                PagingFeedback(refreshState, bookmarks::retry)
                            }
                        }
                    } else if (refreshState is LoadState.Error) {
                        Box(Modifier.fillMaxSize().padding(top = headerHeight), contentAlignment = Alignment.Center) {
                            PagingFeedback(refreshState, bookmarks::retry)
                        }
                    } else when (state) {
                        is LibraryDisplayState.CollectionList -> if (discoverPreview && collectionGroups.isEmpty()) {
                            LibraryEmptyState(LibraryEmpty.COLLECTION, contentPadding = contentPadding, onAction = {
                                editingCollectionId = null
                                showCollectionEditorSheet = true
                            })
                        } else {
                            LazyColumn(
                                state = collectionListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(
                                    items = collectionGroups,
                                    key = { it.id },
                                    contentType = { "collection_card" }
                                ) { group ->
                                    CollectionCard(
                                        item = collectionRows.first { it.collection.id == group.id },
                                        onClick = { viewModel.selectCollectionFilter(group.id) },
                                        onLongClick = {
                                            editingCollectionId = group.id
                                            showCollectionEditorSheet = true
                                        },
                                        gallery = discoverPreview
                                    )
                                }
                                item(key = "add_collection_dummy_card", contentType = "add_collection_dummy") {
                                    AddCollectionDummyCard(
                                        onClick = {
                                            editingCollectionId = null
                                            showCollectionEditorSheet = true
                                        }
                                    )
                                }
                            }
                        }
                        is LibraryDisplayState.DomainGrid, is LibraryDisplayState.RssFeedGrid -> {
                            val rss = state is LibraryDisplayState.RssFeedGrid
                            if (discoverPreview && (if (rss) rssFeedGroups.isEmpty() else domainGroups.isEmpty())) {
                                LibraryEmptyState(
                                    if (rss) rssEmpty(rssSubscriptionCount) else LibraryEmpty.DOMAIN,
                                    contentPadding = contentPadding,
                                    onAction = if (rss) onOpenRssSettings else onOpenManualImport
                                )
                            } else if (if (rss) rssFeedGroups.isEmpty() else domainGroups.isEmpty()) {
                                EmptyLibraryMessage(
                                    onSaveUrl = onOpenManualImport,
                                    contentPadding = contentPadding
                                )
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
                                            RssFeedCard(group, onClick = { viewModel.selectRssFeedFilter(group.feed) }, context = context, gallery = discoverPreview)
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
                                            },
                                            context = context,
                                            gallery = discoverPreview
                                        )
                                    }
                                }
                            }
                        }
                        is LibraryDisplayState.DomainDrillDown, is LibraryDisplayState.RssFeedDrillDown, is LibraryDisplayState.CollectionDrillDown -> {
                            val collectionItem = (state as? LibraryDisplayState.CollectionDrillDown)?.collectionId?.let { id ->
                                collectionGroups.firstOrNull { it.id == id }
                            }
                            val feed = (state as? LibraryDisplayState.RssFeedDrillDown)?.feed?.let { snapshot ->
                                rssFeedGroups.firstOrNull { it.feed.id == snapshot.id }?.feed ?: snapshot
                            }
                            val feedIcon = if (feed != null) rememberFeedVisual(feed, context = context, source = visualSource).value else null
                            val currentDomain = (state as? LibraryDisplayState.DomainDrillDown)?.domain
                                ?: feed?.let(visualSource::feedPublisherDomain).orEmpty()
                            val domainColor = collectionItem?.accentColor ?: feedIcon?.color ?: rememberDomainAccentColor(currentDomain)
                                ?: visualSource.resolveInstantDomainAccentColor(currentDomain)

                            val domainBookmarks = bookmarks

                            CompositionLocalProvider(LocalCardAccentColor provides domainColor) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    if (domainBookmarks.itemCount == 0 && discoverPreview) {
                                        LibraryEmptyState(LibraryEmpty.NEWEST, onAction = onOpenManualImport, contentPadding = contentPadding)
                                    } else if (domainBookmarks.itemCount == 0) {
                                        EmptyLibraryMessage(
                                            onSaveUrl = onOpenManualImport,
                                            contentPadding = contentPadding
                                        )
                                    } else if (discoverPreview) {
                                        val listState = if (collectionItem != null) collectionDetailListState else if (feed != null) rssDetailListState else domainDetailListState
                                        val gridState = when (state) {
                                            is LibraryDisplayState.CollectionDrillDown -> collectionDetailGridState
                                            is LibraryDisplayState.RssFeedDrillDown -> rssDetailGridState
                                            else -> domainDetailGridState
                                        }
                                        LibraryPlates(viewMode, domainBookmarks, listState, gridState, contentPadding,
                                            includeRss = feed != null, onOpen = onNavigateToDetail, onPeek = ::openPeek)
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
                                                        state = if (collectionItem != null) collectionDetailListState else if (feed != null) rssDetailListState else domainDetailListState,
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
                                                                onToggleLike = { viewModel.toggleLike(item.id, EngagementSurface.LIBRARY) },
                                                                bookmark = item,
                                                                wordKeywords = emptyList(),
                                                                tagKeywords = emptyList(),
                                                                currentLanguage = currentLanguage,
                                                                context = context,
                                                                onClick = { onNavigateToDetail(item.id) }
                                                            )
                                                        }
                                                    }
                                                }
                                                LibraryViewMode.GRID -> {
                                                    val (gridState, gridAnchor) = when (state) {
                                                        is LibraryDisplayState.CollectionDrillDown -> collectionDetailGridState to collectionDetailGridAnchor
                                                        is LibraryDisplayState.RssFeedDrillDown -> rssDetailGridState to rssDetailGridAnchor
                                                        else -> domainDetailGridState to domainDetailGridAnchor
                                                    }
                                                    LibraryBookmarkGrid(
                                                        bookmarks = domainBookmarks,
                                                        state = gridState,
                                                        anchor = gridAnchor,
                                                        generation = session?.key.orEmpty(),
                                                        active = state == displayState && viewMode == mode,
                                                        contentPadding = contentPadding,
                                                        bottomClearance = bottomClearance,
                                                        showRadialMenu = showRadialMenu,
                                                        onOpenBookmark = onNavigateToDetail,
                                                        onToggleLike = { viewModel.toggleLike(it, EngagementSurface.LIBRARY) },
                                                        onPeekBookmark = ::openPeek
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is LibraryDisplayState.NewestFirst -> {
                            if (bookmarks.itemCount == 0 && discoverPreview) {
                                LibraryEmptyState(LibraryEmpty.NEWEST, onAction = onOpenManualImport,
                                    contentPadding = contentPadding, leadingContent = scopeRow)
                            } else if (bookmarks.itemCount == 0) {
                                EmptyLibraryMessage(
                                    onSaveUrl = onOpenManualImport,
                                    contentPadding = contentPadding,
                                    leadingContent = scopeRow
                                )
                            } else if (discoverPreview) {
                                LibraryPlates(viewMode, bookmarks, newestFirstListState, newestFirstGridState, contentPadding,
                                    includeRss = includeRss, onOpen = onNavigateToDetail, onPeek = ::openPeek, leadingContent = scopeRow)
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
                                                item(key = NewestFirstScopeKey, contentType = NewestFirstScopeKey) { scopeRow() }
                                                items(
                                                    count = bookmarks.itemCount,
                                                    key = bookmarks.itemKey { it.id },
                                                    contentType = { "bookmark_card" }
                                                ) { index ->
                                                    val item = bookmarks[index] ?: return@items
                                                    BookmarkItemCard(
                                                        onToggleLike = { viewModel.toggleLike(item.id, EngagementSurface.LIBRARY) },
                                                        bookmark = item,
                                                        wordKeywords = emptyList(),
                                                        tagKeywords = emptyList(),
                                                        currentLanguage = currentLanguage,
                                                        context = context,
                                                        onClick = { onNavigateToDetail(item.id) },
                                                        showRssSource = includeRss && item.rssFeedId != null
                                                    )
                                                }
                                            }
                                        }
                                        LibraryViewMode.GRID -> {
                                            LibraryBookmarkGrid(
                                                bookmarks = bookmarks,
                                                state = newestFirstGridState,
                                                anchor = newestFirstGridAnchor,
                                                generation = session?.key.orEmpty(),
                                                active = state == displayState && viewMode == mode,
                                                contentPadding = contentPadding,
                                                bottomClearance = bottomClearance,
                                                showRadialMenu = showRadialMenu,
                                                leadingContent = scopeRow,
                                                leadingContentHeight = rememberNewestFirstScopeRowHeight(),
                                                showRssSource = includeRss,
                                                onOpenBookmark = onNavigateToDetail,
                                                onToggleLike = { viewModel.toggleLike(it, EngagementSurface.LIBRARY) },
                                                onPeekBookmark = ::openPeek
                                            )
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

/**
 * Library-layer bookmarks (§9): S plates in an adaptive grid or XS plate rows. The scroll position lives in
 * the saveable list/grid states; the radial-menu anchor logic of [LibraryBookmarkGrid] is not needed here.
 */
@Composable
private fun LibraryPlates(
    viewMode: LibraryViewMode,
    bookmarks: LazyPagingItems<BookmarkItem>,
    listState: LazyListState,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    includeRss: Boolean,
    onOpen: (String) -> Unit,
    onPeek: (BookmarkItem) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null
) {
    AnimatedContent(
        targetState = viewMode,
        transitionSpec = {
            fadeIn(animationSpec = tween(220, easing = EaseInOutCubic)) togetherWith
                fadeOut(animationSpec = tween(180, easing = EaseInOutCubic))
        },
        label = "library_plates_view_mode"
    ) { mode ->
        if (mode == LibraryViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(LibraryPlateMinCellWidth),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (leadingContent != null) {
                    item(key = NewestFirstScopeKey, span = { GridItemSpan(maxLineSpan) }, contentType = NewestFirstScopeKey) {
                        leadingContent()
                    }
                }
                items(count = bookmarks.itemCount, key = bookmarks.itemKey { it.id }, contentType = { "library_plate_card" }) { index ->
                    val item = bookmarks[index] ?: return@items
                    LibraryPlateCard(item, includeRss && item.rssFeedId != null, onOpen, onPeek)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (leadingContent != null) {
                    item(key = NewestFirstScopeKey, contentType = NewestFirstScopeKey) { leadingContent() }
                }
                items(count = bookmarks.itemCount, key = bookmarks.itemKey { it.id }, contentType = { "library_plate_row" }) { index ->
                    val item = bookmarks[index] ?: return@items
                    LibraryPlateRow(item, includeRss && item.rssFeedId != null, onOpen, onPeek)
                }
            }
        }
    }
}

@Composable
private fun LibraryBookmarkGrid(
    bookmarks: LazyPagingItems<BookmarkItem>,
    state: LazyGridState,
    anchor: BookmarkGridAnchor,
    generation: String,
    active: Boolean,
    contentPadding: PaddingValues,
    onOpenBookmark: (String) -> Unit,
    onToggleLike: (String) -> Unit,
    onPeekBookmark: (BookmarkItem) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
    leadingContentHeight: Dp = 0.dp,
    showRssSource: Boolean = false,
    bottomClearance: Dp = HeaderDefaults.LibraryActionClearance,
    showRadialMenu: Boolean = true
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val leadingItems = if (leadingContent != null) 1 else 0
        val layout = bookmarkGridLayout(
            contentPadding = contentPadding,
            trailingPadding = bottomClearance,
            leadingContentHeightPx = if (leadingContent != null) {
                with(density) { (leadingContentHeight + BookmarkGridVerticalGap).roundToPx() }
            } else 0,
            protectLibraryShortcut = showRadialMenu
        )
        if (active) {
            RestoreBookmarkGridAnchor(state, anchor, generation, layout, leadingItems) { key ->
                bookmarks.itemSnapshotList.items.indexOfFirst { it.id == key }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(BookmarkGridHorizontalGap),
            verticalArrangement = Arrangement.spacedBy(BookmarkGridVerticalGap)
        ) {
            if (leadingContent != null) {
                item(key = NewestFirstScopeKey, span = { GridItemSpan(maxLineSpan) }, contentType = NewestFirstScopeKey) {
                    leadingContent()
                }
            }
            items(
                count = bookmarks.itemCount,
                key = bookmarks.itemKey { it.id },
                contentType = { "bookmark_grid_card" }
            ) { index ->
                val bookmark = bookmarks[index] ?: return@items
                BookmarkGridCard(
                    bookmark = bookmark,
                    context = context,
                    onClick = { onOpenBookmark(bookmark.id) },
                    onLongClick = { onPeekBookmark(bookmark) },
                    onToggleLike = { onToggleLike(bookmark.id) },
                    layout = layout,
                    showRssSource = showRssSource && bookmark.rssFeedId != null
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryMessage(
    onSaveUrl: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    leadingContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
    ) {
        leadingContent()
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            shape = themeCorner(ThemeCorner.CARD),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.library_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onSaveUrl,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddLink,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.library_empty_save_url),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}


/** Shared borderless summary preview for library and search grid cards. */
@Composable
internal fun BookmarkSummaryPeekOverlay(bookmark: BookmarkItem?, headerHeight: androidx.compose.ui.unit.Dp, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    AnimatedVisibility(
        visible = bookmark != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(160)),
        modifier = Modifier.fillMaxSize()
    ) {
        val isOpen = bookmark != null
        var lastPeek by remember { mutableStateOf(bookmark) }
        SideEffect {
            if (bookmark != null) lastPeek = bookmark
        }
        val currentPeek = bookmark ?: lastPeek
        val summaryTitle = stringResource(R.string.detail_section_summary)
        val pane = if (isOpen) {
            Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } }
                .semantics { paneTitle = summaryTitle }
        } else {
            Modifier.clearAndSetSemantics {}
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(pane)
        ) {
            val background = MaterialTheme.colorScheme.background
            ChromeBlurBackground(
                shape = RectangleShape,
                containerColor = background,
                modifier = Modifier.matchParentSize(),
                outline = false,
                unavailableColor = background.copy(alpha = 0.85f)
            )
            if (currentPeek != null) {
                val closeLabel = stringResource(R.string.common_close)
                val closeAction = if (isOpen) {
                    Modifier.semantics {
                        customActions = listOf(CustomAccessibilityAction(closeLabel) {
                            dismiss()
                            true
                        })
                    }
                } else {
                    Modifier
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight)
                        .padding(horizontal = 24.dp)
                        .then(closeAction)
                        .verticalScroll(rememberScrollState(), enabled = isOpen)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_summarize),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = summaryTitle,
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
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
private fun rememberDomainAccentColor(domain: String?): Color? {
    val visual by rememberDomainVisual(domain = domain?.takeIf { it.isNotBlank() })
    return visual.accentColor.takeIf { it != Color.Unspecified }
}
