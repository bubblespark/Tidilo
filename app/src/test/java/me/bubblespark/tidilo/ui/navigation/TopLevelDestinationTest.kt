package me.bubblespark.tidilo.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopLevelDestinationTest {

    @Test
    fun mapsTopLevelRoutes() {
        assertEquals(TopLevelDestination.DISCOVER, topLevelDestinationOf("discover"))
        assertEquals(TopLevelDestination.LIBRARY, topLevelDestinationOf("library"))
        assertEquals(TopLevelDestination.SEARCH, topLevelDestinationOf("search"))
    }

    @Test
    fun returnsNullForNonTopLevelAndNullRoutes() {
        assertNull(topLevelDestinationOf(Screen.BookmarkDetail.route))
        assertNull(topLevelDestinationOf(Screen.Settings.route))
        assertNull(topLevelDestinationOf(Screen.Trash.route))
        assertNull(topLevelDestinationOf(null))
    }

    @Test
    fun routesMatchScreenDefinitions() {
        assertEquals(Screen.Discover.route, TopLevelDestination.DISCOVER.route)
        assertEquals(Screen.Library.route, TopLevelDestination.LIBRARY.route)
        assertEquals(Screen.Search.route, TopLevelDestination.SEARCH.route)
    }

    @Test fun detailRouteCarriesTheOpeningSurface() {
        org.junit.Assert.assertEquals("bookmark_detail/a", Screen.BookmarkDetail.createRoute("a"))
        org.junit.Assert.assertEquals("bookmark_detail/a?surface=5",
            Screen.BookmarkDetail.createRoute("a", me.bubblespark.tidilo.data.engagement.EngagementSurface.DISCOVER_HERO))
    }
}
