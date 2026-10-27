package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryScrollToTopTest {
    @Test fun titleReturnsAllEightActiveLayoutsToTheBeginning(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val id = "header142-${UUID.randomUUID()}"
        val feeds = (0 until 20).map { index ->
            RssFeedEntity("$id-$index", "https://$id-$index.invalid/rss", "0000 Header feed $index", enabled = false)
        }
        val rssBookmarks = feeds.flatMapIndexed { group, feed ->
            (0 until 20).map { item ->
                BookmarkEntity("$id-$group-$item", "Header article $group $item", "https://$id-$group.invalid/$item",
                    "Summary", createdAt = System.currentTimeMillis() + group * 20 + item, updatedAt = 1, rssFeedId = feed.id)
            }
        }
        val bookmarks = rssBookmarks + rssBookmarks.map { it.copy(id = "${it.id}-manual", url = "${it.url}/manual", rssFeedId = null) }
        feeds.forEach { db.rssDao().putFeed(it) }
        db.bookmarkDao().insertAll(bookmarks)
        val oldDiscoverer = DomainIconManager.htmlDiscoverer
        val oldDownloader = DomainIconManager.httpDownloader
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        lateinit var oldFilter: LibraryFilterMode
        lateinit var oldView: LibraryViewMode
        var oldDomain: String? = null
        var oldFeed: RssFeedEntity? = null
        try {
            host.main {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldFilter = vm.filterMode.value
                oldView = vm.viewMode.value
                oldDomain = vm.selectedDomainFilter.value
                oldFeed = vm.selectedRssFeedFilter.value
                activity.setContent { MaterialTheme { LibraryScreen(vm) } }
            }
            val variants = listOf(
                Triple(LibraryFilterMode.NEWEST_FIRST, false, LibraryViewMode.LIST),
                Triple(LibraryFilterMode.NEWEST_FIRST, false, LibraryViewMode.GRID),
                Triple(LibraryFilterMode.DOMAIN, false, LibraryViewMode.GRID),
                Triple(LibraryFilterMode.DOMAIN, true, LibraryViewMode.LIST),
                Triple(LibraryFilterMode.DOMAIN, true, LibraryViewMode.GRID),
                Triple(LibraryFilterMode.RSS, false, LibraryViewMode.GRID),
                Triple(LibraryFilterMode.RSS, true, LibraryViewMode.LIST),
                Triple(LibraryFilterMode.RSS, true, LibraryViewMode.GRID)
            )
            for ((filter, detail, view) in variants) {
                host.main {
                    vm.setFilterMode(filter)
                    vm.selectDomainFilter(if (filter == LibraryFilterMode.DOMAIN && detail) "$id-0.invalid" else null)
                    vm.selectRssFeedFilter(if (filter == LibraryFilterMode.RSS && detail) feeds.first() else null)
                    vm.setViewMode(view)
                }
                // Let outgoing AnimatedContent detach before selecting the active scroll semantics.
                delay(500)
                val body = host.await(activity) {
                    it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                        (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
                }
                host.main { assertTrue(body.config[SemanticsActions.ScrollBy].action?.invoke(0f, 600f) == true) }
                val axis = requireNotNull(body.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                withTimeout(5000) { while (axis.value() <= 0f) delay(30) }
                val title = when (filter) {
                    LibraryFilterMode.NEWEST_FIRST -> host.context.getString(R.string.library_header_title)
                    LibraryFilterMode.DOMAIN -> if (detail) "$id-0.invalid" else host.context.getString(R.string.library_header_title)
                    LibraryFilterMode.RSS -> if (detail) feeds.first().title else host.context.getString(R.string.rss_title)
                }
                val text = host.await(activity) { node ->
                    host.text(node, title) && generateSequence(node.parent) { it.parent }.none {
                        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                    }
                }
                host.main {
                    var clickable = text
                    while (clickable.config.getOrNull(SemanticsActions.OnClick) == null && clickable.parent != null) {
                        clickable = requireNotNull(clickable.parent)
                    }
                    assertNotNull("Title must expose its action for $filter/$detail/$view", clickable.config.getOrNull(SemanticsActions.OnClick))
                    assertTrue(clickable.config[SemanticsActions.OnClick].action?.invoke() == true)
                }
                withTimeout(8000) { while (axis.value() != 0f) delay(30) }
                assertEquals("Active layout returns to the exact start: $filter/$detail/$view", 0f, axis.value(), 0f)
            }
        } finally {
            host.main {
                activity.setContent { }
                vm.setFilterMode(oldFilter); vm.setViewMode(oldView)
                vm.selectDomainFilter(oldDomain); vm.selectRssFeedFilter(oldFeed)
                activity.finish()
            }
            bookmarks.forEach { db.bookmarkDao().deleteById(it.id) }
            feeds.forEach { db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(it.id)) }
            DomainIconManager.htmlDiscoverer = oldDiscoverer
            DomainIconManager.httpDownloader = oldDownloader
        }
    }
}
