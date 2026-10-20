package me.bubblespark.tidilo.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import me.bubblespark.tidilo.ui.components.Dashboard2GearIcon
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
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.ui.components.FrostedHeaderContainer
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface LibraryDisplayState {
    data object NewestFirst : LibraryDisplayState
    data object DomainGrid : LibraryDisplayState
    data class DomainDrillDown(val domain: String) : LibraryDisplayState
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    val selectedDomainFilter by viewModel.selectedDomainFilter.collectAsStateWithLifecycle()
    val domainGroups by viewModel.domainGroups.collectAsStateWithLifecycle()
    val domainFilteredBookmarks by viewModel.domainFilteredBookmarks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isBarsVisible = LocalBarsVisible.current
    val context = LocalContext.current
    val localConfig = LocalConfiguration.current
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    var isViewModeMenuExpanded by remember { mutableStateOf(false) }
    val iconVersion by DomainIconManager.iconUpdateVersion.collectAsStateWithLifecycle()

    val selectedDomainColor by produceState<Color?>(
        initialValue = selectedDomainFilter?.let {
            DomainIconManager.getCachedDominantColor(it) ?: DomainIconManager.generateDomainAccentColor(it)
        },
        key1 = selectedDomainFilter,
        key2 = iconVersion
    ) {
        val domain = selectedDomainFilter
        if (domain != null) {
            val cached = DomainIconManager.getCachedDominantColor(domain)
            if (cached != null) {
                value = cached
            } else {
                value = withContext(Dispatchers.IO) {
                    DomainIconManager.getDominantColor(context, domain)
                }
            }
        } else {
            value = null
        }
    }

    val domainGridState = rememberLazyGridState()
    val newestFirstListState = rememberLazyListState()
    val domainDetailListState = rememberLazyListState()

    LaunchedEffect(selectedDomainFilter) {
        if (selectedDomainFilter != null) {
            domainDetailListState.scrollToItem(0)
        }
    }

    val displayState = remember(filterMode, selectedDomainFilter) {
        when {
            filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                LibraryDisplayState.DomainDrillDown(selectedDomainFilter!!)
            filterMode == LibraryFilterMode.DOMAIN ->
                LibraryDisplayState.DomainGrid
            else ->
                LibraryDisplayState.NewestFirst
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarTop + 80.dp

    // Restore to domain grid from domain drilldown on system back gesture
    if (filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null) {
        BackHandler {
            viewModel.clearDomainFilter()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isLoading) {
            SkeletonBookmarkList(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = headerHeight, start = 20.dp, end = 20.dp)
            )
        } else {
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
                when (state) {
                    is LibraryDisplayState.DomainGrid -> {
                        if (domainGroups.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                            ) {
                                EmptyLibraryMessage()
                            }
                        } else {
                            LazyVerticalGrid(
                                state = domainGridState,
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = headerHeight,
                                    bottom = 140.dp,
                                    start = 20.dp,
                                    end = 20.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
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
                    is LibraryDisplayState.DomainDrillDown -> {
                        val currentDomain = state.domain
                        val domainColor = selectedDomainColor
                            ?: DomainIconManager.getCachedDominantColor(currentDomain)
                            ?: DomainIconManager.generateDomainAccentColor(currentDomain)

                        val domainBookmarks = remember(bookmarks, currentDomain) {
                            LibraryViewModel.filterBookmarksByDomain(bookmarks, currentDomain)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
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
                                    drawRect(brush = ambientBeam)

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
                                    drawRect(brush = cornerGlow)
                                }
                        ) {
                            if (domainBookmarks.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                                ) {
                                    EmptyLibraryMessage()
                                }
                            } else {
                                LazyColumn(
                                    state = domainDetailListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        top = headerHeight,
                                        bottom = 140.dp,
                                        start = 20.dp,
                                        end = 20.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(
                                        items = domainBookmarks,
                                        key = { it.id },
                                        contentType = { "bookmark_card" }
                                    ) { item ->
                                        BookmarkItemCard(
                                            bookmark = item,
                                            wordKeywords = emptyList(),
                                            tagKeywords = emptyList(),
                                            currentLanguage = currentLanguage,
                                            onClick = { onNavigateToDetail(item.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is LibraryDisplayState.NewestFirst -> {
                        if (bookmarks.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = headerHeight, start = 20.dp, end = 20.dp)
                            ) {
                                EmptyLibraryMessage()
                            }
                        } else {
                            LazyColumn(
                                state = newestFirstListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    top = headerHeight,
                                    bottom = 140.dp,
                                    start = 20.dp,
                                    end = 20.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    items = bookmarks,
                                    key = { it.id },
                                    contentType = { "bookmark_card" }
                                ) { item ->
                                    BookmarkItemCard(
                                        bookmark = item,
                                        wordKeywords = emptyList(),
                                        tagKeywords = emptyList(),
                                        currentLanguage = currentLanguage,
                                        onClick = { onNavigateToDetail(item.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Expressive Header (slides out/in from top screen edge linked with scrolling)
        AnimatedVisibility(
            visible = isBarsVisible,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            FrostedHeaderContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        // Back button during domain drilldown
                        AnimatedVisibility(
                            visible = filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null,
                            enter = fadeIn(animationSpec = tween(200)) + expandHorizontally(),
                            exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally()
                        ) {
                            IconButton(
                                onClick = { viewModel.clearDomainFilter() },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.library_back_to_domains),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }

                        Column {
                            val defaultTitle = stringResource(R.string.library_header_title)
                            val headerTitle = when {
                                filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                                    DomainPolicyManager.getDisplayName(selectedDomainFilter)
                                else -> defaultTitle
                            }
                            val headerSubtitle = when {
                                isLoading ->
                                    stringResource(R.string.library_subtitle_loading)
                                filterMode == LibraryFilterMode.DOMAIN && selectedDomainFilter != null ->
                                    stringResource(R.string.library_subtitle_domain_bookmarks, domainFilteredBookmarks.size)
                                filterMode == LibraryFilterMode.DOMAIN ->
                                    stringResource(R.string.library_subtitle_domains, domainGroups.size)
                                else ->
                                    stringResource(R.string.library_subtitle_tagged_bookmarks, bookmarks.size)
                            }

                            AnimatedContent(
                                targetState = headerTitle,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                                },
                                label = "header_title"
                            ) { title ->
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            AnimatedContent(
                                targetState = headerSubtitle,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                                },
                                label = "header_subtitle"
                            ) { subtitle ->
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selectedDomainFilter != null && selectedDomainColor != null) {
                                        selectedDomainColor!!
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // View mode toggle menu button
                        Box {
                            IconButton(
                                onClick = { isViewModeMenuExpanded = true },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Dashboard2GearIcon,
                                    contentDescription = stringResource(R.string.library_view_mode_menu_desc),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            val menuWidth = 200.dp
                            DropdownMenu(
                                expanded = isViewModeMenuExpanded,
                                onDismissRequest = { isViewModeMenuExpanded = false },
                                offset = DpOffset(x = 88.dp - menuWidth, y = 0.dp),
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
                                        val itemBackground = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.Transparent
                                        }
                                        val itemContentColor = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                        val iconTint = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }

                                        DropdownMenuItem(
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(itemBackground),
                                            text = {
                                                Text(
                                                    text = stringResource(mode.labelRes),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = itemContentColor
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
                                                            tint = itemContentColor,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    } else {
                                                        Spacer(modifier = Modifier.size(18.dp))
                                                    }
                                                    Icon(
                                                        imageVector = mode.icon,
                                                        contentDescription = null,
                                                        tint = iconTint,
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
                            onClick = onNavigateToSettings,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.title_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
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
