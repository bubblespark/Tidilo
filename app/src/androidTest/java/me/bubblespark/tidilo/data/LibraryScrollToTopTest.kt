package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryStore
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.RepositoryLibraryStore
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LibraryScrollToTopTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldDiscoverer: ((String) -> DomainIconManager.HtmlDiscoveryResult)? = null
    private var oldDownloader: ((String) -> DomainIconManager.DownloadResult)? = null
    private var oldOgDownloader: (suspend (String) -> ByteArray?)? = null

    @Before
    fun stubMediaCaches() {
        oldDiscoverer = DomainIconManager.htmlDiscoverer
        oldDownloader = DomainIconManager.httpDownloader
        oldOgDownloader = OgImageManager.imageDownloader
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
    }

    @After
    fun restoreMediaCaches() {
        DomainIconManager.htmlDiscoverer = oldDiscoverer
        DomainIconManager.httpDownloader = oldDownloader
        OgImageManager.imageDownloader = oldOgDownloader
    }

    @Test
    fun titleReturnsAllEightActiveLayoutsToTheBeginning() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefsName = "scroll-top-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val id = "header142-${UUID.randomUUID().toString().take(8)}"
        val feeds = (0 until 10).map { index ->
            RssFeedEntity("$id-$index", "https://$id-$index.invalid/rss", "0000 Header feed $index", enabled = false)
        }
        val baseTime = System.currentTimeMillis()
        // 10 feeds (first has 10 items, others 1) + 10 manual domains (first has 10 items, others 1) = 38 rows total.
        val rssBookmarks = feeds.flatMapIndexed { group, feed ->
            val count = if (group == 0) 10 else 1
            (0 until count).map { item ->
                BookmarkEntity(
                    "$id-r-$group-$item",
                    "Header rss $group $item",
                    "https://$id-r-$group.invalid/$item",
                    "Summary",
                    createdAt = baseTime + group * 20L + item,
                    updatedAt = 1,
                    rssFeedId = feed.id
                )
            }
        }
        val manualBookmarks = (0 until 10).flatMap { group ->
            val count = if (group == 0) 10 else 1
            (0 until count).map { item ->
                BookmarkEntity(
                    "$id-m-$group-$item",
                    "Header manual $group $item",
                    "https://$id-0.invalid".takeIf { group == 0 }?.let { "$it/$item" } ?: "https://$id-$group.invalid/$item",
                    "Summary",
                    createdAt = baseTime + 1_000L + group * 20L + item,
                    updatedAt = 1
                )
            }
        }

        try {
            val repository = BookmarkRepository(db, autoInitialize = false)
            runBlocking {
                feeds.forEach { db.rssDao().putFeed(it) }
                db.bookmarkDao().insertAll(rssBookmarks + manualBookmarks)
                repository.retryInitializationAndAwait()
            }
            val store = RepositoryLibraryStore(
                repository,
                CollectionRepository(db),
                DomainPolicyStore.getInstance(context),
                prefs
            )
            val vm = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true))

            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    Box(Modifier.size(320.dp, 260.dp)) {
                        LibraryScreen(vm)
                    }
                }
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

            val scrollableMatcher = SemanticsMatcher("scrollable active layout") { node ->
                node.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
            }

            for ((filter, detail, view) in variants) {
                try {
                    composeRule.runOnUiThread {
                        vm.setFilterMode(filter)
                        vm.selectDomainFilter(if (filter == LibraryFilterMode.DOMAIN && detail) "$id-0.invalid" else null)
                        vm.selectRssFeedFilter(if (filter == LibraryFilterMode.RSS && detail) feeds.first() else null)
                        vm.setViewMode(view)
                    }
                    composeRule.waitForIdle()
                    composeRule.waitUntil(8000) {
                        composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true)[0]
                        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 600f) }

                    composeRule.waitUntil(5000) {
                        val nodes = composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes()
                        nodes.any { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: 0f) > 0f }
                    }

                    val title = when (filter) {
                        LibraryFilterMode.NEWEST_FIRST -> context.getString(R.string.library_header_title)
                        LibraryFilterMode.DOMAIN -> if (detail) "$id-0.invalid" else context.getString(R.string.library_header_title)
                        LibraryFilterMode.RSS -> if (detail) feeds.first().title else context.getString(R.string.rss_title)
                        LibraryFilterMode.COLLECTION -> context.getString(R.string.collection_title)
                    }
                    val titleMatcher = SemanticsMatcher("header title $title") { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true &&
                            generateSequence(node.parent) { it.parent }.none {
                                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
                            }
                    }
                    composeRule.waitUntil(5000) {
                        composeRule.onAllNodes(titleMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    val titleNode = composeRule.onNode(titleMatcher, useUnmergedTree = true).fetchSemanticsNode()
                    composeRule.runOnUiThread {
                        var clickable = titleNode
                        while (clickable.config.getOrNull(SemanticsActions.OnClick) == null && clickable.parent != null) {
                            clickable = requireNotNull(clickable.parent)
                        }
                        clickable.config[SemanticsActions.OnClick].action?.invoke()
                    }

                    composeRule.waitUntil(8000) {
                        val nodes = composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes()
                        nodes.any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() == 0f }
                    }
                    val settledAxis = composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true)
                        .fetchSemanticsNodes().first().config[SemanticsProperties.VerticalScrollAxisRange]
                    assertEquals("Active layout returns to the exact start: $filter/$detail/$view", 0f, settledAxis.value(), 0f)
                } catch (t: Throwable) {
                    throw AssertionError("Failed at layout $filter / detail=$detail / view=$view", t)
                }
            }
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }
}
