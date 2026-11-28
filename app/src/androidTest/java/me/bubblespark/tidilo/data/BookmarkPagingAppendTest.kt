package me.bubblespark.tidilo.data

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Rule
import org.junit.Test

/** The Library grid pages past the repository's initial load of 60 bookmarks. */
class BookmarkPagingAppendTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun pagingAppendsBeyondTheInitialLoad(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = System.currentTimeMillis()
        val bookmarks = List(75) { index ->
            BookmarkEntity("append-$index", "Grid card $index", "https://append.invalid/$index", "Summary",
                createdAt = now - index, updatedAt = now)
        }
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) {
            InMemoryLibrary(context).also {
                it.awaitReady()
                it.db.bookmarkDao().insertAll(bookmarks)
            }
        }
        try {
            val model = library.viewModel()
            composeRule.runOnUiThread {
                model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                model.setViewMode(LibraryViewMode.GRID)
            }
            composeRule.setContent {
                ViewportBox(360, 780, 1.15f) {
                    TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        LibraryScreen(viewModel = model)
                    }
                }
            }
            awaitTitle(0)
            // Newest first leads with its full-span scope row, so bookmark N is lazy item N + 1.
            scrollToIndex(60)
            // Bookmark 60 lies beyond the initial load, so only an appended page can supply it.
            awaitTitle(60, timeoutMillis = 10_000)
            scrollToIndex(75)
            // A poster repeats the title on its plate; the caption below it is the last node.
            composeRule.onAllNodesWithText("Grid card 74", useUnmergedTree = true).onLast().assertIsDisplayed()
        } finally {
            caches.close()
            library.close()
        }
    }

    private fun awaitTitle(index: Int, timeoutMillis: Long = 8_000) {
        val matcher: SemanticsMatcher = hasText("Grid card $index")
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToIndex(index: Int) {
        composeRule.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(index)
        composeRule.waitForIdle()
    }
}
