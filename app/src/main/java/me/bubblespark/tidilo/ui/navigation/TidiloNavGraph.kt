package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.importscreen.ImportScreen
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
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestScreen

internal val BottomNavTabOrder = listOf(
    Screen.Library.route,
    Screen.Search.route,
    Screen.Import.route
)

internal fun isBottomNavTabSwitch(initialRoute: String?, targetRoute: String?): Boolean {
    return initialRoute in BottomNavTabOrder && targetRoute in BottomNavTabOrder
}

internal fun getTabSlideDirection(
    initialRoute: String?,
    targetRoute: String?
): AnimatedContentTransitionScope.SlideDirection {
    val initialIndex = BottomNavTabOrder.indexOf(initialRoute)
    val targetIndex = BottomNavTabOrder.indexOf(targetRoute)
    return if (targetIndex > initialIndex) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }
}

@Composable
fun TidiloNavGraph(
    navController: NavHostController,
    importViewModel: ImportViewModel,
    sharedUrl: String? = null,
    libraryViewModel: LibraryViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = if (sharedUrl != null) Screen.Import.route else Screen.Library.route,
        modifier = modifier,
        enterTransition = {
            val direction = if (isBottomNavTabSwitch(initialState.destination.route, targetState.destination.route)) {
                getTabSlideDirection(initialState.destination.route, targetState.destination.route)
            } else if (targetState.id == navController.previousBackStackEntry?.id) {
                AnimatedContentTransitionScope.SlideDirection.End
            } else {
                AnimatedContentTransitionScope.SlideDirection.Start
            }
            fadeIn(animationSpec = tween(280, easing = EaseInOutCubic)) +
                    slideIntoContainer(
                        towards = direction,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        exitTransition = {
            val direction = if (isBottomNavTabSwitch(initialState.destination.route, targetState.destination.route)) {
                getTabSlideDirection(initialState.destination.route, targetState.destination.route)
            } else if (targetState.id == navController.previousBackStackEntry?.id) {
                AnimatedContentTransitionScope.SlideDirection.End
            } else {
                AnimatedContentTransitionScope.SlideDirection.Start
            }
            fadeOut(animationSpec = tween(200)) +
                    slideOutOfContainer(
                        towards = direction,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(280, easing = EaseInOutCubic)) +
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200)) +
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        predictivePopEnterTransition = { _ ->
            fadeIn(animationSpec = tween(280, easing = EaseInOutCubic)) +
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        predictivePopExitTransition = { _ ->
            fadeOut(animationSpec = tween(200)) +
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        }
    ) {
        composable(Screen.Library.route) {
            LibraryScreen(
                viewModel = libraryViewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToDetail = { bookmarkId ->
                    navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Search.route) {
            SearchScreen(
                viewModel = libraryViewModel,
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
                    navController.navigate(Screen.BookmarkAiReview.createRoute(id)) {
                        launchSingleTop = true
                    }
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
                onNavigateToAiAnalysis = {
                    navController.navigate(Screen.AiAnalysis.route)
                },
                onNavigateToGenerateContentRequest = {
                    navController.navigate(Screen.GenerateContentRequest.route)
                },
                onNavigateToNsfwContents = {
                    navController.navigate(Screen.NsfwContents.route)
                },
                onNavigateToRss = { navController.navigate(Screen.RssSettings.route) },
                onNavigateToSummary = { navController.navigate(Screen.Summary.route) },
                onNavigateToDebug = {
                    navController.navigate(Screen.SettingsDebug.route)
                }
            )
        }
        composable(Screen.AiAnalysis.route) {
            AiAnalysisScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGenerateContentRequest = {
                    navController.navigate(Screen.GenerateContentRequest.route)
                },
                onNavigateToNsfwContents = {
                    navController.navigate(Screen.NsfwContents.route)
                }
            )
        }
        composable(Screen.RssSettings.route) {
            me.bubblespark.tidilo.ui.screens.settings.RssSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Summary.route) {
            me.bubblespark.tidilo.ui.screens.settings.SummaryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.NsfwContents.route) {
            me.bubblespark.tidilo.ui.screens.settings.NsfwSettingsScreen(
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
        composable(Screen.Import.route) {
            ImportScreen(
                viewModel = importViewModel,
                initialSharedUrl = sharedUrl,
                onNavigateToDetail = { bookmarkId ->
                    navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
