package me.bubblespark.tidilo.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TidiloNavGraphTest {

    @Test
    fun notificationDestinationsNormalizeLegacyAndRejectUnknownRoutes() {
        assertEquals("notifications", normalizeNotificationDestination("import"))
        assertEquals("notifications", normalizeNotificationDestination("notifications"))
        assertEquals("library", normalizeNotificationDestination("library"))
        assertEquals("settings_rss", normalizeNotificationDestination("settings_rss"))
        assertEquals(null, normalizeNotificationDestination(null))
        assertEquals(null, normalizeNotificationDestination("bookmark_detail/private-id"))
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
