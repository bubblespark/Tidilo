package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.res.stringResource
import me.bubblespark.tidilo.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import me.bubblespark.tidilo.data.AiProcessingProgress
import me.bubblespark.tidilo.ui.components.AiProcessingIndicatorButton
import me.bubblespark.tidilo.ui.components.AiProcessingSpeechBubble
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import androidx.compose.ui.graphics.luminance
import me.bubblespark.tidilo.ui.theme.ActiveTabIndicatorStyle
import me.bubblespark.tidilo.ui.theme.LocalAppThemeStyle
import me.bubblespark.tidilo.ui.theme.themeChipSurface
import me.bubblespark.tidilo.ui.theme.themeFloatingBarSurface
import me.bubblespark.tidilo.ui.theme.themeIconButtonSurface
import me.bubblespark.tidilo.ui.theme.themeSurfaceBlur

/**
 * M3 Expressive Floating Navigation Bar with Separated Action Button
 *
 * - Tabs order: Library -> Search -> Queue
 * - Action button colors: Accent color background (primary), White icon (Color.White)
 * - Library action button: FAB menu toggle (FilterListIcon / Close), opens "Domain" & "Newest first" pill menu
 * - Search action button: Focus search input (Search icon)
 * - Queue action button: AI Tagging All (AutoAwesome icon)
 * - Left floating button: AI Processing Status Indicator with rainbow glow and progress speech bubble
 */
