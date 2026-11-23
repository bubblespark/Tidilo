package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.discover.DiscoverScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel

import androidx.lifecycle.viewmodel.compose.viewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.TagSelectionScreen

import androidx.navigation.NavType
import androidx.navigation.navArgument
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import androidx.lifecycle.compose.dropUnlessResumed
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestScreen

internal fun NavHostController.navigateTopLevel(destination: TopLevelDestination) =
    navigate(destination.route) {
        popUpTo(Screen.Discover.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

@Composable
fun TidiloNavGraph(
    navController: NavHostController,
    importViewModel: ImportViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    modifier: Modifier = Modifier,
    discoverPreviewEnabled: Boolean = false,
    onOpenNavigation: () -> Unit = {},
    onOpenManualImport: () -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val topLevelSlideOffsetPx = with(density) { 14.dp.roundToPx() }
    val topLevelEnterSpec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f, stiffness = 380f)
    fun AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTopLevelSwitch(): Boolean =
        discoverPreviewEnabled &&
            topLevelDestinationOf(initialState.destination.route) != null &&
            topLevelDestinationOf(targetState.destination.route) != null

    // The only collector, so a failure shows one Toast even while two screens share a transition.
    LaunchedEffect(libraryViewModel) {
        libraryViewModel.events.collect {
            Toast.makeText(context, R.string.common_save_failed, Toast.LENGTH_SHORT).show()
        }
    }
    NavHost(
        navController = navController,
        startDestination = if (discoverPreviewEnabled) Screen.Discover.route else Screen.Library.route,
        modifier = modifier,
        enterTransition = {
            if (isTopLevelSwitch()) {
                fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = topLevelEnterSpec) { topLevelSlideOffsetPx }
            } else {
                val direction = if (targetState.id == navController.previousBackStackEntry?.id) {
                    AnimatedContentTransitionScope.SlideDirection.End
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Start
                }
                slideIntoContainer(
                    towards = direction,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        },
        exitTransition = {
            if (isTopLevelSwitch()) {
                fadeOut(animationSpec = tween(220))
            } else {
                val direction = if (targetState.id == navController.previousBackStackEntry?.id) {
                    AnimatedContentTransitionScope.SlideDirection.End
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Start
                }
                slideOutOfContainer(
                    towards = direction,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        },
        popEnterTransition = {
            if (isTopLevelSwitch()) {
                fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = topLevelEnterSpec) { topLevelSlideOffsetPx }
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        },
        popExitTransition = {
            if (isTopLevelSwitch()) {
                fadeOut(animationSpec = tween(220))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        },
        predictivePopEnterTransition = { _ ->
            if (isTopLevelSwitch()) {
                fadeIn(animationSpec = tween(220)) + slideInVertically(animationSpec = topLevelEnterSpec) { topLevelSlideOffsetPx }
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        },
        predictivePopExitTransition = { _ ->
            if (isTopLevelSwitch()) {
                fadeOut(animationSpec = tween(220))
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    ) {
        composable(Screen.Discover.route) {
            DiscoverScreen(onOpenNavigation = onOpenNavigation)
        }
        composable(Screen.Library.route) {
            LibraryScreen(
                viewModel = libraryViewModel,
                onOpenNavigation = onOpenNavigation,
                onOpenManualImport = onOpenManualImport,
                onNavigateToSearch = {
                    libraryViewModel.triggerSearchFocus()
                    navController.navigate(Screen.Search.route) { launchSingleTop = true }
                },
                onNavigateToDetail = { bookmarkId ->
                    navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                        launchSingleTop = true
                    }
                },
                showRadialMenu = !discoverPreviewEnabled
            )
        }
        composable(Screen.Search.route) {
            if (discoverPreviewEnabled) {
                SearchScreen(
                    viewModel = libraryViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTagSelection = {
                        navController.navigate(Screen.TagSelection.route)
                    },
                    onNavigateToDetail = { bookmarkId ->
                        navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                            launchSingleTop = true
                        }
                    },
                    bottomContentPadding = HeaderDefaults.FloatingNavigationClearance
                )
            } else {
                SearchScreen(
                    viewModel = libraryViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToTagSelection = {
                        navController.navigate(Screen.TagSelection.route)
                    },
                    onNavigateToDetail = { bookmarkId ->
                        navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
        composable(
            route = Screen.BookmarkDetail.route,
            arguments = listOf(
                navArgument("bookmarkId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bookmarkId = backStackEntry.arguments?.getString("bookmarkId").orEmpty()
            BookmarkDetailScreen(
                bookmarkId = bookmarkId,
                viewModel = libraryViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToAiReview = { id ->
                    if (navController.currentBackStackEntry == backStackEntry) {
                        navController.navigate(Screen.BookmarkAiReview.createRoute(id)) { launchSingleTop = true }
                    }
                },
                onNavigateToAiSettings = dropUnlessResumed {
                    navController.navigate(Screen.AiAnalysis.route) { launchSingleTop = true }
                }
            )
        }
        composable(
            route = Screen.BookmarkAiReview.route,
            arguments = listOf(
                navArgument("bookmarkId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bookmarkId = backStackEntry.arguments?.getString("bookmarkId").orEmpty()
            BookmarkAiReviewScreen(
                bookmarkId = bookmarkId,
                viewModel = libraryViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToAiAnalysis = dropUnlessResumed {
                    navController.navigate(Screen.AiAnalysis.route) { launchSingleTop = true }
                },
                onNavigateToPrivateContent = dropUnlessResumed {
                    navController.navigate(Screen.PrivateContent.route) { launchSingleTop = true }
                },
                onNavigateToRss = { navController.navigate(Screen.RssSettings.route) },
                onNavigateToStorage = { navController.navigate(Screen.Storage.route) },
                onNavigateToDebug = {
                    navController.navigate(Screen.SettingsDebug.route)
                }
            )
        }
        composable(Screen.AiAnalysis.route) {
            AiAnalysisScreen(
                onNavigateBack = dropUnlessResumed { navController.popBackStack() },
                onNavigateToGenerateContentRequest = dropUnlessResumed {
                    navController.navigate(Screen.GenerateContentRequest.route) { launchSingleTop = true }
                }
            )
        }
        composable(Screen.RssSettings.route) {
            me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Storage.route) {
            me.bubblespark.tidilo.ui.screens.settings.StorageScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.PrivateContent.route) {
            me.bubblespark.tidilo.ui.screens.settings.PrivateSettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.SettingsDebug.route) {
            SettingsDebugScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.GenerateContentRequest.route) {
            GenerateContentRequestScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.TagSelection.route) {
            TagSelectionScreen(
                viewModel = libraryViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Notifications.route) {
            me.bubblespark.tidilo.ui.screens.notifications.NotificationScreen(
                viewModel = importViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { bookmarkId ->
                    navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Trash.route) {
            me.bubblespark.tidilo.ui.screens.trash.TrashScreen(
                onNavigateBack = dropUnlessResumed { navController.popBackStack() }
            )
        }
    }
}
