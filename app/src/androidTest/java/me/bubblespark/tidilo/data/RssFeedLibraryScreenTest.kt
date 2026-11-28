package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/** RSS feed decks in the Library, on an in-memory library with offline visual caches. */
class RssFeedLibraryScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity
    private val indexed = SemanticsMatcher.keyIsDefined(SemanticsProperties.IndexForKey)
    private val texts = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private fun nodes(matcher: SemanticsMatcher) = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    private fun hasTextValue(node: SemanticsNode, value: String) =
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    private fun absent(text: String): Boolean {
        composeRule.waitForIdle()
        return nodes(hasText(text)).isEmpty()
    }
    private fun awaitText(value: String) = composeRule.awaitNode(hasText(value, substring = true))

    private fun scrollToDeck(feed: RssFeedEntity, expectedIndex: Int? = null, detail: String = feed.title) {
        fun target() = nodes(hasScrollToIndexAction() and indexed).firstNotNullOfOrNull { node ->
            val index = node.config[SemanticsProperties.IndexForKey](feed.id)
            if (index >= 0 && (expectedIndex == null || index == expectedIndex)) node to index else null
        }
        runCatching { composeRule.waitUntil(5_000) { target() != null } }
            .onFailure { throw AssertionError("Could not scroll to feed deck. $detail", it) }
        val (list, index) = requireNotNull(target())
        composeRule.runOnUiThread { assertTrue(list.config[SemanticsActions.ScrollToIndex].action?.invoke(index) == true) }
        composeRule.waitForIdle()
    }

    private fun visibleDeckForTitle(title: SemanticsNode, feed: RssFeedEntity): SemanticsNode? {
        if (!hasTextValue(title, feed.title)) return null
        val ancestors = generateSequence(title) { it.parent }.toList()
        val gridIndex = ancestors.indexOfFirst {
            val index = it.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(feed.id)
            index != null && index >= 0
        }
        if (gridIndex < 0) return null
        return ancestors.take(gridIndex)
            .lastOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?.takeIf { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
    }

    private fun visibleDeck(feed: RssFeedEntity) = nodes(texts).firstNotNullOfOrNull { visibleDeckForTitle(it, feed) }

    private suspend fun assertDeckOrderAndLayout(
        vm: LibraryViewModel,
        feeds: List<RssFeedEntity>,
        leadingFeed: RssFeedEntity,
        hasLeadingFeed: Boolean
    ): List<Int> {
        val groups = withTimeout(5000) {
            vm.rssFeedGroups.first { groups ->
                feeds.all { feed -> groups.any { it.feed.id == feed.id } } &&
                    groups.any { it.feed.id == leadingFeed.id } == hasLeadingFeed
            }
        }
        val indices = feeds.map { feed -> groups.indexOfFirst { it.feed.id == feed.id } }
        val start = indices.min()
        val neighbors = (maxOf(0, start - 2) until minOf(groups.size, indices.max() + 3))
            .joinToString { "$it=${groups[it].feed.title}" }
        val order = "Fixture indices $indices. Neighboring feeds: $neighbors"
        assertEquals(order, (start..start + 2).toList(), indices)
        scrollToDeck(feeds.first(), expectedIndex = start, detail = order)

        fun deckBounds(): Map<String, Rect> {
            val hasCurrentOrder = nodes(indexed).any { node ->
                val indexForKey = node.config[SemanticsProperties.IndexForKey]
                feeds.map { indexForKey(it.id) } == indices
            }
            if (!hasCurrentOrder) return emptyMap()
            val decks = feeds.mapNotNull { feed -> visibleDeck(feed)?.let { feed.title to it } }
            var bounds = emptyMap<String, Rect>()
            composeRule.runOnUiThread {
                bounds = decks.associate { (title, deck) ->
                    val origin = deck.layoutInfo.coordinates.positionInRoot()
                    title to Rect(origin.x, origin.y, origin.x + deck.size.width, origin.y + deck.size.height)
                }
            }
            return bounds
        }
        var bounds = emptyMap<String, Rect>()
        runCatching { composeRule.waitUntil(5_000) { deckBounds().also { bounds = it }.size == feeds.size } }
        val layout = "$order. Deck bounds: $bounds"
        assertEquals(layout, feeds.size, bounds.size)
        val (alpha, beta, gamma) = feeds.map { bounds.getValue(it.title) }
        val startsInFirstColumn = start % 2 == 0
        val (left, right) = if (startsInFirstColumn) alpha to beta else beta to gamma
        assertTrue(layout, left.top == right.top && left.right <= right.left)
        assertTrue(layout, alpha.bottom <= gamma.top && alpha.left == gamma.left)
        return indices
    }

    @Test fun feedDeckOpensOnlyItsArticlesSupportsBothViewsAndReturnsWithHeaderAndSystemBack(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val label = id.take(8)
        fun feed(name: String, order: Int) = RssFeedEntity(
            "$id-$order", "https://issue311.example/$id/$order", "0000 RSS $label $name",
            enabled = false, iconUrl = "https://images311.example/$id/$order.png",
            siteUrl = "https://publisher311.example/"
        )
        val a = feed("Alpha", 3).copy(deleted = true)
        val b = feed("Beta", 2)
        val c = feed("Gamma", 1)
        val leading = feed("0 Lead", 0)
        val decks = listOf(a, b, c)
        // Leave enough rows below Beta for its row to scroll past Alpha.
        val trailing = List(12) { feed("Tail $it", 10 + it) }
        val allFeeds = decks + leading + trailing
        val now = System.currentTimeMillis()
        val articles = allFeeds.map { feed ->
            val title = feed.title.substringAfterLast(' ')
            BookmarkEntity(feed.id, "$title article $label", "https://article311.example/${feed.id}",
                "$title summary $label", createdAt = now, updatedAt = now, rssFeedId = feed.id)
        }
        val (alphaArticle, betaArticle) = articles
        val library = InMemoryLibrary(activity.applicationContext)
        val caches = OfflineVisualCaches()
        try {
            val iconBytes = tinyPng(4, 4, Color.BLUE)
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(iconBytes, true, false) }
            library.awaitReady()
            library.db.withTransaction {
                for (feed in allFeeds.asReversed()) library.db.rssDao().putFeed(feed)
                library.db.bookmarkDao().insertAll(articles)
            }
            val model = library.viewModel()
            composeRule.runOnUiThread {
                model.selectRssFeedFilter(null)
                model.setFilterMode(LibraryFilterMode.RSS)
                model.setViewMode(LibraryViewMode.LIST)
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { LibraryScreen(model) }
            }
            val withLeading = assertDeckOrderAndLayout(model, decks, leading, hasLeadingFeed = true)
            library.db.bookmarkDao().deleteById(leading.id)
            val withoutLeading = assertDeckOrderAndLayout(model, decks, leading, hasLeadingFeed = false)
            assertEquals("Removing the preceding deck must exercise the other starting column",
                withLeading.first() - 1, withoutLeading.first())
            val alphaIndex = if (withoutLeading.first() % 2 == 0) {
                library.db.bookmarkDao().insertAll(listOf(articles.first { it.rssFeedId == leading.id }))
                withLeading.first()
            } else {
                withoutLeading.first()
            }
            assertTrue(absent(alphaArticle.title))
            scrollToDeck(a, expectedIndex = alphaIndex)
            composeRule.clickNode(awaitText(a.title))
            awaitText(alphaArticle.title)
            awaitText(activity.resources.getQuantityString(R.plurals.rss_subtitle_bookmarks, 1, 1))
            assertTrue(absent(betaArticle.title))
            composeRule.runOnUiThread { model.setViewMode(LibraryViewMode.GRID) }
            val card = generateSequence(awaitText(alphaArticle.title)) { it.parent }
                .first { SemanticsActions.OnLongClick in it.config }
            composeRule.runOnUiThread { assertTrue(card.config[SemanticsActions.OnLongClick].action?.invoke() == true) }
            composeRule.awaitNode(hasTestTag("discover_peek_sheet"))
            // System Back reaches the Peek sheet's own window first and keeps the feed selected.
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            composeRule.waitUntil(8_000) { nodes(hasTestTag("discover_peek_sheet")).isEmpty() }
            awaitText(alphaArticle.title)
            composeRule.clickNode(composeRule.awaitNode(hasContentDescription(activity.getString(R.string.rss_back_to_feeds))))
            composeRule.waitUntil(5_000) { model.selectedRssFeedFilter.value == null && visibleDeck(a) != null }
            scrollToDeck(b, expectedIndex = alphaIndex + 1)
            composeRule.waitUntil(5_000) { model.selectedRssFeedFilter.value == null && visibleDeck(b) != null }
            val alpha = visibleDeck(a)
            if (alpha != null) {
                val overview = nodes(indexed).first {
                    it.config[SemanticsProperties.IndexForKey](b.id) == alphaIndex + 1
                }
                withContext(AndroidUiDispatcher.Main) {
                    overview.config[SemanticsActions.ScrollByOffset](Offset(0f, alpha.boundsInRoot.bottom + 1f))
                }
                composeRule.waitForIdle()
            }
            assertNull("Alpha should be out of view before opening Beta", visibleDeck(a))
            val beta = requireNotNull(visibleDeck(b))
            composeRule.runOnUiThread { assertTrue(beta.config[SemanticsActions.OnClick].action?.invoke() == true) }
            awaitText(betaArticle.title)
            assertTrue(absent(alphaArticle.title))
            composeRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitUntil(5_000) { model.selectedRssFeedFilter.value == null && visibleDeck(b) != null }
        } finally {
            caches.close()
            library.close()
        }
    }
}