@Composable
fun TidiloBottomBar(
    navController: NavController,
    isBarsVisible: Boolean = true,
    readyCount: Int = 0,
    isFilterMenuExpanded: Boolean = false,
    onToggleFilterMenu: () -> Unit = {},
    currentFilterMode: LibraryFilterMode = LibraryFilterMode.NEWEST_FIRST,
    onSelectFilterMode: (LibraryFilterMode) -> Unit = {},
    onSearchAction: () -> Unit = {},
    onAiAnalysisAll: () -> Unit = {},
    aiProcessingProgress: AiProcessingProgress = AiProcessingProgress(),
    isSpeechBubbleVisible: Boolean = false,
    onToggleSpeechBubble: () -> Unit = {},
    onDismissSpeechBubble: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    onCancelProcessing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Floating navigation tabs: Library, Search, Import
    val items = listOf(
        Screen.Library,
        Screen.Search,
        Screen.Import
    )

    val themeStyle = LocalAppThemeStyle.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isTactileIndicator = themeStyle.floatingBarStyle.activeTabStyle == ActiveTabIndicatorStyle.TACTILE_EXTRUDED_TILE
    val barRippleColor = if (isTactileIndicator) {
        if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    } else {
        Color.Unspecified
    }

    val barHeight = 68.dp
    val containerBgColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = themeStyle.floatingBarAlpha)
    val isActionButtonVisible = currentRoute in listOf(
        Screen.Library.route,
        Screen.Search.route,
        Screen.Import.route
    )

    // M3 Expressive: contracted size is 56dp (icon 28dp); expanded/hidden size is 86dp (icon 40dp)
    val buttonSize by animateDpAsState(
        targetValue = if (isBarsVisible) 56.dp else 86.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "actionButtonSize"
    )

    val iconSize by animateDpAsState(
        targetValue = if (isBarsVisible) 28.dp else 40.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "actionButtonIconSize"
    )

    val buttonElevation by animateDpAsState(
        targetValue = if (isBarsVisible) 6.dp else 10.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "actionButtonElevation"
    )

    val containerHeight = maxOf(barHeight, buttonSize)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight),
        contentAlignment = Alignment.Center
    ) {
        val barWidth = maxWidth * 0.58f
        val buttonGap = 14.dp

        // Contracted: 14dp spacing from navigation bar right edge to prevent overlap
        val contractedCenterOffsetX = (barWidth / 2) + buttonGap + (56.dp / 2)
        // Expanded (86dp container): standard 16dp FAB margin from screen right edge
        val expandedCenterOffsetX = (maxWidth / 2) - 16.dp - (86.dp / 2)

        val actionButtonOffsetX = animateDpAsState(
            targetValue = if (isBarsVisible) contractedCenterOffsetX else expandedCenterOffsetX,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "actionButtonOffsetX"
        )

        // 1. Centered floating navigation bar container (slides out/in with scroll, hidden when FAB menu expanded)
        val isFloatingNavVisible = isBarsVisible && !isFilterMenuExpanded
        AnimatedVisibility(
            visible = isFloatingNavVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeOut(animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val floatingBarDepth = themeStyle.floatingBarStyle.depth
            val floatingBarShadow = if (floatingBarDepth > 0.dp) 0.dp else 10.dp
            val floatingBarTonal = if (floatingBarDepth > 0.dp) 0.dp else 6.dp

            Surface(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .then(
                        if (floatingBarShadow > 0.dp) Modifier.shadow(elevation = floatingBarShadow, shape = CircleShape)
                        else Modifier
                    )
                    .themeFloatingBarSurface(
                        style = themeStyle.floatingBarStyle,
                        palette = themeStyle.surfacePalette,
                        isDark = isDark,
                        fallbackShape = CircleShape
                    ),
                shape = CircleShape,
                color = if (floatingBarDepth > 0.dp) Color.Transparent else containerBgColor,
                tonalElevation = floatingBarTonal
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val tabShape = CircleShape
                        val tactileTabChipStyle = remember(themeStyle.chipStyle) {
                            themeStyle.chipStyle.copy(shape = tabShape)
                        }
                        val activeContainerColor = if (isTactileIndicator) {
                            themeStyle.floatingBarStyle.activeContainer(
                                isDark = isDark,
                                fallback = themeStyle.surfacePalette.container(
                                    isDark,
                                    fallback = if (isDark) Color(0xFF22262B) else Color.White
                                )
                            )
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                        val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val activeContentColor = if (isTactileIndicator) {
                            themeStyle.floatingBarStyle.activeContent(
                                isDark = isDark,
                                fallback = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                            )
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }

                        items.forEach { screen ->
                            val isSelected = currentRoute == screen.route

                            val itemBgColor by animateColorAsState(
                                targetValue = if (isSelected) activeContainerColor else Color.Transparent,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "tabBgColor_${screen.route}"
                            )

                            val tabWeight by animateFloatAsState(
                                targetValue = if (isSelected) 1.35f else 1.0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "tabWeight_${screen.route}"
                            )

                            // Selected icon is 10% smaller (28dp * 0.9 = 25.2dp -> 25dp truncated)
                            val tabIconSize by animateDpAsState(
                                targetValue = if (isSelected) 25.dp else 28.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "tabIconSize_${screen.route}"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(tabWeight)
                                    .fillMaxHeight()
                                    .then(
                                        if (isSelected && isTactileIndicator) {
                                            Modifier.themeChipSurface(
                                                style = tactileTabChipStyle,
                                                palette = themeStyle.surfacePalette,
                                                isDark = isDark,
                                                fallbackShape = tabShape,
                                                fallbackContainerColor = activeContainerColor
                                            )
                                        } else {
                                            Modifier.background(itemBgColor, shape = tabShape)
                                        }
                                    )
                                    .clip(tabShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true, color = barRippleColor)
                                    ) {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    val iconVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon
                                    val iconColor = if (isSelected) activeContentColor else inactiveTextColor

                                    if (screen == Screen.Import && readyCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge(
                                                    containerColor = MaterialTheme.colorScheme.error,
                                                    contentColor = MaterialTheme.colorScheme.onError
                                                 ) {
                                                    Text(
                                                        text = if (readyCount > 99) "99+" else readyCount.toString(),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = iconVector,
                                                contentDescription = stringResource(screen.titleRes),
                                                modifier = Modifier.size(tabIconSize),
                                                tint = iconColor
                                            )
                                        }
                                    } else {
                                        Icon(
                                            imageVector = iconVector,
                                            contentDescription = stringResource(screen.titleRes),
                                            modifier = Modifier.size(tabIconSize),
                                            tint = iconColor
                                        )
                                    }

                                    // Display label only for selected item (10% smaller: 12sp * 0.9 = 10.8sp -> 10sp truncated)
                                    AnimatedVisibility(
                                        visible = isSelected,
                                        enter = fadeIn(animationSpec = tween(150, delayMillis = 40)) + expandVertically(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        ),
                                        exit = fadeOut(animationSpec = tween(100)) + shrinkVertically(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        )
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = stringResource(screen.titleRes),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 10.sp,
                                                    lineHeight = 14.sp
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = activeContentColor,
                                                maxLines = 1,
                                                softWrap = false
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

        // 2. AI Processing indicator button positioned symmetrically at the left offset
        AnimatedVisibility(
            visible = aiProcessingProgress.isProcessing,
            enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(x = -actionButtonOffsetX.value.roundToPx(), y = 0) }
        ) {
            Box(
                modifier = Modifier.requiredSize(buttonSize),
                contentAlignment = Alignment.Center
            ) {
                // Elevated speech bubble floating above the left button
                AnimatedVisibility(
                    visible = isSpeechBubbleVisible,
                    enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                    exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                        targetOffsetY = { it / 3 },
                        animationSpec = tween(120)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(y = -(buttonSize + 14.dp))
                        .wrapContentSize(align = Alignment.BottomStart, unbounded = true)
                ) {
                    AiProcessingSpeechBubble(
                        progress = aiProcessingProgress,
                        buttonSize = buttonSize,
                        onNavigateToImport = onNavigateToImport,
                        onDismiss = onDismissSpeechBubble,
                        onCancelProcessing = onCancelProcessing
                    )
                }

                // Rainbow glowing button
                AiProcessingIndicatorButton(
                    progress = aiProcessingProgress,
                    buttonSize = buttonSize,
                    iconSize = iconSize,
                    buttonElevation = buttonElevation,
                    onClick = onToggleSpeechBubble
                )
            }
        }

        // 3. Action button and FAB menu positioned at the right offset
        // Palette requirement: matches active tab selection (primaryContainer / onPrimaryContainer)
        AnimatedVisibility(
            visible = isActionButtonVisible,
            enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(x = actionButtonOffsetX.value.roundToPx(), y = 0) }
        ) {
            Box(
                modifier = Modifier.requiredSize(buttonSize),
                contentAlignment = Alignment.Center
            ) {
                // FAB menu pills in Library screen: "Domain" and "Newest first"
                // Sized to match Details Screen FAB menu (horizontal 24dp, vertical 16dp, icon 28dp, titleMedium)
                // Constant size regardless of parent FAB expansion, right-aligned to parent FAB
                AnimatedVisibility(
                    visible = isFilterMenuExpanded && currentRoute == Screen.Library.route,
                    enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ),
                    exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(120)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(y = -(buttonSize + 16.dp))
                        .wrapContentSize(align = Alignment.BottomEnd, unbounded = true)
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        // Collection filter
                        val isCollectionActive = currentFilterMode == LibraryFilterMode.COLLECTION
                        Surface(
                            shape = CircleShape,
                            color = (if (isCollectionActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = LocalAppThemeStyle.current.floatingBarAlpha),
                            contentColor = if (isCollectionActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .semantics { selected = isCollectionActive }
                                .clickable(
                                    role = Role.Button,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = barRippleColor)
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.COLLECTION)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Box {
                                Box(modifier = Modifier.matchParentSize().themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                                ) {
                                Icon(
                                    imageVector = Icons.Default.FolderSpecial,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isCollectionActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = stringResource(R.string.collection_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCollectionActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            }
                        }

                        // RSS source filter
                        val isRssActive = currentFilterMode == LibraryFilterMode.RSS
                        Surface(
                            shape = CircleShape,
                            color = (if (isRssActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = LocalAppThemeStyle.current.floatingBarAlpha),
                            contentColor = if (isRssActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = barRippleColor)
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.RSS)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Box {
                                Box(modifier = Modifier.matchParentSize().themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                                ) {
                                Icon(
                                    imageVector = Icons.Default.RssFeed,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isRssActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = stringResource(R.string.rss_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isRssActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            }
                        }

                        // 1. Top pill: "Domain"
                        val isDomainActive = currentFilterMode == LibraryFilterMode.DOMAIN
                        Surface(
                            shape = CircleShape,
                            color = (if (isDomainActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = LocalAppThemeStyle.current.floatingBarAlpha),
                            contentColor = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = barRippleColor)
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.DOMAIN)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Box {
                                Box(modifier = Modifier.matchParentSize().themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                                ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_domain),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = stringResource(R.string.bottom_bar_domain),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            }
                        }

                        // 2. Bottom pill: "Newest first"
                        val isNewestActive = currentFilterMode == LibraryFilterMode.NEWEST_FIRST
                        Surface(
                            shape = CircleShape,
                            color = (if (isNewestActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer).copy(alpha = LocalAppThemeStyle.current.floatingBarAlpha),
                            contentColor = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = barRippleColor)
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.NEWEST_FIRST)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Box {
                                Box(modifier = Modifier.matchParentSize().themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                                ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_downward),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = stringResource(R.string.bottom_bar_newest_first),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            }
                        }
                    }
                }

                // Action button surface (palette: primaryContainer / onPrimaryContainer)
                val actionButtonElevation = if (themeStyle.iconButtonStyle.depth > 0.dp) 0.dp else buttonElevation
                val actionButtonFallbackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = themeStyle.floatingBarAlpha)
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .themeIconButtonSurface(
                            plate = themeStyle.iconButtonStyle.actionButtonPlate,
                            style = themeStyle.iconButtonStyle,
                            palette = themeStyle.surfacePalette,
                            isDark = isDark,
                            shape = CircleShape,
                            fallbackContainerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                    shape = CircleShape,
                    color = if (themeStyle.iconButtonStyle.depth > 0.dp) Color.Transparent else actionButtonFallbackColor,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = actionButtonElevation
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .themeSurfaceBlur(LocalAppThemeStyle.current.floatingBarBlurRadius, CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = MaterialTheme.colorScheme.onPrimaryContainer, bounded = true)
                            ) {
                                when (currentRoute) {
                                    Screen.Library.route -> onToggleFilterMenu()
                                    Screen.Search.route -> onSearchAction()
                                    Screen.Import.route -> onAiAnalysisAll()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = if (currentRoute == Screen.Library.route && isFilterMenuExpanded) {
                                "library_close"
                            } else {
                                currentRoute
                            },
                            transitionSpec = {
                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.8f))
                                    .togetherWith(fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 0.8f))
                            },
                            label = "actionButtonIcon"
                        ) { targetState ->
                            val iconColor = MaterialTheme.colorScheme.onPrimaryContainer
                            when (targetState) {
                                "library_close" -> {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.bottom_bar_close_filter_menu),
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Library.route -> {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_filter_list),
                                        contentDescription = stringResource(R.string.bottom_bar_filter_menu),
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Search.route -> {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = stringResource(R.string.bottom_bar_search),
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Import.route -> {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = stringResource(R.string.bottom_bar_ai_analysis_all),
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                else -> {
                                    Spacer(modifier = Modifier.size(iconSize))
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
