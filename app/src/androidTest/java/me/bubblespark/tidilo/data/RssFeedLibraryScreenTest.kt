package me.bubblespark.tidilo.data

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test

class RssFeedLibraryScreenTest {
    private val host = HeaderTestHost()
    private val instrumentation = host.instrumentation
    private val context = host.context
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun text(value: String) = withTimeout(5000) {
        while (find { it.text?.toString()?.contains(value) == true } == null) delay(50)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private suspend fun click(action: Int = AccessibilityNodeInfo.ACTION_CLICK, predicate: (AccessibilityNodeInfo) -> Boolean) = withTimeout(5000) {
        while (true) {
            var node = find(predicate)
            while (node != null && if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) !node.isLongClickable else !node.isClickable) node = node.parent
            if (node?.performAction(action) == true) return@withTimeout
            delay(50)
        }
    }

    private suspend fun scrollToDeck(
        activity: ComponentActivity,
        feed: RssFeedEntity,
        expectedIndex: Int? = null,
        detail: String = feed.title
    ) {
        val scrolled = withTimeoutOrNull(5000) {
            var found = false
            while (!found) {
                host.main {
                    for (node in host.nodes(activity, includeOtherWindows = false)) {
                        val index = node.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(feed.id)
                        val scroll = node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action
                        if (index != null && index >= 0 &&
                            (expectedIndex == null || index == expectedIndex) &&
                            scroll?.invoke(index) == true
                        ) {
                            found = true
                            break
                        }
                    }
                }
                if (!found) delay(30)
            }
            true
        }
        assertTrue("Could not scroll to feed deck. $detail", scrolled == true)
    }

