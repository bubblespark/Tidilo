package me.bubblespark.tidilo.data

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.DiscoverSnapshot
import me.bubblespark.tidilo.data.discover.MapBubble
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.discover.RisingGroup
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.screens.discover.DiscoverScreen
import me.bubblespark.tidilo.ui.screens.discover.DiscoverSource
import me.bubblespark.tidilo.ui.screens.discover.DiscoverViewModel
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Discover shelves rendered from a fake source (no database): layout, announcements, and callbacks. */
class DiscoverShelvesTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val domain = "${UUID.randomUUID().toString().take(8)}.example"
    private lateinit var caches: OfflineVisualCaches

    @Before fun images() {
        // Wide art keeps a 16:9 ratio; "square" URLs return 1:1 art to exercise the mat.
        caches = OfflineVisualCaches { url -> if ("square" in url) tinyPng(8, 8) else tinyPng(16, 9) }
    }

    @After fun restore() {
        caches.close()
    }

    private fun item(id: String, image: Boolean = true, square: Boolean = false, description: String = "") = BookmarkItem(
        id, "Title of $id", "https://$domain/$id", description, Tag.createEmpty(),
        createdAt = System.currentTimeMillis() - 40L * 86_400_000,
        ogImageUrl = if (image) "https://$domain/${if (square) "square" else "wide"}-$id.png" else null
    )
    private fun pick(id: String, reason: DiscoverReason? = null, image: Boolean = true) = DiscoverPick(item(id, image), reason)

    private fun fullSnapshot() = DiscoverSnapshot(
        day = 1, libraryCount = 120,
        hero = DiscoverPick(item("hero", description = "A two-line pull quote from the summary."), DiscoverReason.DormantNeverOpened(94)),
        onThisDay = listOf(pick("otd", DiscoverReason.OnThisDay(OnThisDayPeriod.ONE_YEAR))),
        sleepingGems = listOf(pick("gem0", DiscoverReason.DormantNeverOpened(41)), pick("gem1", DiscoverReason.DormantNeverOpened(52), image = false)),
        rising = listOf(RisingGroup("AI", "ai", listOf(pick("rise", DiscoverReason.Rising(4))))),
        connection = pick("left", DiscoverReason.Connection("Rust")) to pick("right", DiscoverReason.Connection("Rust"), image = false),
        connectionEntity = "Rust",
        map = listOf(MapBubble("Kotlin", "kotlin", 1f), MapBubble("Compose", "compose", 0.4f))
    )

    private class FakeSource(snapshot: DiscoverSnapshot) : DiscoverSource {
        override val privateVisible = MutableStateFlow(true)
        val snapshot = MutableStateFlow(snapshot)
        val justSaved = MutableStateFlow<List<BookmarkItem>>(emptyList())
        val freshRss = MutableStateFlow<List<DiscoverPick>>(emptyList())
        val processing = MutableStateFlow<List<ImportTask>>(emptyList())
        val errors = MutableStateFlow<List<ImportTask>>(emptyList())
        var deck: List<BookmarkItem> = emptyList()
        val impressions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        val flicks = java.util.concurrent.CopyOnWriteArrayList<String>()
        val likes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        override fun snapshot() = snapshot
        override fun justSaved() = justSaved
        override fun freshRss() = freshRss
        override fun processingTasks() = processing
        override fun errorTasks() = errors
        override suspend fun dealDeck() = deck
        override suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface) { impressions += bookmarkId to surface }
        override suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface) { flicks += bookmarkId }
        override suspend fun toggleLike(bookmarkId: String, surface: EngagementSurface): Boolean? {
            likes += bookmarkId to surface
            return true
        }
        override fun seenShelves(): Set<DiscoverShelf>? = DiscoverShelf.entries.toSet()
        override fun saveSeenShelves(shelves: Set<DiscoverShelf>) = Unit
        // Like the repository: suppressed cards and muted entities leave the snapshot until undone.
        private val original = snapshot
        private val hidden = java.util.Collections.synchronizedSet(HashSet<String>())
        private val muted = java.util.Collections.synchronizedSet(HashSet<String>())
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun refilter() {
            snapshot.value = original.copy(
                hero = original.hero?.takeIf { it.item.id !in hidden },
                sleepingGems = original.sleepingGems.filter { it.item.id !in hidden },
                map = original.map.filter { it.skeletonKey !in muted }
            )
        }
        override suspend fun peek(bookmarkId: String) { calls += "peek:$bookmarkId" }
        override suspend fun snooze(bookmarkId: String) { calls += "snooze:$bookmarkId"; hidden += bookmarkId; refilter() }
        override suspend fun neverAgain(bookmarkId: String) { calls += "never:$bookmarkId"; hidden += bookmarkId; refilter() }
        override suspend fun unsuppress(bookmarkId: String) { calls += "unsuppress:$bookmarkId"; hidden -= bookmarkId; refilter() }
        override suspend fun muteTopic(skeletonKey: String) { calls += "mute:$skeletonKey"; muted += skeletonKey; refilter() }
        override suspend fun unmuteTopic(skeletonKey: String) { calls += "unmute:$skeletonKey"; muted -= skeletonKey; refilter() }
        override suspend fun reDeal() { calls += "reDeal"; delay(300) }
        override fun entranceDay(): Long? = 1L // the fixture day: no entrance unless a test asks for it
        override fun saveEntranceDay(day: Long) = Unit
    }

    private class Calls {
        val opened = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DiscoverShelf>>()
        val entities = java.util.concurrent.CopyOnWriteArrayList<String>()
        val retries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val saves = java.util.concurrent.atomic.AtomicInteger()
    }

    private fun show(source: FakeSource, calls: Calls = Calls(), japaneseLightLargeFont: Boolean = false) {
        composeRule.setContent {
            val base = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val context = if (!japaneseLightLargeFont) base else remember(base, baseConfiguration) {
                base.createConfigurationContext(Configuration(baseConfiguration).apply {
                    setLocale(Locale.JAPAN)
                    fontScale = 2f
                })
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalDensity provides Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
            ) {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    // The app draws the canvas behind every screen; this host has no app chrome.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    DiscoverScreen(
                        onOpenNavigation = {},
                        onOpenBookmark = { id, shelf -> calls.opened += id to shelf },
                        onOpenEntity = { calls.entities += it },
                        onSaveUrl = { calls.saves.incrementAndGet() },
                        onRetryImport = { calls.retries += it },
                        viewModel = remember { DiscoverViewModel(source) }
                    )
                }
            }
        }
    }

    private fun nodes(matcher: SemanticsMatcher, useUnmergedTree: Boolean = true): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun awaitAbsent(matcher: SemanticsMatcher) = composeRule.waitUntil(8_000) { nodes(matcher).isEmpty() }

    private fun until(condition: () -> Boolean) = composeRule.waitUntil(5_000, condition)

    private fun tag(value: String) = hasTestTag(value)

    private fun click(node: SemanticsNode) {
        composeRule.runOnUiThread { node.config[SemanticsActions.OnClick].action?.invoke() }
        composeRule.waitForIdle()
    }

    private fun longClick(node: SemanticsNode) {
        composeRule.runOnUiThread { node.config[SemanticsActions.OnLongClick].action?.invoke() }
        composeRule.waitForIdle()
    }

    /** Scrolls the Discover list until a node matching [matcher] is composed. */
    private fun find(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.onNodeWithTag("discover_list").performScrollToNode(matcher)
        return await(matcher)
    }

    /** Clicks the clickable node whose merged text is [label] (buttons merge their child text). */
    private fun clickButton(label: String) {
        val button = hasClickAction() and hasText(label)
        composeRule.waitUntil(5_000) { nodes(button, useUnmergedTree = false).isNotEmpty() }
        composeRule.onAllNodes(button).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun mergedDescription(tag: String): List<String> =
        nodes(hasTestTag(tag), useUnmergedTree = false).first().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    @Test fun emptyLibraryShowsTheWallAndSaveUrl() {
        val calls = Calls()
        val source = FakeSource(fullSnapshot().copy(libraryCount = 0, hero = null, onThisDay = emptyList(), sleepingGems = emptyList(),
            rising = emptyList(), connection = null, map = emptyList()))
        show(source, calls)
        await(tag("discover_empty"))
        clickButton("Save URL")
        assertEquals(1, calls.saves.get())
    }

    @Test fun smallLibraryShowsDeckAndHowToSave() {
        val source = FakeSource(fullSnapshot().copy(libraryCount = 10, hero = null, sleepingGems = emptyList(), rising = emptyList(),
            connection = null, map = emptyList(), onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"))
        source.justSaved.value = listOf(item("new"))
        show(source)
        await(tag("discover_shelf_JUST_SAVED"))
        find(tag("discover_deck"))
        find(tag("discover_how_to_save"))
        assertTrue(nodes(tag("discover_shelf_HERO")).isEmpty())
    }

    @Test fun everyShelfRendersWithAHeadingAndAnnouncesItsReason() {
        val source = FakeSource(fullSnapshot())
        source.deck = listOf(item("d0"), item("d1", square = true))
        source.justSaved.value = listOf(item("new"))
        source.freshRss.value = listOf(pick("rss", DiscoverReason.RssExpiring(2)))
        show(source)
        await(tag("discover_shelf_HERO"))
        val hero = await(tag("discover_card_hero"))
        assertTrue(mergedDescription("discover_card_hero").any { "never opened" in it })
        assertTrue(hero.boundsInRoot.width > 0f)
        for (shelf in listOf("ON_THIS_DAY", "JUST_SAVED", "DECK", "SLEEPING_GEMS", "RISING", "FRESH_RSS", "CONNECTIONS", "MAP")) {
            find(tag("discover_shelf_$shelf"))
        }
        val headings = nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).size
        assertTrue("headings: $headings", headings >= 1)
        find(tag("discover_card_rss"))
        assertTrue(mergedDescription("discover_card_rss").any { "Disappears in 2 days" in it })
    }

    @Test fun japaneseLightThemeAtDoubleFontScaleKeepsShelvesReadable() {
        val source = FakeSource(fullSnapshot())
        source.deck = listOf(item("d0"), item("d1"))
        source.freshRss.value = listOf(pick("rss", DiscoverReason.RssExpiring(2)))
        show(source, japaneseLightLargeFont = true)
        await(hasText("今日の一枚") or tag("discover_shelf_HERO"))
        for (shelf in listOf("DECK", "SLEEPING_GEMS", "FRESH_RSS", "MAP")) find(tag("discover_shelf_$shelf"))
        find(hasText("シャッフル"))
    }

    @Test fun cardsOpenDetailWithTheirShelfAndEntitiesOpenSearch() {
        val calls = Calls()
        val source = FakeSource(fullSnapshot())
        show(source, calls)
        click(await(tag("discover_card_hero")))
        click(find(tag("discover_card_gem0")))
        click(find(tag("discover_rising_ai")))
        click(find(tag("discover_map_kotlin")))
        assertEquals(listOf("hero" to DiscoverShelf.HERO, "gem0" to DiscoverShelf.SLEEPING_GEMS), calls.opened.toList())
        assertEquals(listOf("AI", "Kotlin"), calls.entities.toList())
    }

    @Test fun runningAndFailedImportsShowInJustSaved() {
        val calls = Calls()
        val source = FakeSource(fullSnapshot().copy(hero = null))
        source.processing.value = listOf(ImportTask(id = "run", url = "https://$domain/run", title = "Running", status = TaskStatus.PROCESSING, statusText = ""))
        source.errors.value = listOf(ImportTask(id = "bad", url = "https://$domain/bad", title = "Broken", status = TaskStatus.ERROR, statusText = ""))
        show(source, calls)
        await(tag("discover_task_run"))
        clickButton("Retry")
        assertEquals(listOf("bad"), calls.retries.toList())
    }

    @Test fun deckButtonsAdvanceAndLike() {
        val source = FakeSource(fullSnapshot().copy(hero = null, onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"))
        show(source)
        click(find(tag("discover_deck_like")))
        until { source.likes.toList() == listOf("d0" to EngagementSurface.DISCOVER_DECK) }
        click(await(tag("discover_deck_next")))
        await(tag("discover_card_d1"))
        click(await(tag("discover_deck_next")))
        await(tag("discover_deck_deal"))
        assertEquals(listOf("d0", "d1"), source.flicks.toList())
    }

    @Test fun visibleCardRecordsAnImpressionWithItsShelf() {
        val source = FakeSource(fullSnapshot())
        show(source)
        await(tag("discover_card_hero"))
        until { source.impressions.any { it == "hero" to EngagementSurface.DISCOVER_HERO } }
    }

    @Test fun deckFlicksAndOffersAccessibilityActions() {
        val source = FakeSource(fullSnapshot().copy(hero = null, onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"), item("d2"))
        show(source)
        val card = find(tag("discover_card_d0"))
        assertEquals(listOf("Next card", "Like"), card.config[SemanticsActions.CustomActions].map { it.label })
        assertEquals("Peek", card.config[SemanticsActions.OnLongClick].label)
        assertEquals("Card 1 of 3", card.config.getOrNull(SemanticsProperties.StateDescription))
        composeRule.runOnUiThread { card.config[SemanticsActions.CustomActions][1].action() }
        until { source.likes.toList() == listOf("d0" to EngagementSurface.DISCOVER_DECK) }
        composeRule.runOnUiThread { card.config[SemanticsActions.CustomActions][0].action() }
        await(tag("discover_card_d1"))
        // A fast fling shorter than 40% of the card's width flicks it (velocity alone).
        composeRule.onNodeWithTag("discover_card_d1", useUnmergedTree = true).performTouchInput {
            swipe(Offset(left + 40f, centerY), Offset(left + 40f + width * 0.3f, centerY), durationMillis = 72)
        }
        await(tag("discover_card_d2"))
        until { source.flicks.toList() == listOf("d0", "d1") }
        // A short, slow drag springs back.
        composeRule.onNodeWithTag("discover_card_d2", useUnmergedTree = true).performTouchInput {
            swipe(Offset(left + 40f, centerY), Offset(left + 80f, centerY), durationMillis = 600)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("d0", "d1"), source.flicks.toList())
    }

    @Test fun peekSheetRecordsSnoozesAndUndoes() {
        val source = FakeSource(fullSnapshot())
        show(source)
        longClick(find(tag("discover_card_gem0")))
        await(tag("discover_peek_sheet"))
        until { "peek:gem0" in source.calls }
        clickButton("Not now")
        awaitAbsent(tag("discover_card_gem0"))
        clickButton("Undo")
        find(tag("discover_card_gem0"))
        assertEquals(listOf("peek:gem0", "snooze:gem0", "unsuppress:gem0"), source.calls.toList())

        longClick(find(tag("discover_card_gem0")))
        clickButton("Don’t show again")
        awaitAbsent(tag("discover_card_gem0"))
        assertTrue("never:gem0" in source.calls)
    }

    @Test fun peekLikesAddsToCollectionAndReturnsFocus() {
        val source = FakeSource(fullSnapshot())
        // Cards take input focus only outside touch mode (keyboard, D-pad), as in any Android app.
        instrumentation.setInTouchMode(false)
        try {
            show(source)
            longClick(await(tag("discover_card_hero")))
            click(await(tag("discover_peek_like")))
            until { source.likes.toList() == listOf("hero" to EngagementSurface.DISCOVER_PEEK) }
            clickButton("Add to collection")
            await(hasText("Choose collections"))
            awaitAbsent(tag("discover_peek_sheet"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitAbsent(hasText("Choose collections"))
            await(tag("discover_card_hero") and SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        } finally {
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun mapMuteHidesTheBubbleUntilUndo() {
        val source = FakeSource(fullSnapshot())
        show(source)
        val bubble = find(tag("discover_map_kotlin"))
        assertEquals("Mute topic", bubble.config[SemanticsActions.OnLongClick].label)
        longClick(bubble)
        awaitAbsent(tag("discover_map_kotlin"))
        clickButton("Undo")
        await(tag("discover_map_kotlin"))
        assertEquals(listOf("mute:kotlin", "unmute:kotlin"), source.calls.toList())
    }

    @Test fun pullingDownReDealsOnceAndFlipsTheSnapshotShelves() {
        val source = FakeSource(fullSnapshot())
        source.justSaved.value = listOf(item("new"))
        show(source)
        await(tag("discover_card_hero"))
        composeRule.onNodeWithTag("discover_card_hero", useUnmergedTree = true).performTouchInput {
            swipe(Offset(centerX, top + 40f), Offset(centerX, top + 900f), durationMillis = 320)
        }
        until { source.calls.toList() == listOf("reDeal") }
        find(tag("discover_card_new"))
        assertEquals(listOf("reDeal"), source.calls.toList())
    }
}
