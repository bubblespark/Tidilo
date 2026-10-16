package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import me.bubblespark.tidilo.ui.components.ArrowDownwardIcon
import me.bubblespark.tidilo.ui.components.DomainIcon
import me.bubblespark.tidilo.ui.components.FilterListIcon
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode

/**
 * M3 Expressive Floating Navigation Bar with Separated Action Button
 *
 * - Tabs order: Library -> Search -> Queue
 * - Action button colors: Accent color background (primary), White icon (Color.White)
 * - Library action button: FAB menu toggle (FilterListIcon / Close), opens "Domain" & "Newest first" pill menu
 * - Search action button: Focus search input (Search icon)
 * - Queue action button: AI Tagging All (AutoAwesome icon)
 */
@Composable
fun TidiloBottomBar(
    navController: NavController,
    isBarsVisible: Boolean = true,
    queuingCount: Int = 0,
    isQueueRunning: Boolean = false,
    isFilterMenuExpanded: Boolean = false,
    onToggleFilterMenu: () -> Unit = {},
    currentFilterMode: LibraryFilterMode = LibraryFilterMode.NEWEST_FIRST,
    onSelectFilterMode: (LibraryFilterMode) -> Unit = {},
    onSearchAction: () -> Unit = {},
    onAiTaggingAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Floating navigation tabs: Library, Search, Queue
    val items = listOf(
        Screen.Library,
        Screen.Search,
        Screen.Queue
    )

    val barHeight = 68.dp
    val containerBgColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val isActionButtonVisible = currentRoute in listOf(
        Screen.Library.route,
        Screen.Search.route,
        Screen.Queue.route
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

        val actionButtonOffsetX by animateDpAsState(
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
            Surface(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .shadow(elevation = 10.dp, shape = CircleShape),
                shape = CircleShape,
                color = containerBgColor,
                tonalElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "queueHourglassTransition")

                    // Hourglass 180-degree flip loop animation (Issue #3)
                    // 3400ms cycle:
                    // 0ms..500ms: Flip 180° with EaseOutBack bounce
                    // 500ms..1700ms: Resting at 180° (sand falling metaphor)
                    // 1700ms..2200ms: Flip 180° -> 360° with EaseOutBack bounce
                    // 2200ms..3400ms: Resting at 360°/0°
                    val hourglassRotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 3400
                                0f at 0 using EaseOutBack
                                180f at 500 using LinearEasing
                                180f at 1700 using EaseOutBack
                                360f at 2200 using LinearEasing
                                360f at 3400
                            },
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "queueHourglassRotation"
                    )

                    // Dynamic pop/bounce scale during rotation peaks
                    val hourglassScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 3400
                                1f at 0 using FastOutSlowInEasing
                                1.15f at 250 using FastOutSlowInEasing
                                1f at 500
                                1f at 1700 using FastOutSlowInEasing
                                1.15f at 1950 using FastOutSlowInEasing
                                1f at 2200
                                1f at 3400
                            },
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "queueHourglassScale"
                    )

                    items.forEach { screen ->
                        val isSelected = currentRoute == screen.route

                        val activeContainerColor = MaterialTheme.colorScheme.primaryContainer
                        val inactiveTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        val activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer

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

                        Box(
                            modifier = Modifier
                                .weight(tabWeight)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(itemBgColor, shape = CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true)
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

                                val iconModifier = if (screen == Screen.Queue && isQueueRunning) {
                                    Modifier
                                        .size(28.dp)
                                        .graphicsLayer {
                                            rotationZ = hourglassRotation
                                            scaleX = hourglassScale
                                            scaleY = hourglassScale
                                        }
                                } else {
                                    Modifier.size(28.dp)
                                }

                                if (screen == Screen.Queue && queuingCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            ) {
                                                Text(
                                                    text = if (queuingCount > 99) "99+" else queuingCount.toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = iconVector,
                                            contentDescription = screen.title,
                                            modifier = iconModifier,
                                            tint = iconColor
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = iconVector,
                                        contentDescription = screen.title,
                                        modifier = iconModifier,
                                        tint = iconColor
                                    )
                                }

                                // Display label only for selected item
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
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = screen.title,
                                            style = MaterialTheme.typography.labelMedium,
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

        // 2. Action button and FAB menu positioned at the right offset
        // Palette requirement: matches active tab selection (primaryContainer / onPrimaryContainer)
        AnimatedVisibility(
            visible = isActionButtonVisible,
            enter = scaleIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = scaleOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = actionButtonOffsetX)
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
                        // 1. Top pill: "Domain"
                        val isDomainActive = currentFilterMode == LibraryFilterMode.DOMAIN
                        Surface(
                            shape = CircleShape,
                            color = if (isDomainActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple()
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.DOMAIN)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                Icon(
                                    imageVector = DomainIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = "Domain",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDomainActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // 2. Bottom pill: "Newest first"
                        val isNewestActive = currentFilterMode == LibraryFilterMode.NEWEST_FIRST
                        Surface(
                            shape = CircleShape,
                            color = if (isNewestActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shadowElevation = 6.dp,
                            tonalElevation = 4.dp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple()
                                ) {
                                    onSelectFilterMode(LibraryFilterMode.NEWEST_FIRST)
                                    onToggleFilterMenu()
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                Icon(
                                    imageVector = ArrowDownwardIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    text = "Newest first",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isNewestActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }

                // Action button surface (palette: primaryContainer / onPrimaryContainer)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = buttonElevation
                ) {
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
                                    Screen.Queue.route -> onAiTaggingAll()
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
                                        contentDescription = "Close filter menu",
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Library.route -> {
                                    Icon(
                                        imageVector = FilterListIcon,
                                        contentDescription = "Filter menu",
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Search.route -> {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = iconColor,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }
                                Screen.Queue.route -> {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = "AI Tagging All",
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
