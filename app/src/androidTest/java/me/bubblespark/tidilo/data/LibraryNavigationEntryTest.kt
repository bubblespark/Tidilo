package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.SearchScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryNavigationEntryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        // The modal rail swaps window focus; for a moment no Compose root is reachable.
        composeRule.waitUntil(8_000) { runCatching { nodes(matcher) }.getOrDefault(emptyList()).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun description(id: Int) = hasContentDescription(activity.getString(id))
    private fun text(id: Int) = hasText(activity.getString(id))

    /** Invokes the click action of [node] or its nearest clickable ancestor on the UI thread. */
    private fun click(node: SemanticsNode, then: () -> Unit = {}) {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        composeRule.runOnUiThread {
            assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            then()
        }
        composeRule.waitForIdle()
    }

    /** System Back reaches the focused window, so it also closes the modal rail's own window. */
    private fun pressSystemBack() {
        check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
    }

    private fun scroll(pixels: Float) {
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))[0]
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        composeRule.waitForIdle()
    }

    /** Runs [block] against an in-memory Library with offline visual caches. */
    private fun withLibrary(block: suspend (InMemoryLibrary) -> Unit): Unit = runBlocking {
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(activity.applicationContext).also { it.awaitReady() } }
        try { block(library) } finally {
            caches.close()
            library.close()
        }
    }

    private fun showApp(model: LibraryViewModel) {
        // MainActivity draws edge to edge; rail and header insets depend on it.
        composeRule.runOnUiThread { activity.enableEdgeToEdge() }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                TidiloApp(libraryViewModel = model, discoverPreviewEnabled = false)
            }
        }
    }

    @Test fun domainDrillDownReplacesMenuWithUp() = verifyGroupNavigation(LibraryFilterMode.DOMAIN)

    @Test fun emptyRssDrillDownReplacesMenuWithUp() = verifyGroupNavigation(LibraryFilterMode.RSS)

    @Test fun emptyCollectionDrillDownReplacesMenuWithUp() = verifyGroupNavigation(LibraryFilterMode.COLLECTION)

    private fun verifyGroupNavigation(mode: LibraryFilterMode) = withLibrary { library ->
        val id = "navigation-group"
        val backLabel = activity.getString(when (mode) {
            LibraryFilterMode.COLLECTION -> R.string.collection_back_to_collections
            LibraryFilterMode.RSS -> R.string.rss_back_to_feeds
            else -> R.string.library_back_to_domains
        })
        val model = library.viewModel()
        fun selectGroup() {
            when (mode) {
                LibraryFilterMode.COLLECTION -> model.selectCollectionFilter(id)
                LibraryFilterMode.RSS -> model.selectRssFeedFilter(RssFeedEntity(id, "https://navigation.invalid/$id", "Navigation fixture"))
                else -> model.selectDomainFilter("navigation.invalid")
            }
        }
        fun assertCleared() {
            assertNull(model.selectedDomainFilter.value)
            assertNull(model.selectedRssFeedFilter.value)
            assertNull(model.selectedCollectionId.value)
            assertEquals(mode, model.filterMode.value)
        }
        showApp(model)
        if (mode == LibraryFilterMode.COLLECTION) {
            withContext(Dispatchers.IO) {
                library.db.collectionDao().insert(CollectionEntity(id, "Navigation fixture", "", "folder", null, 0, 1, 1))
            }
            // Collection rows are collected only while the Library UI observes them.
            composeRule.waitUntil(8_000) { model.collectionState.value.rows.any { it.collection.id == id } }
            composeRule.runOnIdle { model.setFilterMode(mode) }
            await(hasText("Navigation fixture"))
        }
        composeRule.runOnIdle { model.setFilterMode(mode); selectGroup() }
        val back = await(hasContentDescription(backLabel))
        assertTrue("Group navigation must replace Menu with Up", nodes(description(R.string.navigation_open)).isEmpty())
        click(back) { assertCleared() }
        click(await(description(R.string.navigation_open)))
        await(text(R.string.app_name))
        pressSystemBack()
        composeRule.runOnIdle { selectGroup() }
        await(hasContentDescription(backLabel))
        pressSystemBack()
        composeRule.waitUntil(3_000) { model.selectedDomainFilter.value == null && model.selectedRssFeedFilter.value == null &&
            model.selectedCollectionId.value == null }
        composeRule.runOnIdle { assertCleared() }
        await(description(R.string.navigation_open))
    }

    @Test fun searchHistoryEndsWithoutObsoleteNavigationClearance() = withLibrary { library ->
        library.store.saveSearchHistory((0..9).map { "Spacing fixture $it" })
        val model = library.viewModel()
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                Box(Modifier.height(500.dp)) { SearchScreen(model) }
            }
        }
        await(hasText("Spacing fixture 0"))
        scroll(100_000f)
        val last = await(hasText("Spacing fixture 9"))
        val grid = nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).first()
        val gap = (grid.boundsInRoot.bottom - last.boundsInRoot.bottom) / activity.resources.displayMetrics.density
        assertTrue("Search must use safe insets rather than obsolete bar clearance: $gap dp", gap < 100f)
    }

    @Test fun railUtilitiesReachBottomAndHeaderStaysVisible() = withLibrary { library ->
        showApp(library.viewModel())
        click(await(description(R.string.navigation_open)))
        val settings = await(text(R.string.title_settings))
        val density = activity.resources.displayMetrics.density
        val height = composeRule.runOnUiThread { activity.window.decorView.height }
        assertTrue("Settings belongs near the bottom of a tall rail", settings.boundsInRoot.bottom > height - 90f * density)
        assertTrue(nodes(text(R.string.app_name)).isNotEmpty())
    }

    @Test fun libraryExposesMenuInsteadOfSettings() = withLibrary { library ->
        showApp(library.viewModel())
        await(description(R.string.navigation_open))
        composeRule.waitForIdle()
        assertTrue("Library must have its navigation menu", nodes(description(R.string.navigation_open)).isNotEmpty())
    }

    @Test fun searchExposesBackInsteadOfRootNavigation() = withLibrary { library ->
        val model = library.viewModel()
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { SearchScreen(model) }
        }
        await(text(R.string.search_header_title))
        composeRule.waitForIdle()
        assertTrue("Search must expose a Back action as a child of Library", nodes(description(R.string.common_back)).isNotEmpty())
    }
}
