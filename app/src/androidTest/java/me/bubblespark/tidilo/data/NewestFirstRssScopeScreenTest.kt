package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Newest first "Include RSS" chip: scope, counts, full-span grid row, RSS cues, scroll reset. */
class NewestFirstRssScopeScreenTest {
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
    fun includeRssChipControlsNewestFirstScope() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefsName = "rss-scope-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val id = "scope267-${UUID.randomUUID().toString().take(8)}"
        val feed = RssFeedEntity(id, "https://$id.invalid/rss", "Scope feed", enabled = false)
        val base = System.currentTimeMillis() + 10_000_000L
        val manual = (0 until 6).map {
            BookmarkEntity("$id-m$it", "$id manual $it", "https://manual-$id.invalid/$it", "", createdAt = base + it, updatedAt = 1)
        }
        val rss = (0 until 6).map {
            BookmarkEntity(
                "$id-r$it", "$id rss $it", "https://rss-$id.invalid/$it", "",
                createdAt = base + 1_000 + it, updatedAt = 1, rssFeedId = feed.id, rssPublishedAt = 1
            )
        }
        val chipLabel = context.getString(R.string.library_include_rss_chip)
        // Library plates name an RSS article's source at the end of their merged description.
        val rssSource = ", " + context.getString(R.string.rss_title)
        val rssCard = SemanticsMatcher("RSS plate") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.endsWith(rssSource) } == true
        }
        val manualSubtitle = context.getString(R.string.library_subtitle_bookmarks, manual.size)
        val combinedSubtitle = context.getString(R.string.library_subtitle_bookmarks_with_rss, manual.size + rss.size)

        try {
            val repository = BookmarkRepository(db, autoInitialize = false)
            runBlocking {
                db.rssDao().putFeed(feed)
                db.bookmarkDao().insertAll(manual + rss)
                repository.retryInitializationAndAwait()
            }
            val store = RepositoryLibraryStore(
                repository,
                CollectionRepository(db),
                DomainPolicyStore.getInstance(context),
                prefs
            )
            val vm = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true)).apply {
                setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                setViewMode(LibraryViewMode.LIST)
                setIncludeRssInNewestFirst(false)
            }

            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    Box(Modifier.size(360.dp, 360.dp)) {
                        LibraryScreen(vm)
                    }
                }
            }

            val chipMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
                .and(hasAnyDescendant(hasText(chipLabel)))

            // OFF: manual-only list, chip unselected, manual subtitle.
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(chipMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNode(chipMatcher, useUnmergedTree = true).assertIsNotSelected()
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText("$id manual 5"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(hasText(manualSubtitle), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("$id rss 5", useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNode(rssCard, useUnmergedTree = true).assertDoesNotExist()

            // ON: combined list, RSS badge on untagged RSS rows, combined subtitle.
            composeRule.onNode(chipMatcher, useUnmergedTree = true).performClick()
            composeRule.waitUntil(5000) { vm.includeRssInNewestFirst.value }
            composeRule.onNode(chipMatcher, useUnmergedTree = true).assertIsSelected()
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText("$id rss 5"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(hasText(combinedSubtitle), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            // How many untagged RSS rows fit in the box depends on the device's font metrics.
            assertTrue(composeRule.onAllNodes(rssCard, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

            // Grid: the scope row spans the grid width, so the first card sits below it (one plate column fits 360dp).
            composeRule.runOnUiThread { vm.setViewMode(LibraryViewMode.GRID) }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText("$id rss 5"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(rssCard, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val chipBottom = composeRule.onNode(chipMatcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
            // A poster repeats the title on its plate, so a title can have two nodes.
            val first = composeRule.onAllNodesWithText("$id rss 5", useUnmergedTree = true).fetchSemanticsNodes().first()
            assertTrue("First card must start below the scope row", first.boundsInRoot.top >= chipBottom)

            // Scope change returns the active layout to the top.
            val scrollableMatcher = SemanticsMatcher("scrollable list/grid") {
                it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
            }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true)[0]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1500f) }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes()
                    .any { (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() ?: 0f) > 0f }
            }

            composeRule.runOnUiThread { vm.setIncludeRssInNewestFirst(false) }
            composeRule.waitUntil(8000) {
                val chipOff = composeRule.onAllNodes(chipMatcher, useUnmergedTree = true).fetchSemanticsNodes()
                    .any { it.config.getOrNull(SemanticsProperties.Selected) == false }
                val atTop = composeRule.onAllNodes(scrollableMatcher, useUnmergedTree = true).fetchSemanticsNodes()
                    .any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() == 0f }
                chipOff && atTop
            }
            composeRule.onNodeWithText("$id rss 5", useUnmergedTree = true).assertDoesNotExist()
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
        }
    }
}
