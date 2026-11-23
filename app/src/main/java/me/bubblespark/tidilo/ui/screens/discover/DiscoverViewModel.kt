package me.bubblespark.tidilo.ui.screens.discover

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.TaskStatus
import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.data.discover.DiscoverRepository
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.DiscoverSnapshot
import me.bubblespark.tidilo.data.discover.MapBubble
import me.bubblespark.tidilo.data.discover.RisingGroup
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem

/** Everything Discover reads and writes, behind one seam so the ViewModel is JVM-testable. */
internal interface DiscoverSource {
    val privateVisible: StateFlow<Boolean>
    fun snapshot(): Flow<DiscoverSnapshot>
    fun justSaved(): Flow<List<BookmarkItem>>
    fun freshRss(): Flow<List<DiscoverPick>>
    fun processingTasks(): Flow<List<ImportTask>>
    fun errorTasks(): Flow<List<ImportTask>>
    suspend fun dealDeck(): List<BookmarkItem>
    suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface)
    suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface)
    /** Toggles Like; returns the stored state afterwards, or null when the bookmark no longer exists. */
    suspend fun toggleLike(bookmarkId: String, surface: EngagementSurface): Boolean?
    /** Shelves already shown, or null before Discover recorded its first baseline. */
    fun seenShelves(): Set<DiscoverShelf>?
    fun saveSeenShelves(shelves: Set<DiscoverShelf>)
}

