package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidiloNavGraphTest {

    @Test
    fun isBottomNavTabSwitch_returnsTrueOnlyWhenBothAreTabs() {
        // Tab to tab
        assertTrue(isBottomNavTabSwitch(Screen.Library.route, Screen.Search.route))
        assertTrue(isBottomNavTabSwitch(Screen.Search.route, Screen.Import.route))
        assertTrue(isBottomNavTabSwitch(Screen.Import.route, Screen.Library.route))

        // Tab to subscreen
        assertFalse(isBottomNavTabSwitch(Screen.Library.route, Screen.Settings.route))
        assertFalse(isBottomNavTabSwitch(Screen.Library.route, Screen.BookmarkDetail.route))

        // Subscreen to subscreen
        assertFalse(isBottomNavTabSwitch(Screen.Settings.route, Screen.NsfwContents.route))
        assertFalse(isBottomNavTabSwitch(Screen.Settings.route, Screen.GenerateContentRequest.route))

        // Null / Unknown
        assertFalse(isBottomNavTabSwitch(null, Screen.Library.route))
        assertFalse(isBottomNavTabSwitch(Screen.Library.route, null))
    }

    @Test
    fun getTabSlideDirection_slidesBasedOnPhysicalOrder() {
        // Library (0) -> Search (1): moves to right tab -> slide towards Start (left)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getTabSlideDirection(
                initialRoute = Screen.Library.route,
                targetRoute = Screen.Search.route
            )
        )

        // Search (1) -> Library (0): moves to left tab -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getTabSlideDirection(
                initialRoute = Screen.Search.route,
                targetRoute = Screen.Library.route
            )
        )

        // Search (1) -> Import (2): moves to right tab -> slide towards Start (left)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getTabSlideDirection(
                initialRoute = Screen.Search.route,
                targetRoute = Screen.Import.route
            )
        )

        // Import (2) -> Search (1): moves to left tab -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getTabSlideDirection(
                initialRoute = Screen.Import.route,
                targetRoute = Screen.Search.route
            )
        )
    }

    @Test
    fun bookmarkDetailRoute_resolvesToSameDestinationTemplate() {
        val route1 = Screen.BookmarkDetail.createRoute("item-1")
        val route2 = Screen.BookmarkDetail.createRoute("item-2")

        assertEquals("bookmark_detail/item-1", route1)
        assertEquals("bookmark_detail/item-2", route2)
        assertEquals("bookmark_detail/{bookmarkId}", Screen.BookmarkDetail.route)

        // Both routes map to the same base route prefix for single-top destination matching
        val baseTemplate = Screen.BookmarkDetail.route.substringBefore("/{")
        assertTrue(route1.startsWith(baseTemplate))
        assertTrue(route2.startsWith(baseTemplate))
    }

    @Test
    fun bookmarkAiReviewRoute_resolvesToSameDestinationTemplate() {
        val route = Screen.BookmarkAiReview.createRoute("item-1")
        assertEquals("bookmark_ai_review/item-1", route)
        assertEquals("bookmark_ai_review/{bookmarkId}", Screen.BookmarkAiReview.route)
    }
}
