package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Outgoing Library content stays drawn while its exit runs; frames are sampled on the paused test clock. */
class LibraryTransitionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = composeRule.activity

    @Test fun outgoingFeedAndDomainKeepTheirBookmarksUntilExitCompletes(): Unit = runBlocking {
        val feed = RssFeedEntity("transition-feed", "https://transition120.example/feed", "000 Transition feed", enabled = false)
        val domain = "000-transition120.example"
        val manual = BookmarkEntity("transition-manual", "Transition manual article", "https://$domain/article", "Summary",
            createdAt = 1, updatedAt = 1)
        val manualLead = BookmarkEntity("transition-manual-lead", "Transition manual lead", "https://$domain/lead", "Summary",
            createdAt = 4, updatedAt = 4)
        val rss = BookmarkEntity("transition-rss", "Transition RSS article", "https://transition120.example/article", "Summary",
            createdAt = 2, updatedAt = 2, rssFeedId = feed.id)
        // The leads sort first, so each sampled article sits below the tab group that returns over the outgoing list's top.
        val rssLead = BookmarkEntity("transition-rss-lead", "Transition RSS lead", "https://transition120.example/lead",
            "Summary", createdAt = 3, updatedAt = 3, rssFeedId = feed.id)
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) {
            InMemoryLibrary(context.applicationContext).also {
                it.awaitReady()
                it.db.rssDao().putFeed(feed)
                it.db.bookmarkDao().insertAll(listOf(manualLead, manual, rss, rssLead))
            }
        }
        try {
            val vm = library.viewModel()
            composeRule.runOnUiThread {
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.setViewMode(LibraryViewMode.LIST)
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    LibraryScreen(vm)
                }
            }
            val empty = context.getString(R.string.library_empty_title)

            // RSS feed → feed list.
            awaitText(feed.title)
            composeRule.runOnIdle { vm.selectRssFeedFilter(feed) }
            awaitText(rss.title)
            assertOutgoingStillDrawn(rss.title, accentText = "2 bookmarks", forbiddenText = empty) { vm.selectRssFeedFilter(null) }
            awaitAbsent(rss.title)

            // Domain list → domain.
            composeRule.runOnIdle { vm.setFilterMode(LibraryFilterMode.DOMAIN) }
            awaitText(domain)
            assertOutgoingStillDrawn(domain) { vm.selectDomainFilter(domain) }
            awaitText(manual.title)

            // Domain → domain list (back).
            assertOutgoingStillDrawn(manual.title) { context.onBackPressedDispatcher.onBackPressed() }
            awaitAbsent(manual.title)

            // Newest first → RSS mode switch.
            composeRule.runOnIdle {
                vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                vm.setViewMode(LibraryViewMode.GRID)
            }
            awaitText(manual.title)
            assertOutgoingStillDrawn(manual.title) { vm.setFilterMode(LibraryFilterMode.RSS) }
            awaitAbsent(manual.title)

            // An actually empty feed must still settle to the empty state.
            val emptyFeed = feed.copy(id = "transition-empty", url = "https://transition120.example/empty")
            composeRule.runOnIdle { vm.selectRssFeedFilter(emptyFeed) }
            awaitText(empty)
        } finally {
            caches.close()
            library.close()
        }
    }

    /**
     * Captures the settled frame, applies [change] on the paused clock, steps [EXIT_SAMPLE_MS] into the exit,
     * and asserts that [title] (and the accent of [accentText]) are still drawn and [forbiddenText] is absent.
     */
    private fun assertOutgoingStillDrawn(
        title: String,
        accentText: String? = null,
        forbiddenText: String? = null,
        change: () -> Unit
    ) {
        composeRule.waitForIdle()
        val titleBounds = pixelBounds(title)
        val accentBounds = accentText?.let(::pixelBounds)
        val before = capture()
        var during: Bitmap? = null
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.runOnUiThread(change)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(EXIT_SAMPLE_MS)
            during = capture()
            assertTextStillDrawn(before, during, titleBounds)
            accentBounds?.let { assertAccentStillDrawn(before, during, it, context.resources.displayMetrics.density) }
            if (forbiddenText != null) {
                assertTrue("Exit must not introduce a transient '$forbiddenText'", nodes(hasText(forbiddenText)).isEmpty())
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
            before.recycle()
            during?.recycle()
        }
    }

    private fun capture(): Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()

    private fun pixelBounds(text: String): Rect {
        // A poster repeats the title in light ink on its plate; the dark caption below it is the last node.
        val bounds = nodes(hasText(text)).maxBy { it.boundsInRoot.top }.boundsInRoot
        return Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun awaitText(text: String) = composeRule.waitUntil(10_000) { nodes(hasText(text)).isNotEmpty() }

    private fun awaitAbsent(text: String) = composeRule.waitUntil(10_000) { nodes(hasText(text)).isEmpty() }

    private companion object {
        /** About a quarter into the 160–180ms Library fades. */
        const val EXIT_SAMPLE_MS = 48L
    }
}
