package me.bubblespark.tidilo.ui.screens.discover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import me.bubblespark.tidilo.data.discover.MapBubble
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
        val calls = mutableListOf<String>()
        var hideWrite: suspend () -> Unit = {}
        var reDealGate: CompletableDeferred<Unit>? = null
        var entrance: Long? = null
        override suspend fun peek(bookmarkId: String) { calls += "peek:$bookmarkId" }
        override suspend fun snooze(bookmarkId: String) { hideWrite(); calls += "snooze:$bookmarkId" }
        override suspend fun neverAgain(bookmarkId: String) { hideWrite(); calls += "never:$bookmarkId" }
        override suspend fun unsuppress(bookmarkId: String) { calls += "unsuppress:$bookmarkId" }
        override suspend fun muteTopic(skeletonKey: String) { calls += "mute:$skeletonKey" }
        override suspend fun unmuteTopic(skeletonKey: String) { calls += "unmute:$skeletonKey" }
        override suspend fun reDeal() { calls += "reDeal"; reDealGate?.await() }
        override fun entranceDay() = entrance
        override fun saveEntranceDay(day: Long) { entrance = day }
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
        lastVm!!.nextCard("a")
        lastVm!!.nextCard("a") // a second tap for the same card (during the throw) is ignored
        lastVm!!.nextCard("b")
        assertTrue(state.value.deck.exhausted)
        assertEquals(listOf("a" to EngagementSurface.DISCOVER_DECK, "b" to EngagementSurface.DISCOVER_DECK), source.flicks)
        lastVm!!.nextCard("b")
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
        lastVm!!.nextCard("a")
        lastVm!!.nextCard("secret") // top card is now "b"
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

    @Test fun deckRemoveAndRestoreKeepTheTopCard() {
        val deck = DeckState(listOf(item("a"), item("b"), item("c"), item("d")), index = 2) // top = c
        assertEquals("c", deck.remove("a").current?.id)
        assertEquals("d", deck.remove("c").current?.id)
        assertEquals("c", deck.remove("d").current?.id)
        assertEquals(deck, deck.remove("missing"))
        assertEquals(deck, deck.remove("a").restore(item("a"), 0))
        assertEquals(deck, deck.remove("c").restore(item("c"), 2))
        assertEquals(deck, deck.restore(item("c"), 0))
        assertTrue(DeckState(listOf(item("a"))).next().exhausted)
        assertTrue(DeckState(listOf(item("a")), index = 1).next().exhausted)
    }

    @Test fun snoozeHidesTheDeckCardAndUndoPutsItBack() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"), item("b"))
        val state = viewModel(source)
        lastVm!!.snooze(item("a"))
        assertEquals("b", state.value.deck.current?.id)
        val notice = state.value.notice!!
        assertEquals(DiscoverNotice(DiscoverNotice.Kind.SNOOZED, "a", "a"), notice)
        lastVm!!.noticeShown()
        assertNull(state.value.notice)
        lastVm!!.undo(notice)
        assertEquals("a", state.value.deck.current?.id)
        lastVm!!.neverAgain(item("gem")) // not in the deck
        assertEquals(DiscoverNotice.Kind.HIDDEN, state.value.notice?.kind)
        assertEquals(listOf("snooze:a", "unsuppress:a", "never:gem"), source.calls)
    }

    @Test fun failedHideRestoresTheCardButCancellationDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"), item("b"))
        val state = viewModel(source)
        source.hideWrite = { error("write failed") }
        lastVm!!.snooze(item("a"))
        assertEquals("a", state.value.deck.current?.id)
        assertNull(state.value.notice)
        source.hideWrite = { throw CancellationException("left Discover") }
        lastVm!!.snooze(item("a"))
        assertEquals("b", state.value.deck.current?.id)
    }

    @Test fun peekRecordsAndLikesWithThePeekSurface() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        val state = viewModel(source)
        lastVm!!.onPeek("x")
        lastVm!!.togglePeekLike(item("x"))
        assertEquals(listOf("peek:x"), source.calls)
        assertEquals(listOf("x" to EngagementSurface.DISCOVER_PEEK), source.likes)
        assertTrue(state.value.deck.isLiked(item("x")))
    }

    @Test fun muteShowsANoticeAndUndoUnmutes() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val state = viewModel(source)
        lastVm!!.muteTopic(MapBubble("Kotlin", "kotlin", 1f))
        val notice = state.value.notice!!
        assertEquals(DiscoverNotice(DiscoverNotice.Kind.MUTED, "kotlin", "Kotlin"), notice)
        lastVm!!.undo(notice)
        assertEquals(listOf("mute:kotlin", "unmute:kotlin"), source.calls)
    }

    @Test fun reDealRunsOnceWhilePending() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.reDealGate = gate
        val state = viewModel(source)
        lastVm!!.reDeal()
        lastVm!!.reDeal()
        assertTrue(state.value.reDealing)
        gate.complete(Unit)
        assertFalse(state.value.reDealing)
        assertEquals(listOf("reDeal"), source.calls)
    }

    @Test fun entrancePlaysOnTheFirstOpenOfTheDayOnly() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        val state = viewModel(source)
        assertTrue(state.value.playEntrance)
        lastVm!!.entranceShown()
        assertFalse(state.value.playEntrance)
        assertFalse(viewModel(source).value.playEntrance) // same day, new visit
        // A new day: only the first Discover that sees it plays the entrance.
        source.snapshot.value = snapshot(10).copy(day = 2)
        assertTrue(state.value.playEntrance)
        assertFalse(viewModel(source).value.playEntrance)
    }

    @Test fun undoAfterHidingPrivateContentKeepsThePrivateCardOut() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("secret").copy(isPrivate = true), item("b"))
        val state = viewModel(source)
        lastVm!!.snooze(state.value.deck.current!!)
        val notice = state.value.notice!!
        source.privateVisible.value = false
        lastVm!!.undo(notice)
        assertEquals(listOf("b"), state.value.deck.cards.map { it.id })
        // A failed write rolls back the same way.
        source.privateVisible.value = true
        source.deck = listOf(item("hidden").copy(isPrivate = true), item("c"))
        lastVm!!.dealAnother()
        source.hideWrite = { source.privateVisible.value = false; error("write failed") }
        lastVm!!.snooze(state.value.deck.current!!)
        assertEquals(listOf("c"), state.value.deck.cards.map { it.id })
    }

    @Test fun undoAfterANewDealPutsTheCardInTheCurrentDeck() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"), item("b"))
        val state = viewModel(source)
        lastVm!!.snooze(item("a"))
        val notice = state.value.notice!!
        source.deck = listOf(item("x"), item("y"))
        lastVm!!.dealAnother()
        lastVm!!.undo(notice)
        lastVm!!.undo(notice)
        assertEquals(listOf("a", "x", "y"), state.value.deck.cards.map { it.id })
    }

    @Test fun storedLikeStateShowsThroughOnceTheShelvesCatchUp() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10, hero = true)
        val state = viewModel(source)
        val hero = state.value.hero!!.item
        lastVm!!.togglePeekLike(hero)
        assertTrue(state.value.deck.isLiked(state.value.findItem("hero")!!))
        // The shelves now store the like; then Detail unlikes it.
        source.snapshot.value = snapshot(10).copy(hero = DiscoverPick(hero.copy(isLiked = true), null))
        source.snapshot.value = snapshot(10).copy(hero = DiscoverPick(hero.copy(isLiked = false), null))
        assertFalse(state.value.deck.isLiked(state.value.findItem("hero")!!))
    }

    @Test fun deckLikeUpdatesTheDealtCopy() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        source.deck = listOf(item("a"))
        val state = viewModel(source)
        lastVm!!.toggleDeckLike(state.value.deck.current!!)
        assertTrue(state.value.deck.current!!.isLiked)
    }

    @Test fun anotherDiscoverDayWhileOpenPlaysTheEntranceAgain() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.snapshot.value = snapshot(10)
        val state = viewModel(source)
        lastVm!!.entranceShown()
        source.snapshot.value = snapshot(10).copy(day = 2)
        assertTrue(state.value.playEntrance)
    }
}
