package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainTabHeaderTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun actionCentersStayFixedAcrossRootAndDrillDownTitles(): Unit = runBlocking {
        val context = composeRule.activity
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context.applicationContext).also { it.awaitReady() } }
        try {
            val vm = library.viewModel()
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { LibraryScreen(vm) }
            }
            fun await(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) {
                composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val centers = linkedMapOf<String, Float>()
            val viewStyle = context.getString(R.string.library_view_mode_menu_desc)
            // Short titles only: an overflowing title starts an infinite marquee.
            for (title in listOf<String?>(null, "IGN", "IGN PC Articles")) {
                composeRule.runOnIdle {
                    vm.setFilterMode(if (title == null) LibraryFilterMode.NEWEST_FIRST else LibraryFilterMode.RSS)
                    vm.selectRssFeedFilter(title?.let { RssFeedEntity("header-test", "https://header.invalid/rss", it, enabled = false) })
                }
                val expectedTitle = title ?: context.getString(R.string.library_header_title)
                try {
                    await(hasText(expectedTitle))
                    await(hasContentDescription(viewStyle))
                } catch (error: Throwable) {
                    throw AssertionError("Missing title or view style action for $expectedTitle", error)
                }
                val action = composeRule.onAllNodes(hasContentDescription(viewStyle), useUnmergedTree = true).fetchSemanticsNodes().first()
                centers[expectedTitle] = action.boundsInRoot.center.y
            }
            assertEquals("Titles must not move the view style action vertically: $centers", centers.values.min(), centers.values.max(), 1f)
        } finally {
            caches.close()
            library.close()
        }
    }
}
