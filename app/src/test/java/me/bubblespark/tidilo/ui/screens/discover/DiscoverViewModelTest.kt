package me.bubblespark.tidilo.ui.screens.discover

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.TaskStatus
import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.DiscoverSnapshot
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private fun item(id: String) = BookmarkItem(id, id, "https://fixture.invalid/$id", "", Tag.createEmpty(), createdAt = 1)
    private fun pick(id: String, reason: DiscoverReason? = null) = DiscoverPick(item(id), reason)
    private fun snapshot(count: Int, hero: Boolean = false, onThisDay: Boolean = false) = DiscoverSnapshot(
        day = 1, libraryCount = count, hero = if (hero) pick("hero") else null,
        onThisDay = if (onThisDay) listOf(pick("otd", DiscoverReason.OnThisDay(OnThisDayPeriod.ONE_YEAR))) else emptyList(),
        sleepingGems = emptyList(), rising = emptyList(), connection = null, connectionEntity = null, map = emptyList()
    )
    private fun task(id: String, status: TaskStatus, rss: Boolean = false, private: Boolean = false) =
        ImportTask(id = id, url = "https://fixture.invalid/$id", title = id, status = status, statusText = "",
            rssFeedId = if (rss) "feed" else null, isPrivate = private)

    private class FakeSource : DiscoverSource {
        override val privateVisible = MutableStateFlow(true)
        val snapshot = MutableStateFlow(DiscoverSnapshot(1, 0, null, emptyList(), emptyList(), emptyList(), null, null, emptyList()))
        val justSaved = MutableStateFlow<List<BookmarkItem>>(emptyList())
        val freshRss = MutableStateFlow<List<DiscoverPick>>(emptyList())
        val processing = MutableStateFlow<List<ImportTask>>(emptyList())
        val errors = MutableStateFlow<List<ImportTask>>(emptyList())
        var deck: List<BookmarkItem> = emptyList()
        var deals = 0
        val flicks = mutableListOf<Pair<String, EngagementSurface>>()
        val impressions = mutableListOf<Pair<String, EngagementSurface>>()
        var likeResult: () -> Boolean? = { true }
        val likes = mutableListOf<Pair<String, EngagementSurface>>()
        var seen: Set<DiscoverShelf>? = null
        override fun snapshot() = snapshot
        override fun justSaved() = justSaved
        override fun freshRss() = freshRss
        override fun processingTasks() = processing
        override fun errorTasks() = errors
        override suspend fun dealDeck(): List<BookmarkItem> { deals++; return deck }
        override suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface) { impressions += bookmarkId to surface }
        override suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface) { flicks += bookmarkId to surface }
        override suspend fun toggleLike(bookmarkId: String, surface: EngagementSurface): Boolean? {
            likes += bookmarkId to surface
            return likeResult()
        }
        override fun seenShelves() = seen
        override fun saveSeenShelves(shelves: Set<DiscoverShelf>) { seen = shelves }
    }

    private fun TestScope.viewModel(source: FakeSource): StateFlow<DiscoverUiState> {
        val vm = DiscoverViewModel(source, backgroundScope)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm.uiState.also { lastVm = vm }
    }
    private var lastVm: DiscoverViewModel? = null

    @Test fun coldStartStatesFollowLibrarySize() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val state = viewModel(source)
        assertFalse(state.value.loading)
        assertEquals(Greeting.NONE, state.value.greeting)
        assertFalse(state.value.showHowToSave)
        source.snapshot.value = snapshot(1)
        assertEquals(Greeting.GROWING, state.value.greeting)
        assertTrue(state.value.showHowToSave)
        source.snapshot.value = snapshot(49)
        assertTrue(state.value.showHowToSave)
        source.snapshot.value = snapshot(50, hero = true)
        assertEquals(Greeting.PICK, state.value.greeting)
        assertFalse(state.value.showHowToSave)
        assertEquals(DiscoverShelf.HERO, state.value.presentShelves.first())
    }

    @Test fun justSavedPutsManualVisibleImportsFirst() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.justSaved.value = listOf(item("saved"))
        source.processing.value = listOf(task("running", TaskStatus.PROCESSING), task("feed", TaskStatus.QUEUED, rss = true),
            task("secret", TaskStatus.QUEUED, private = true))
        source.errors.value = listOf(task("broken", TaskStatus.ERROR))
        val state = viewModel(source)
        assertEquals(listOf("task_running", "task_secret", "task_broken", "saved"), state.value.justSaved.map { it.key })
        source.privateVisible.value = false
        assertEquals(listOf("task_running", "task_broken", "saved"), state.value.justSaved.map { it.key })
        assertTrue(state.value.justSaved[1] is JustSavedEntry.Failed)
    }

    @Test fun firstShelfCaptionAppearsOnceAfterTheBaseline() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        val state = viewModel(source)
        assertNull(state.value.newShelfCaption)
        assertEquals(emptySet<DiscoverShelf>(), source.seen)
        source.snapshot.value = snapshot(10, onThisDay = true)
        assertEquals(DiscoverShelf.ON_THIS_DAY, state.value.newShelfCaption)
        assertEquals(setOf(DiscoverShelf.ON_THIS_DAY), source.seen)
        // A new visit (new ViewModel) never repeats it.
        val again = viewModel(source)
        assertNull(again.value.newShelfCaption)
    }

    @Test fun existingLibraryRecordsBaselineWithoutCaptions() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(80, hero = true, onThisDay = true)
        val state = viewModel(source)
        assertNull(state.value.newShelfCaption)
        assertEquals(setOf(DiscoverShelf.HERO, DiscoverShelf.ON_THIS_DAY), source.seen)
    }

    @Test fun deckIsDealtOnOpenAndOnDemandAndRecordsFlicks() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"), item("b"))
        val state = viewModel(source)
        assertEquals(1, source.deals)
        assertEquals("a", state.value.deck.current?.id)
        lastVm!!.nextCard()
        lastVm!!.nextCard()
        assertTrue(state.value.deck.exhausted)
        assertEquals(listOf("a" to EngagementSurface.DISCOVER_DECK, "b" to EngagementSurface.DISCOVER_DECK), source.flicks)
        lastVm!!.nextCard()
        assertEquals(2, source.flicks.size)
        lastVm!!.dealAnother()
        assertEquals(2, source.deals)
        assertEquals(0, state.value.deck.index)
        lastVm!!.toggleDeckLike(state.value.deck.current!!)
        assertTrue(state.value.deck.isLiked(state.value.deck.current!!))
        assertEquals(listOf("a" to EngagementSurface.DISCOVER_DECK), source.likes)
    }

    @Test fun deckLikeFollowsTheStoredStateAndRollsBack() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"))
        val state = viewModel(source)
        val card = state.value.deck.current!!
        source.likeResult = { null } // the bookmark was deleted meanwhile
        lastVm!!.toggleDeckLike(card)
        assertFalse(state.value.deck.isLiked(card))
        source.likeResult = { error("write failed") }
        lastVm!!.toggleDeckLike(card)
        assertFalse(state.value.deck.isLiked(card))
        source.likeResult = { false } // stored state wins over the optimistic flip
        lastVm!!.toggleDeckLike(card)
        assertFalse(state.value.deck.isLiked(card))
    }

    @Test fun hidingPrivateContentRemovesDealtPrivateCards() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"), item("secret").copy(isPrivate = true), item("b"), item("hidden").copy(isPrivate = true))
        val state = viewModel(source)
        lastVm!!.nextCard()
        lastVm!!.nextCard() // top card is now "b"
        source.privateVisible.value = false
        assertEquals(listOf("a", "b"), state.value.deck.cards.map { it.id })
        assertEquals("b", state.value.deck.current?.id)
    }

    @Test fun firstSaveIntoAnEmptyLibraryDealsTheDeck() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val state = viewModel(source)
        assertTrue(state.value.deck.cards.isEmpty())
        source.deck = listOf(item("first"))
        source.snapshot.value = snapshot(1)
        assertEquals(2, source.deals)
        assertEquals("first", state.value.deck.current?.id)
        assertTrue(DiscoverShelf.DECK in state.value.presentShelves)
    }

    @Test fun onlyTheCaptionedShelfIsMarkedSeen() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        val state = viewModel(source)
        source.snapshot.value = snapshot(60, hero = true, onThisDay = true)
        assertEquals(DiscoverShelf.HERO, state.value.newShelfCaption)
        assertEquals(setOf(DiscoverShelf.HERO), source.seen)
        val next = viewModel(source)
        assertEquals(DiscoverShelf.ON_THIS_DAY, next.value.newShelfCaption)
        assertEquals(setOf(DiscoverShelf.HERO, DiscoverShelf.ON_THIS_DAY), source.seen)
    }

    @Test fun impressionsCarryTheShelfSurface() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        viewModel(source)
        lastVm!!.onImpression("x", DiscoverShelf.SLEEPING_GEMS)
        assertEquals(listOf("x" to EngagementSurface.DISCOVER_SLEEPING_GEMS), source.impressions)
    }
}
