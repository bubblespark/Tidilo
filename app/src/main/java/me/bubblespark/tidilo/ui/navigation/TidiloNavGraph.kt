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
import me.bubblespark.tidilo.ui.screens.queue.QueueScreen

import me.bubblespark.tidilo.ui.screens.queue.QueueViewModel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.screens.library.TagSelectionScreen

import androidx.navigation.NavType
import androidx.navigation.navArgument
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.screens.settings.SettingsDebugScreen
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestScreen

private val BottomNavTabOrder = listOf(
    Screen.Library.route,
    Screen.Search.route,
    Screen.Queue.route
)

private fun getBottomNavTabIndex(route: String?): Int {
    val idx = BottomNavTabOrder.indexOf(route)
    return if (idx >= 0) idx else -1
}

private fun getSlideDirection(
    initialRoute: String?,
    targetRoute: String?,
    defaultDirection: AnimatedContentTransitionScope.SlideDirection
): AnimatedContentTransitionScope.SlideDirection {
    val initialIndex = getBottomNavTabIndex(initialRoute)
    val targetIndex = getBottomNavTabIndex(targetRoute)
    return if (initialIndex != -1 && targetIndex != -1) {
        if (targetIndex > initialIndex) {
            AnimatedContentTransitionScope.SlideDirection.Start
        } else {
            AnimatedContentTransitionScope.SlideDirection.End
        }
    } else {
        defaultDirection
    }
}

@Composable
fun TidiloNavGraph(
    navController: NavHostController,
    queueViewModel: QueueViewModel,
    sharedUrl: String? = null,
    libraryViewModel: LibraryViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = if (sharedUrl != null) Screen.Queue.route else Screen.Library.route,
        modifier = modifier,
        enterTransition = {
            val direction = getSlideDirection(
                initialRoute = initialState.destination.route,
                targetRoute = targetState.destination.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
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
            val direction = getSlideDirection(
                initialRoute = initialState.destination.route,
                targetRoute = targetState.destination.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
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
            val direction = getSlideDirection(
                initialRoute = initialState.destination.route,
                targetRoute = targetState.destination.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.End
            )
            fadeIn(animationSpec = tween(280, easing = EaseInOutCubic)) +
                    slideIntoContainer(
                        towards = direction,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
        },
        popExitTransition = {
            val direction = getSlideDirection(
                initialRoute = initialState.destination.route,
                targetRoute = targetState.destination.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.End
            )
            fadeOut(animationSpec = tween(200)) +
                    slideOutOfContainer(
                        towards = direction,
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
                    if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                        navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId))
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
                    if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                        navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId))
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
                    if (navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                        navController.navigate(Screen.BookmarkAiReview.createRoute(id))
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
                onNavigateToGenerateContentRequest = {
                    navController.navigate(Screen.GenerateContentRequest.route)
                },
                onNavigateToNsfwContents = {
                    navController.navigate(Screen.NsfwContents.route)
                },
                onNavigateToDebug = {
                    navController.navigate(Screen.SettingsDebug.route)
                }
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
        composable(Screen.Queue.route) {
            QueueScreen(
                viewModel = queueViewModel,
                initialSharedUrl = sharedUrl
            )
        }
    }
}