/** Production [DiscoverSource] over [DiscoverRepository], [ImportRepository], and `tidilo_library_prefs`. */
internal class RepositoryDiscoverSource(
    private val discover: DiscoverRepository,
    private val imports: ImportRepository,
    private val bookmarks: BookmarkRepository,
    private val prefs: SharedPreferences,
    override val privateVisible: StateFlow<Boolean> = AppConfig.isPrivateContentVisible
) : DiscoverSource {
    override fun snapshot() = discover.observeSnapshot()
    override fun justSaved() = discover.observeJustSaved()
    override fun freshRss() = discover.observeFreshRss()
    override fun processingTasks(): Flow<List<ImportTask>> = imports.processingTasks
    override fun errorTasks(): Flow<List<ImportTask>> = imports.errorTasks
    override suspend fun dealDeck() = discover.dealDeck()
    override suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface) = discover.recordImpression(bookmarkId, surface)
    override suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface) = discover.recordDeckFlick(bookmarkId, surface)
    override suspend fun toggleLike(bookmarkId: String, surface: EngagementSurface) =
        bookmarks.toggleLike(bookmarkId, System.currentTimeMillis(), surface)
    override fun seenShelves(): Set<DiscoverShelf>? =
        runCatching { prefs.getStringSet(SEEN_SHELVES_KEY, null) }.getOrNull()
            ?.mapNotNull { name -> DiscoverShelf.entries.firstOrNull { it.name == name } }?.toSet()
    override fun saveSeenShelves(shelves: Set<DiscoverShelf>) {
        runCatching { prefs.edit().putStringSet(SEEN_SHELVES_KEY, shelves.mapTo(HashSet()) { it.name }).apply() }
    }

    companion object {
        private const val PREFS_NAME = "tidilo_library_prefs"
        private const val SEEN_SHELVES_KEY = "discover_seen_shelves"

        fun create(context: Context = TidiloApplication.instance) = RepositoryDiscoverSource(
            DiscoverRepository.getInstance(context),
            ImportRepository.getInstance(context),
            BookmarkRepository.getInstance(context),
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
}

/** One card of "Just saved" (§4.5): an import still running, a failed import, or a saved bookmark. */
@Immutable
sealed interface JustSavedEntry {
    val key: String
    data class InProgress(val task: ImportTask) : JustSavedEntry { override val key get() = "task_${task.id}" }
    data class Failed(val task: ImportTask) : JustSavedEntry { override val key get() = "task_${task.id}" }
    data class Saved(val item: BookmarkItem) : JustSavedEntry { override val key get() = item.id }
}

enum class Greeting { PICK, GROWING, NONE }

/** The shuffle deck: [cards] in order, [index] of the top card, and like states changed while it was open. */
@Immutable
data class DeckState(val cards: List<BookmarkItem> = emptyList(), val index: Int = 0, val likes: Map<String, Boolean> = emptyMap()) {
    val current: BookmarkItem? get() = cards.getOrNull(index)
    val exhausted: Boolean get() = cards.isNotEmpty() && index >= cards.size
    fun isLiked(item: BookmarkItem): Boolean = likes[item.id] ?: item.isLiked

    /** Drops Private cards, keeping the top card (or the next visible one) on top. */
    fun withoutPrivate(): DeckState {
        if (cards.none { it.isPrivate }) return this
        val kept = cards.filterNot { it.isPrivate }
        return copy(cards = kept, index = cards.take(index).count { !it.isPrivate })
    }
}

@Immutable
data class ConnectionPair(val entity: String, val first: DiscoverPick, val second: DiscoverPick)

@Immutable
data class DiscoverUiState(
    val loading: Boolean = true,
    val libraryCount: Int = 0,
    val greeting: Greeting = Greeting.NONE,
    val hero: DiscoverPick? = null,
    val onThisDay: List<DiscoverPick> = emptyList(),
    val justSaved: List<JustSavedEntry> = emptyList(),
    val deck: DeckState = DeckState(),
    val sleepingGems: List<DiscoverPick> = emptyList(),
    val rising: List<RisingGroup> = emptyList(),
    val freshRss: List<DiscoverPick> = emptyList(),
    val connection: ConnectionPair? = null,
    val map: List<MapBubble> = emptyList(),
    val showHowToSave: Boolean = false,
    val newShelfCaption: DiscoverShelf? = null
) {
    /** Shelves with content, in display order (§4.2). */
    val presentShelves: List<DiscoverShelf>
        get() = buildList {
            if (hero != null) add(DiscoverShelf.HERO)
            if (onThisDay.isNotEmpty()) add(DiscoverShelf.ON_THIS_DAY)
            if (justSaved.isNotEmpty()) add(DiscoverShelf.JUST_SAVED)
            if (deck.cards.isNotEmpty()) add(DiscoverShelf.DECK)
            if (sleepingGems.isNotEmpty()) add(DiscoverShelf.SLEEPING_GEMS)
            if (rising.isNotEmpty()) add(DiscoverShelf.RISING)
            if (freshRss.isNotEmpty()) add(DiscoverShelf.FRESH_RSS)
            if (connection != null) add(DiscoverShelf.CONNECTIONS)
            if (map.isNotEmpty()) add(DiscoverShelf.MAP)
        }
}

internal const val HOW_TO_SAVE_MAX_LIBRARY = 49

/**
 * Discover home state (§4). Combines the daily snapshot, live shelves, running imports, and the deck.
 * The deck is dealt once per ViewModel (one Discover visit) and again on [dealAnother].
 */
class DiscoverViewModel internal constructor(
    private val source: DiscoverSource,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {
    constructor() : this(RepositoryDiscoverSource.create())

    private val scope = coroutineScope ?: viewModelScope
    private val deck = MutableStateFlow(DeckState())
    private var seen: Set<DiscoverShelf>? = null
    private val caption = MutableStateFlow<DiscoverShelf?>(null)
    private var lastLibraryCount: Int? = null

    private val tasks = combine(source.processingTasks(), source.errorTasks(), source.privateVisible) { running, failed, visible ->
        fun ImportTask.shown() = rssFeedId == null && (visible || !isPrivate)
        running.filter { it.shown() && (it.status == TaskStatus.QUEUED || it.status == TaskStatus.PROCESSING) }
            .map { JustSavedEntry.InProgress(it) } +
            failed.filter { it.shown() && it.status == TaskStatus.ERROR }.map { JustSavedEntry.Failed(it) }
    }

    val uiState: StateFlow<DiscoverUiState> = combine(
        source.snapshot(),
        source.justSaved(),
        source.freshRss(),
        tasks,
        combine(deck, caption) { d, c -> d to c }
    ) { snapshot, justSaved, freshRss, taskEntries, (deckState, captionShelf) ->
        val count = snapshot.libraryCount
        // The first save into an empty library deals the deck that the empty open could not.
        if (lastLibraryCount == 0 && count > 0 && deckState.cards.isEmpty()) dealAnother()
        lastLibraryCount = count
        val connection = snapshot.connection?.let { (a, b) -> ConnectionPair(snapshot.connectionEntity.orEmpty(), a, b) }
        val state = if (count == 0) {
            DiscoverUiState(loading = false, libraryCount = 0, justSaved = taskEntries)
        } else {
            DiscoverUiState(
                loading = false,
                libraryCount = count,
                greeting = if (snapshot.hero != null) Greeting.PICK else Greeting.GROWING,
                hero = snapshot.hero,
                onThisDay = snapshot.onThisDay,
                justSaved = taskEntries + justSaved.map { JustSavedEntry.Saved(it) },
                deck = deckState,
                sleepingGems = snapshot.sleepingGems,
                rising = snapshot.rising,
                freshRss = freshRss,
                connection = connection,
                map = snapshot.map,
                showHowToSave = count <= HOW_TO_SAVE_MAX_LIBRARY
            )
        }
        noteShelves(state.presentShelves.toSet())
        state.copy(newShelfCaption = caption.value ?: captionShelf)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), DiscoverUiState())

    init {
        dealAnother()
        // Hiding Private content removes Private cards already dealt; later draws exclude them at the source.
        scope.launch {
            source.privateVisible.collect { visible -> if (!visible) deck.update { it.withoutPrivate() } }
        }
    }

    /**
     * Shows a one-time caption for a shelf that appears for the first time after Discover was first seen
     * (§4.6). The first state ever only records the baseline, so an existing library shows no captions.
     * "Just saved" and the deck are part of every cold start and never get a caption. One caption shows per
     * visit; other new shelves stay unseen so they get their caption on a later visit.
     */
    private fun noteShelves(present: Set<DiscoverShelf>) {
        val shown = present - UNCAPTIONED_SHELVES
        val known = seen ?: source.seenShelves()
        if (known == null) {
            seen = shown
            source.saveSeenShelves(shown)
            return
        }
        seen = known
        if (caption.value != null) return
        val first = DiscoverShelf.entries.firstOrNull { it in shown && it !in known } ?: return
        caption.value = first
        seen = known + first
        source.saveSeenShelves(known + first)
    }

    /** Draws a fresh deck (on open and on "Deal another"). */
    fun dealAnother() = launchLogged("deal the deck") {
        val cards = source.dealDeck()
        deck.value = DeckState(cards)
    }

    /** "Next card": records a flick for the top card and advances. */
    fun nextCard() {
        val top = deck.value.current ?: return
        deck.update { it.copy(index = it.index + 1) }
        launchLogged("record a deck flick") { source.recordDeckFlick(top.id, EngagementSurface.DISCOVER_DECK) }
    }

    /**
     * Likes or unlikes a deck card. The heart flips at once; the stored result then replaces it, and a
     * failure or a bookmark that no longer exists restores the previous state.
     */
    fun toggleDeckLike(item: BookmarkItem) {
        val before = deck.value.likes[item.id]
        val optimistic = !deck.value.isLiked(item)
        deck.update { it.copy(likes = it.likes + (item.id to optimistic)) }
        fun restore() = deck.update { state ->
            state.copy(likes = if (before == null) state.likes - item.id else state.likes + (item.id to before))
        }
        scope.launch {
            try {
                val stored = source.toggleLike(item.id, EngagementSurface.DISCOVER_DECK)
                if (stored == null) restore() else deck.update { it.copy(likes = it.likes + (item.id to stored)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                restore()
                logError("toggle a deck like", error)
            }
        }
    }

    fun onImpression(id: String, shelf: DiscoverShelf) =
        launchLogged("record an impression") { source.recordImpression(id, surfaceOf(shelf)) }

    /** Runs [block], logging failures but never swallowing cancellation. */
    private fun launchLogged(action: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logError(action, error)
            }
        }
    }

    // android.util.Log is a stub on the JVM; a logging failure must never change the outcome.
    private fun logError(action: String, error: Exception) {
        runCatching { Log.e(TAG, "Failed to $action", error) }
    }

    private companion object {
        const val TAG = "DiscoverViewModel"
        val UNCAPTIONED_SHELVES = setOf(DiscoverShelf.JUST_SAVED, DiscoverShelf.DECK)
    }
}
