package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.TidiloApp
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryNavigationInteractionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    /** The modal rail and dialogs swap window focus; for a moment no Compose root is reachable. */
    private fun safeNodes(matcher: SemanticsMatcher) = runCatching { nodes(matcher) }.getOrDefault(emptyList())

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { safeNodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun description(id: Int) = hasContentDescription(activity.getString(id))
    private fun text(id: Int) = hasText(activity.getString(id))

    private fun clickableNode(node: SemanticsNode): SemanticsNode {
        var target = node
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        return target
    }

    private fun click(node: SemanticsNode) {
        val target = clickableNode(node)
        composeRule.runOnUiThread { check(target.config[SemanticsActions.OnClick].action?.invoke() == true) }
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
        // MainActivity draws edge to edge; the shell's insets depend on it.
        composeRule.runOnUiThread { activity.enableEdgeToEdge() }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                TidiloApp(libraryViewModel = model)
            }
        }
    }

    @Test fun searchBackRestoresLibraryPosition() = withLibrary { library ->
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            library.db.bookmarkDao().insertAll((0..15).map {
                BookmarkEntity("navigation-$it", "Navigation fixture $it", "https://navigation.invalid/$it", "Summary",
                    createdAt = now + it, updatedAt = 1)
            })
        }
        val model = library.viewModel()
        var stage = "opening the Library"
        val fixtureCard = SemanticsMatcher("navigation fixture card") { node ->
            // Grid cards and list rows carry the bookmark id in their tag.
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("library_card_navigation-") || it.startsWith("library_row_navigation-") } == true
        }
        fun scrollBy(pixels: Float) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))[0]
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
            composeRule.waitForIdle()
        }
        try {
            composeRule.runOnUiThread { model.setFilterMode(LibraryFilterMode.NEWEST_FIRST) }
            showApp(model)
            // The app starts on Discover; the toolbar opens the Library layer.
            click(await(hasTestTag("floating_nav_library")))
            stage = "waiting for Library fixtures"
            await(fixtureCard)
            scrollBy(500f)
            // A short upward scroll brings the hidden toolbar back.
            scrollBy(-40f)
            val height = composeRule.runOnUiThread { activity.window.decorView.height }
            val first = nodes(fixtureCard).filter { it.boundsInRoot.top > 0f && it.boundsInRoot.bottom < height }
                .minBy { it.boundsInRoot.top }
            val firstVisibleCard = first.config[SemanticsProperties.TestTag]
            val firstVisibleTop = first.boundsInRoot.top
            stage = "opening Search"
            click(await(hasTestTag("floating_nav_search")))
            await(text(R.string.search_header_title))
            stage = "returning from Search"
            // Back from Search returns to Discover; the toolbar's Library item restores the saved Library state.
            click(await(description(R.string.common_back)))
            click(await(hasTestTag("floating_nav_library")))
            await(text(R.string.library_header_title))
            assertEquals(LibraryFilterMode.NEWEST_FIRST, model.filterMode.value)
            stage = "restoring the visible bookmark"
            composeRule.waitUntil(8_000) {
                safeNodes(hasTestTag(firstVisibleCard)).any { abs(it.boundsInRoot.top - firstVisibleTop) <= 2f }
            }
            val restored = nodes(hasTestTag(firstVisibleCard)).first()
            assertEquals("Back must restore the same bookmark position", firstVisibleTop, restored.boundsInRoot.top, 2f)
        } catch (error: Throwable) {
            throw AssertionError("Navigation test failed while $stage; filter=${model.filterMode.value}; initialized=${model.isInitialized.value}", error)
        }
    }

    @Test fun drawerOpensNotificationsWithoutRootTabs() = withLibrary { library ->
        showApp(library.viewModel())
        click(await(description(R.string.navigation_open)))
        click(await(description(R.string.settings_notification_title)))
        await(text(R.string.settings_notification_title))
        click(await(description(R.string.common_back)))
        await(description(R.string.navigation_open))
    }

    @Test fun emptyLibrarySaveUrlMeetsTouchTargetAcrossFontScales() = withLibrary { library ->
        val collectionId = "empty-capture"
        val model = library.viewModel()
        val fontScale = mutableFloatStateOf(1f)
        var saveClicks = 0
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                    Box(Modifier.size(360.dp, 640.dp).background(MaterialTheme.colorScheme.background)) {
                        LibraryScreen(viewModel = model, onOpenManualImport = { saveClicks++ })
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            library.db.collectionDao().insert(CollectionEntity(collectionId, "Empty capture fixture", "", "folder", null, 0, 1L, 1L))
        }
        composeRule.runOnUiThread { model.setFilterMode(LibraryFilterMode.COLLECTION) }
        // Collection rows are collected only while the Library UI observes them.
        composeRule.waitUntil(8_000) { model.collectionState.value.rows.any { it.collection.id == collectionId } }
        composeRule.runOnUiThread { model.selectCollectionFilter(collectionId) }
        val density = activity.resources.displayMetrics.density
        for ((index, scale) in listOf(1f, 2f).withIndex()) {
            composeRule.runOnIdle { fontScale.floatValue = scale }
            await(text(R.string.library_empty_title))
            await(text(R.string.library_empty_hint))
            val cta = clickableNode(await(text(R.string.library_empty_save_url)))
            assertTrue("fontScale=$scale CTA must have >= 48dp height", cta.boundsInRoot.height >= 47.5f * density)
            // The 640dp host reserves the floating toolbar's clearance at its bottom.
            assertTrue("fontScale=$scale Save URL button must stay above the floating toolbar",
                cta.boundsInRoot.bottom <= (640.dp - HeaderDefaults.FloatingNavigationClearance).value * density + 1f)
            click(cta)
            assertEquals(index + 1, saveClicks)
        }
    }
}
