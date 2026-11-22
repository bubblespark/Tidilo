package me.bubblespark.tidilo

import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchFocusRequestTracker
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    @Test
    fun testDefaultTagValuesAreNone() {
        val noneTag = Tag.create(null, " ")
        assertEquals("None", noneTag.category)
        assertEquals("None", noneTag.subcategory)
    }

    @Test
    fun testTaxonomyLocalizationAndLookup() {
        assertEquals("記事", me.bubblespark.tidilo.data.Category.ARTICLE.getLabel(isJapanese = true))
        assertEquals("Article", me.bubblespark.tidilo.data.Category.ARTICLE.getLabel(isJapanese = false))
        assertEquals(me.bubblespark.tidilo.data.Category.ARTICLE, me.bubblespark.tidilo.data.Category.fromId("記事"))
        assertEquals(me.bubblespark.tidilo.data.Category.ARTICLE, me.bubblespark.tidilo.data.Category.fromId("Article"))

        assertEquals("技術・IT", me.bubblespark.tidilo.data.Subcategory.TECH.getLabel(isJapanese = true))
        assertEquals("Tech", me.bubblespark.tidilo.data.Subcategory.TECH.getLabel(isJapanese = false))
        assertEquals(me.bubblespark.tidilo.data.Subcategory.TECH, me.bubblespark.tidilo.data.Subcategory.fromId("技術・IT"))
        assertEquals(me.bubblespark.tidilo.data.Subcategory.TECH, me.bubblespark.tidilo.data.Subcategory.fromId("Tech"))
    }

    @Test
    fun testSearchHistoryManagementRequirements() {
        var history = emptyList<String>()

        // 1. History stores the entire search query as a single entry (not split by word/tag)
        history = LibraryViewModel.updateHistoryList(history, "Kotlin #Article #Tech")
        assertEquals(1, history.size)
        assertEquals("Kotlin #Article #Tech", history[0])

        // 2. Newest first (latest entry prepended to the head)
        history = LibraryViewModel.updateHistoryList(history, "Android Compose")
        assertEquals(2, history.size)
        assertEquals("Android Compose", history[0])
        assertEquals("Kotlin #Article #Tech", history[1])

        // 3. Exact duplicates are moved to head rather than duplicated
        history = LibraryViewModel.updateHistoryList(history, "Kotlin #Article #Tech")
        assertEquals(2, history.size)
        assertEquals("Kotlin #Article #Tech", history[0])
        assertEquals("Android Compose", history[1])

        // 4. Maintain up to 10 entries max
        for (i in 1..15) {
            history = LibraryViewModel.updateHistoryList(history, "Query $i")
        }
        assertEquals(10, history.size)
        assertEquals("Query 15", history[0]) // Newest query at index 0
        assertEquals("Query 6", history[9])  // 10th query at index 9
    }

    @Test
    fun testSearchScreenRoute() {
        assertEquals("search", me.bubblespark.tidilo.ui.navigation.Screen.Search.route)
        assertEquals("Search", me.bubblespark.tidilo.ui.navigation.Screen.Search.title)
    }

    @Test
    fun testTagSelectionRoute() {
        assertEquals("tag_selection", me.bubblespark.tidilo.ui.navigation.Screen.TagSelection.route)
        assertEquals("Tag Selection", me.bubblespark.tidilo.ui.navigation.Screen.TagSelection.title)
    }

    @Test
    fun testEntityNameSortingAlphabetBeforeJapanese() {
        val list = listOf("機械学習", "Zebra", "Apple", "テスト", "google", "あいうえお")
        fun compareEntityNames(a: String, b: String): Int {
            val aFirst = a.firstOrNull()
            val bFirst = b.firstOrNull()
            val aIsAscii = aFirst != null && aFirst.code < 128
            val bIsAscii = bFirst != null && bFirst.code < 128
            return when {
                aIsAscii && !bIsAscii -> -1
                !aIsAscii && bIsAscii -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
        }

        val sorted = list.sortedWith { a, b -> compareEntityNames(a, b) }
        // Alphabetical (case-insensitive) comes first: Apple, google, Zebra
        assertEquals("Apple", sorted[0])
        assertEquals("google", sorted[1])
        assertEquals("Zebra", sorted[2])
        // Japanese comes behind alphabet
        assertTrue(sorted[3] == "あいうえお" || sorted[3] == "テスト" || sorted[3] == "機械学習")
        assertEquals("あいうえお", sorted[3])
        assertEquals("テスト", sorted[4])
        assertEquals("機械学習", sorted[5])
    }

    @Test
    fun testPrivateContentScreenRoute() {
        assertEquals("settings_private", me.bubblespark.tidilo.ui.navigation.Screen.PrivateContent.route)
        assertEquals("Private Content", me.bubblespark.tidilo.ui.navigation.Screen.PrivateContent.title)
    }

    @Test
    fun testPrivateFilteringLogic() {
        val mixedBookmarks = listOf(
            BookmarkItem(id = "1", title = "Clean Tech", url = "https://example.com/1", description = "", tag = Tag.create("Tech", "Tech"), isPrivate = false),
            BookmarkItem(id = "2", title = "Adult Doujin", url = "https://example.com/2", description = "", tag = Tag.create("Media", "Adult"), isPrivate = true)
        )

        // When Private content is visible
        val visibleWhenTrue = mixedBookmarks.filter { true }
        assertEquals(2, visibleWhenTrue.size)

        // When Private content is hidden
        val visibleWhenFalse = mixedBookmarks.filter { !it.isPrivate }
        assertEquals(1, visibleWhenFalse.size)
        assertEquals("Clean Tech", visibleWhenFalse[0].title)
    }

    @Test
    fun testSearchFocusRequestTrackerRetainsPreNavigationRequestUntilConsumed() {
        val tracker = SearchFocusRequestTracker()
        assertEquals(0L, tracker.trigger.value)

        // Triggered before collector is attached (e.g., before navigating to SearchScreen)
        val token = tracker.requestFocus()
        assertEquals(1L, token)
        assertEquals(1L, tracker.trigger.value)

        // Consumed once SearchScreen finishes focusing
        assertTrue(tracker.consumeFocus(token))
        assertEquals(0L, tracker.trigger.value)

        // Subsequent duplicate consume is a no-op (e.g., returning from BookmarkDetailScreen)
        assertFalse(tracker.consumeFocus(token))
        assertEquals(0L, tracker.trigger.value)
    }

    @Test
    fun testSearchFocusRequestTrackerPreservesNewerTokenOnStaleConsume() {
        val tracker = SearchFocusRequestTracker()
        val firstToken = tracker.requestFocus()
        val secondToken = tracker.requestFocus()

        assertEquals(1L, firstToken)
        assertEquals(2L, secondToken)
        assertEquals(2L, tracker.trigger.value)

        // Stale consumption of firstToken must not wipe secondToken
        assertFalse(tracker.consumeFocus(firstToken))
        assertEquals(2L, tracker.trigger.value)

        // Consuming current secondToken resets to 0L
        assertTrue(tracker.consumeFocus(secondToken))
        assertEquals(0L, tracker.trigger.value)
    }

    @Test
    fun testSearchFocusRequestTrackerMonotonicAcrossConsumptionCycles() {
        val tracker = SearchFocusRequestTracker()
        val firstToken = tracker.requestFocus()
        assertEquals(1L, firstToken)
        assertEquals(1L, tracker.trigger.value)

        assertTrue(tracker.consumeFocus(firstToken))
        assertEquals(0L, tracker.trigger.value)

        // Across consumption cycles, subsequent request gets a strictly higher monotonic token
        val secondToken = tracker.requestFocus()
        assertEquals(2L, secondToken)
        assertEquals(2L, tracker.trigger.value)

        // Delayed/stale consume of firstToken must not clear secondToken
        assertFalse(tracker.consumeFocus(firstToken))
        assertEquals(2L, tracker.trigger.value)

        assertTrue(tracker.consumeFocus(secondToken))
        assertEquals(0L, tracker.trigger.value)
    }
}