    private fun visibleDeckForTitle(title: SemanticsNode, feed: RssFeedEntity): SemanticsNode? {
        if (!host.text(title, feed.title)) return null
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

    private suspend fun assertDeckOrderAndLayout(
        activity: ComponentActivity,
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
        scrollToDeck(activity, feeds.first(), expectedIndex = start, detail = order)

        var bounds = emptyMap<String, Rect>()
        withTimeoutOrNull(5000) {
            while (bounds.size != feeds.size) {
                host.main {
                    val nodes = host.nodes(activity, includeOtherWindows = false)
                    val hasCurrentOrder = nodes.any { node ->
                        val indexForKey = node.config.getOrNull(SemanticsProperties.IndexForKey)
                        indexForKey != null && feeds.map { indexForKey(it.id) } == indices
                    }
                    if (!hasCurrentOrder) return@main
                    bounds = feeds.mapNotNull { feed ->
                        val deck = nodes.firstNotNullOfOrNull { visibleDeckForTitle(it, feed) }
                            ?: return@mapNotNull null
                        val origin = deck.layoutInfo.coordinates.positionInRoot()
                        feed.title to Rect(origin.x, origin.y, origin.x + deck.size.width, origin.y + deck.size.height)
                    }.toMap()
                }
                if (bounds.size != feeds.size) delay(30)
            }
        }
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
        val db = AppDatabase.getInstance(context)
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
        val oldDiscoverer = DomainIconManager.htmlDiscoverer
        val oldDownloader = DomainIconManager.httpDownloader
        var activity: ComponentActivity? = null
        var vm: LibraryViewModel? = null
        var oldFilter = LibraryFilterMode.NEWEST_FIRST
        var oldView = LibraryViewMode.LIST
        try {
            for (feed in allFeeds.asReversed()) db.rssDao().putFeed(feed)
            db.bookmarkDao().insertAll(articles)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
            val icon = android.graphics.Bitmap.createBitmap(180, 180, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(icon).drawCircle(90f, 90f, 65f,
                android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
            val iconBytes = java.io.ByteArrayOutputStream().use {
                icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                icon.recycle()
                it.toByteArray()
            }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(iconBytes, true, false) }
            val screen = host.launch().also { activity = it }
            lateinit var model: LibraryViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(screen)[LibraryViewModel::class.java]
                oldFilter = model.filterMode.value
                oldView = model.viewMode.value
                vm = model
                model.selectRssFeedFilter(null)
                model.setFilterMode(LibraryFilterMode.RSS)
                model.setViewMode(LibraryViewMode.LIST)
                screen.setContent { MaterialTheme { LibraryScreen(model) } }
            }
            val withLeading = assertDeckOrderAndLayout(screen, model, decks, leading, hasLeadingFeed = true)
            db.bookmarkDao().deleteById(leading.id)
            val withoutLeading = assertDeckOrderAndLayout(screen, model, decks, leading, hasLeadingFeed = false)
            assertEquals("Removing the preceding deck must exercise the other starting column",
                withLeading.first() - 1, withoutLeading.first())
            val alphaIndex = if (withoutLeading.first() % 2 == 0) {
                db.bookmarkDao().insertAll(listOf(articles.first { it.rssFeedId == leading.id }))
                withLeading.first()
            } else {
                withoutLeading.first()
            }
            screenshot("issue311-feed-grid.png")
            assertNull(find { it.text?.toString() == alphaArticle.title })
            scrollToDeck(screen, a, expectedIndex = alphaIndex)
            click { it.text?.toString()?.contains(a.title) == true }
            text(alphaArticle.title)
            text(screen.resources.getQuantityString(R.plurals.rss_subtitle_bookmarks, 1, 1))
            screenshot("issue311-feed-detail.png")
            assertNull(find { it.text?.toString() == betaArticle.title })
            instrumentation.runOnMainSync { model.setViewMode(LibraryViewMode.GRID) }
            text(alphaArticle.title)
            click(AccessibilityNodeInfo.ACTION_LONG_CLICK) { it.text?.toString()?.contains(alphaArticle.title) == true }
            text(alphaArticle.description)
            instrumentation.runOnMainSync { screen.onBackPressedDispatcher.onBackPressed() }
            text(alphaArticle.title)
            click { it.contentDescription?.toString() == screen.getString(R.string.rss_back_to_feeds) }
            host.await(screen) { model.selectedRssFeedFilter.value == null && visibleDeckForTitle(it, a) != null }
            scrollToDeck(screen, b, expectedIndex = alphaIndex + 1)
            host.await(screen) { model.selectedRssFeedFilter.value == null && visibleDeckForTitle(it, b) != null }
            withContext(AndroidUiDispatcher.Main) {
                val nodes = host.nodes(screen, includeOtherWindows = false)
                val alpha = nodes.firstNotNullOfOrNull { visibleDeckForTitle(it, a) }
                if (alpha != null) {
                    val overview = nodes.first {
                        it.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(b.id) == alphaIndex + 1
                    }
                    overview.config[SemanticsActions.ScrollByOffset](Offset(0f, alpha.boundsInRoot.bottom + 1f))
                }
                val scrolled = host.nodes(screen, includeOtherWindows = false)
                assertNull("Alpha should be out of view before opening Beta",
                    scrolled.firstNotNullOfOrNull { visibleDeckForTitle(it, a) })
                val beta = requireNotNull(scrolled.firstNotNullOfOrNull { visibleDeckForTitle(it, b) })
                assertTrue(beta.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            text(betaArticle.title)
            assertNull(find { it.text?.toString() == alphaArticle.title })
            instrumentation.runOnMainSync { screen.onBackPressedDispatcher.onBackPressed() }
            host.await(screen) { model.selectedRssFeedFilter.value == null && visibleDeckForTitle(it, b) != null }
        } finally {
            try {
                host.main {
                    runCatching { vm?.setFilterMode(oldFilter) }
                        .onFailure { Log.w("RssFeedLibraryScreenTest", "Could not restore the library filter", it) }
                    runCatching { vm?.setViewMode(oldView) }
                        .onFailure { Log.w("RssFeedLibraryScreenTest", "Could not restore the library view mode", it) }
                    activity?.finish()
                }
            } finally {
                DomainIconManager.htmlDiscoverer = oldDiscoverer
                DomainIconManager.httpDownloader = oldDownloader
                for (feed in allFeeds) db.bookmarkDao().deleteById(feed.id)
                val placeholders = allFeeds.joinToString { "?" }
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM rss_feeds WHERE id IN ($placeholders)", allFeeds.map { it.id }.toTypedArray()
                )
            }
        }
    }
}
