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
        assertTrue(isBottomNavTabSwitch(Screen.Search.route, Screen.Queue.route))
        assertTrue(isBottomNavTabSwitch(Screen.Queue.route, Screen.Library.route))

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

        // Search (1) -> Queue (2): moves to right tab -> slide towards Start (left)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.Start,
            getTabSlideDirection(
                initialRoute = Screen.Search.route,
                targetRoute = Screen.Queue.route
            )
        )

        // Queue (2) -> Search (1): moves to left tab -> slide towards End (right)
        assertEquals(
            AnimatedContentTransitionScope.SlideDirection.End,
            getTabSlideDirection(
                initialRoute = Screen.Queue.route,
                targetRoute = Screen.Search.route
            )
        )
    }
}
