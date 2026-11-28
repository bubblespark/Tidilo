package me.bubblespark.tidilo.data

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.navigation.LocalNavAnimatedScope
import me.bubblespark.tidilo.ui.navigation.LocalSharedTransitionScope
import me.bubblespark.tidilo.ui.navigation.PlateNavTransitions
import me.bubblespark.tidilo.ui.navigation.PlateTransitionLayout
import me.bubblespark.tidilo.ui.navigation.Screen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.DomainGroupItem
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryStore
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Library plate ↔ Detail hero (§7.2) under a `NavHost` wrapped like the app's: the morph on open and Back,
 * predictive back cancel/commit, an interrupted open, and the crossfade-only cases. Time is stepped by frame.
 */
class PlateTransitionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>(
        // Device animation scale must not decide the outcome; the test steps time itself.
        effectContext = object : MotionDurationScale { override val scaleFactor = 1f }
    )
    private val domain = "${UUID.randomUUID().toString().take(8)}.example"
    private val modelScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var caches: OfflineVisualCaches
    private var scope: SharedTransitionScope? = null

    @Before fun images() {
        // A wide image, so Detail shows its hero banner.
        caches = OfflineVisualCaches { tinyPng(191, 100) }
    }

    @After fun restore() {
        modelScope.cancel()
        caches.close()
    }

    private fun item(id: String, image: Boolean) = BookmarkItem(
        id, "Title of $id", "https://$domain/$id", "Summary of $id", Tag.createEmpty(),
        createdAt = System.currentTimeMillis() - 86_400_000,
        ogImageUrl = if (image) "https://$domain/wide-$id.png" else null
    )

    private class FakeStore(items: List<BookmarkItem>) : LibraryStore {
        override val isInitialized = MutableStateFlow(true)
        override val initializationFailure = MutableStateFlow<Throwable?>(null)
        private val items = MutableStateFlow(items)
        private val done = LoadStates(LoadState.NotLoading(true), LoadState.NotLoading(true), LoadState.NotLoading(true))
        override fun retryInitialization() = Unit
        override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>> =
            items.map { PagingData.from(it, done) }
        override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>> = flowOf(PagingData.empty())
        override fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>> = flowOf(PagingData.empty())
        override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) = items.map { it.size }
        override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) = flowOf(0)
        override fun observeDomainGroups(showPrivate: Boolean) = flowOf(emptyList<DomainGroupItem>())
        override fun observeRssFeedGroups(showPrivate: Boolean) = flowOf(emptyList<RssFeedGroup>())
        override fun observeRssFeedCount() = flowOf(0)
        override fun observeCollections(showPrivate: Boolean) = flowOf(emptyList<CollectionWithCount>())
        override fun observeMembershipIds(bookmarkId: String) = flowOf(emptyList<String>())
        override fun observeBookmark(id: String) = items.map { list -> list.firstOrNull { it.id == id } }
        override fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind) = flowOf(emptyList<TagCount>())
        override fun observeEntityLibrary() = flowOf(emptyMap<String, String>())
        override suspend fun toggleLike(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordBrowserOpen(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordDetailView(id: String, surface: EngagementSurface) = Unit
        override suspend fun recordSummaryPeek(id: String, surface: EngagementSurface) = Unit
        override suspend fun saveNote(id: String, note: String) = Unit
        override suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) = Unit
        override suspend fun applyAiProposal(id: String, description: String?, tag: Tag?) = true
        override suspend fun moveToTrash(id: String) = true
        override fun loadFilterMode() = LibraryFilterMode.NEWEST_FIRST
        override suspend fun saveFilterMode(mode: LibraryFilterMode) = Unit
        override fun loadViewMode() = LibraryViewMode.GRID
        override fun saveViewMode(mode: LibraryViewMode) = Unit
        override fun loadIncludeRssInNewestFirst() = false
        override fun saveIncludeRssInNewestFirst(include: Boolean) = Unit
        override suspend fun loadSearchHistory() = emptyList<String>()
        override suspend fun saveSearchHistory(history: List<String>) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    private fun show(reducedMotion: Boolean = false) {
        val store = FakeStore(listOf(item("a", image = true), item("p", image = false)))
        lateinit var model: LibraryViewModel
        composeRule.runOnUiThread { model = LibraryViewModel(store, SavedStateHandle(), MutableStateFlow(true), modelScope) }
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                val nav = rememberNavController()
                // The same page transitions TidiloNavGraph uses between plate screens and Detail.
                val plate = PlateNavTransitions(MaterialTheme.motionScheme.defaultEffectsSpec(), reducedMotion)
                PlateTransitionLayout(reducedMotion = reducedMotion) { hostModifier ->
                    scope = LocalSharedTransitionScope.current
                    NavHost(nav, Screen.Library.route, hostModifier,
                        enterTransition = { plate.enter },
                        exitTransition = { plate.exit },
                        popEnterTransition = { plate.enter },
                        popExitTransition = { plate.popExit }
                    ) {
                        composable(Screen.Library.route) {
                            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                LibraryScreen(
                                    viewModel = model,
                                    onNavigateToDetail = { nav.navigate(Screen.BookmarkDetail.createRoute(it)) { launchSingleTop = true } }
                                )
                            }
                        }
                        composable(
                            Screen.BookmarkDetail.route,
                            arguments = listOf(
                                navArgument("bookmarkId") { type = NavType.StringType },
                                navArgument("surface") { type = NavType.IntType; defaultValue = EngagementSurface.DETAIL.code }
                            )
                        ) { entry ->
                            CompositionLocalProvider(LocalNavAnimatedScope provides this) {
                                BookmarkDetailScreen(
                                    bookmarkId = entry.arguments?.getString("bookmarkId").orEmpty(),
                                    viewModel = model,
                                    onNavigateBack = { nav.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }
    private fun awaitAbsent(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) { nodes(matcher).isEmpty() }
    private val hero = hasTestTag("detail_hero")
    private fun card(id: String) = hasTestTag("library_card_$id")
    private fun rootWidth() = composeRule.activity.window.decorView.width.toFloat()
    private fun heroWidth(): Float? = nodes(hero).firstOrNull()?.boundsInRoot?.width
    private fun active() = scope?.isTransitionActive == true

    private fun tap(id: String) {
        val node = await(card(id))
        composeRule.runOnUiThread { check(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) }
    }
    private fun back() = composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

    /** Steps up to [frames] frames, returning every hero width seen while a shared transition ran. */
    private fun stepAndSample(frames: Int = 60): List<Float> {
        val widths = mutableListOf<Float>()
        repeat(frames) {
            composeRule.mainClock.advanceTimeByFrame()
            if (active()) heroWidth()?.let { widths += it }
        }
        return widths
    }

    private fun settle() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.waitUntil(8_000) { !active() }
    }

    private fun openSettled(id: String) {
        tap(id)
        await(hero)
        composeRule.waitUntil(8_000) { !active() && heroWidth()?.let { it > rootWidth() * 0.95f } == true }
    }

    private fun assertMorphed(widths: List<Float>, small: Float, large: Float) =
        assertTrue("hero widths during the transition $widths, card $small, full $large",
            widths.any { it > small + 1f && it < large - 1f })

    @Test fun openMorphsPlateIntoHeroAndBackReturnsIt() {
        show()
        val cardWidth = await(card("a")).boundsInRoot.width
        composeRule.mainClock.autoAdvance = false
        tap("a")
        assertMorphed(stepAndSample(), cardWidth, rootWidth())
        settle()
        await(hero)

        composeRule.mainClock.autoAdvance = false
        back()
        assertMorphed(stepAndSample(), cardWidth, rootWidth())
        settle()
        awaitAbsent(hero)
        await(card("a"))
    }

    @Test fun backButtonDrawsAboveHeroDuringOpen() {
        show()
        await(card("a"))
        composeRule.mainClock.autoAdvance = false
        tap("a")
        // Step to the last frames of the morph, where the full-width hero covers Detail's top-left corner.
        var sampled = false
        repeat(60) {
            composeRule.mainClock.advanceTimeByFrame()
            val back = nodes(hasTestTag("detail_back")).firstOrNull()
            if (!sampled && active() && back != null && (heroWidth() ?: 0f) > rootWidth() * 0.95f) {
                sampled = true
                val center = back.boundsInRoot.center
                val pixel = composeRule.onRoot().captureToImage().toPixelMap()[center.x.toInt(), center.y.toInt()]
                // The hero fixture is pure green; the Back plate and its arrow are neutral.
                assertTrue("Back is hidden under the hero: $pixel", pixel.green - maxOf(pixel.red, pixel.blue) < 0.3f)
            }
        }
        assertTrue("no frame with an active transition and a full-width hero", sampled)
        settle()
    }

    @Test fun predictiveBackCancelKeepsDetailAndCommitReturnsToCard() {
        show()
        openSettled("a")
        val dispatcher = composeRule.activity.onBackPressedDispatcher
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 800f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 800f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        val mid = stepAndSample(10)
        assertTrue("hero follows the gesture: $mid", mid.any { it < rootWidth() - 1f })
        composeRule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        settle()
        composeRule.waitUntil(8_000) { heroWidth()?.let { it > rootWidth() * 0.95f } == true }

        composeRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 800f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 800f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        settle()
        awaitAbsent(hero)
        await(card("a"))
    }

    @Test fun gridListToggleOnlyCrossfades() {
        show()
        await(card("a"))
        composeRule.mainClock.autoAdvance = false
        val toggle = await(hasTestTag("library_view_toggle"))
        composeRule.runOnUiThread { check(toggle.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true) }
        var sawActive = false
        repeat(30) {
            composeRule.mainClock.advanceTimeByFrame()
            sawActive = sawActive || active()
        }
        assertFalse("outgoing and incoming plates must not share keys during the crossfade", sawActive)
        settle()
        await(hasTestTag("library_row_a"))
    }

    @Test fun backDuringOpenSettlesOnLibrary() {
        show()
        await(card("a"))
        composeRule.mainClock.autoAdvance = false
        tap("a")
        repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        back()
        settle()
        awaitAbsent(hero)
        await(card("a"))
    }

    @Test fun reducedMotionOnlyCrossfades() {
        show(reducedMotion = true)
        await(card("a"))
        composeRule.mainClock.autoAdvance = false
        tap("a")
        assertTrue(stepAndSample().isEmpty())
        assertFalse(active())
        settle()
        await(hero)
    }

    @Test fun posterCardOpensWithoutSharedHero() {
        show()
        await(card("p"))
        composeRule.mainClock.autoAdvance = false
        tap("p")
        var sawActive = false
        repeat(60) {
            composeRule.mainClock.advanceTimeByFrame()
            sawActive = sawActive || active()
        }
        assertFalse(sawActive)
        settle()
        awaitAbsent(card("p"))
    }
}
