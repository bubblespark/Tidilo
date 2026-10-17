package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import org.junit.Assert.assertEquals
import org.junit.Test

class TidiloNavGraphTest {

    @Test
    fun getRouteDepth_returnsCorrectDepths() {
        // Level 0: Bottom Navigation tabs
        assertEquals(0, getRouteDepth(Screen.Library.route))
        assertEquals(0, getRouteDepth(Screen.Search.route))
        assertEquals(0, getRouteDepth(Screen.Queue.route))

        // Level 1: Primary sub-screens
        assertEquals(1, getRouteDepth(Screen.BookmarkDetail.route))
        assertEquals(1, getRouteDepth(Screen.BookmarkDetail.createRoute("123")))
        assertEquals(1, getRouteDepth(Screen.TagSelection.route))
        assertEquals(1, getRouteDepth(Screen.Settings.route))

        // Level 2: Nested sub-screens
        assertEquals(2, getRouteDepth(Screen.BookmarkAiReview.route))
        assertEquals(2, getRouteDepth(Screen.BookmarkAiReview.createRoute("123")))
        assertEquals(2, getRouteDepth(Screen.NsfwContents.route))
        assertEquals(2, getRouteDepth(Screen.SettingsDebug.route))
        assertEquals(2, getRouteDepth(Screen.GenerateContentRequest.route))

        // Unknown / null
        assertEquals(-1, getRouteDepth(null))
        assertEquals(-1, getRouteDepth("unknown_route"))
    }

    @Test
    fun getSlideDirection_bottomNavTabs_slidesBasedOnTabIndex() {
        // Library (0) -> Search (1): moves to right tab -> slide towards Start (left)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getSlideDirection(
                initialRoute = Screen.Library.route,
                targetRoute = Screen.Search.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // Search (1) -> Library (0): moves to left tab -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getSlideDirection(
                initialRoute = Screen.Search.route,
                targetRoute = Screen.Library.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // Search (1) -> Queue (2): moves to right tab -> slide towards Start (left)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getSlideDirection(
                initialRoute = Screen.Search.route,
                targetRoute = Screen.Queue.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )
    }

    @Test
    fun getSlideDirection_forwardHierarchy_slidesTowardsStart() {
        // Settings (1) -> NSFW Contents (2): deeper -> slide towards Start
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getSlideDirection(
                initialRoute = Screen.Settings.route,
                targetRoute = Screen.NsfwContents.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // Settings (1) -> GenerateContentRequest (2): deeper -> slide towards Start
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getSlideDirection(
                initialRoute = Screen.Settings.route,
                targetRoute = Screen.GenerateContentRequest.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // Library (0) -> BookmarkDetail (1): deeper -> slide towards Start
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getSlideDirection(
                initialRoute = Screen.Library.route,
                targetRoute = Screen.BookmarkDetail.createRoute("42"),
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )
    }

    @Test
    fun getSlideDirection_backwardHierarchy_alwaysSlidesTowardsEnd() {
        // NSFW Contents (2) -> Settings (1): returning up -> slide towards End (right)
        // Even when called with defaultDirection = Start (as in rapid gesture back during enterTransition)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getSlideDirection(
                initialRoute = Screen.NsfwContents.route,
                targetRoute = Screen.Settings.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // GenerateContentRequest (2) -> Settings (1): returning up -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getSlideDirection(
                initialRoute = Screen.GenerateContentRequest.route,
                targetRoute = Screen.Settings.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )

        // BookmarkDetail (1) -> Library (0): returning up -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getSlideDirection(
                initialRoute = Screen.BookmarkDetail.createRoute("42"),
                targetRoute = Screen.Library.route,
                defaultDirection = AnimatedContentTransitionScope.SlideDirection.Start
            )
        )
    }
}
