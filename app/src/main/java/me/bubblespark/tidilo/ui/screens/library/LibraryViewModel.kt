package me.bubblespark.tidilo.ui.screens.library

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import kotlinx.coroutines.flow.onStart
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.TagKind
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

import me.bubblespark.tidilo.data.BookmarkSource
import me.bubblespark.tidilo.data.BookmarkSearchResult
import java.text.Collator
import java.util.Locale

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibraryFilterMode {
    DOMAIN,
    NEWEST_FIRST,
    COLLECTION,
    RSS
}

/** Newest first is manual-only unless the user includes RSS; other destinations keep their fixed source. */
internal fun libraryBookmarkSource(mode: LibraryFilterMode, includeRss: Boolean): BookmarkSource = when {
    mode == LibraryFilterMode.RSS -> BookmarkSource.RSS
    mode == LibraryFilterMode.NEWEST_FIRST && includeRss -> BookmarkSource.ALL
    else -> BookmarkSource.MANUAL
}

/**
 * An opaque Library paging window. Screens retain it while composed and release it on disposal;
 * [LibraryViewModel] alone decides when its cache is kept or cancelled.
 */
internal interface LibraryPages {
    /** Filter key of this window; grids use it as their scroll-anchor generation. */
    val key: String
    val flow: Flow<PagingData<BookmarkItem>>

    /** Whether the window's Pager cache is still alive. */
    val isActive: Boolean
}

internal class SearchFocusRequestTracker {
    private val _trigger = MutableStateFlow(0L)
    val trigger: StateFlow<Long> = _trigger.asStateFlow()
    private val sequence = java.util.concurrent.atomic.AtomicLong(0L)

    fun requestFocus(): Long {
        val next = sequence.incrementAndGet()
        _trigger.value = next
        return next
    }

    fun consumeFocus(token: Long): Boolean {
        if (token <= 0L) return false
        return _trigger.compareAndSet(token, 0L)
    }
}

/** One-shot UI events; [LibraryViewModel.events] has a single collector in `TidiloNavGraph`. */
internal sealed interface LibraryEvent {
    data object SaveFailed : LibraryEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel internal constructor(
    private val store: LibraryStore,
    private val savedState: SavedStateHandle,
    private val privateVisible: StateFlow<Boolean>,
    coroutineScope: CoroutineScope? = null,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    /** Reflection-based factories (`viewModel()`, `ViewModelProvider`) use these constructors. */
    constructor() : this(SavedStateHandle())
    constructor(savedState: SavedStateHandle) : this(
        RepositoryLibraryStore.create(), savedState, AppConfig.isPrivateContentVisible
    )

    private val scope = coroutineScope ?: viewModelScope

    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)
    internal val events: SharedFlow<LibraryEvent> = _events.asSharedFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val debouncedSearchQuery = _searchQuery
        .debounce { if (it.isBlank()) 0L else 250L }
        .distinctUntilChanged()

    private val searchFocusTracker = SearchFocusRequestTracker()
    val searchFocusTrigger: StateFlow<Long> = searchFocusTracker.trigger

    fun triggerSearchFocus() {
        searchFocusTracker.requestFocus()
    }

    fun consumeSearchFocus(token: Long) {
        searchFocusTracker.consumeFocus(token)
    }

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _viewMode = MutableStateFlow(store.loadViewMode())
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    fun setViewMode(mode: LibraryViewMode) {
        if (_viewMode.value != mode) {
            _viewMode.value = mode
            store.saveViewMode(mode)
        }
    }

    private val _includeRssInNewestFirst = MutableStateFlow(store.loadIncludeRssInNewestFirst())
    /** Newest first source scope; shared by List and Grid and kept across filter-mode changes. */
    val includeRssInNewestFirst: StateFlow<Boolean> = _includeRssInNewestFirst.asStateFlow()

    fun setIncludeRssInNewestFirst(include: Boolean) {
        if (_includeRssInNewestFirst.value != include) {
            _includeRssInNewestFirst.value = include
            store.saveIncludeRssInNewestFirst(include)
        }
    }

    private val _filterMode = MutableStateFlow(store.loadFilterMode())
    val filterMode: StateFlow<LibraryFilterMode> = _filterMode.asStateFlow()

    val selectedCollectionId = savedState.getStateFlow<String?>("selectedCollectionId", null)
    fun selectCollectionFilter(id: String?) { savedState["selectedCollectionId"] = id }
    private val collectionReload = MutableStateFlow(0)
    fun retryCollections() { collectionReload.value++ }
    val collectionState = combine(privateVisible, collectionReload) { visible, _ -> visible }
        .flatMapLatest { visible -> store.observeCollections(visible)
            .map { CollectionLibraryState(rows = it, loading = false) }
            .onStart { emit(CollectionLibraryState()) }
            .catch { failure -> emit(CollectionLibraryState(loading = false, error = failure)) }
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), CollectionLibraryState())

    private val _selectedDomainFilter = MutableStateFlow<String?>(null)
    val selectedDomainFilter: StateFlow<String?> = _selectedDomainFilter.asStateFlow()

    private val _selectedRssFeedFilter = MutableStateFlow<me.bubblespark.tidilo.data.db.RssFeedEntity?>(null)
    val selectedRssFeedFilter = _selectedRssFeedFilter.asStateFlow()

    fun selectRssFeedFilter(feed: me.bubblespark.tidilo.data.db.RssFeedEntity?) {
        _selectedRssFeedFilter.value = feed
    }

    fun setFilterMode(mode: LibraryFilterMode) {
        if (_filterMode.value != mode) {
            _filterMode.value = mode
            scope.launch { store.saveFilterMode(mode) }
            _selectedDomainFilter.value = null
            _selectedRssFeedFilter.value = null
            selectCollectionFilter(null)
        }
    }

    fun selectDomainFilter(domain: String?) {
        _selectedDomainFilter.value = domain
    }

    fun clearDomainFilter() {
        _selectedDomainFilter.value = null
    }

    /**
     * Domain card list (for 2-column grid).
     * Sorted by domain name (A-Z ASCII first, followed by Japanese collator).
     */
    private val _domainGroupsLoading = MutableStateFlow(true)
    val domainGroupsLoading = _domainGroupsLoading.asStateFlow()

    private val _rssFeedGroupsLoading = MutableStateFlow(true)
    val rssFeedGroupsLoading = _rssFeedGroupsLoading.asStateFlow()
    val rssFeedGroups = privateVisible.flatMapLatest { visible ->
        store.observeRssFeedGroups(visible).onStart { _rssFeedGroupsLoading.value = true }
    }.map { groups ->
        val collator = Collator.getInstance(Locale.JAPANESE)
        val sorted = groups.sortedWith { a, b ->
            val asciiA = a.feed.title.firstOrNull()?.code?.let { it < 128 } == true
            val asciiB = b.feed.title.firstOrNull()?.code?.let { it < 128 } == true
            when {
                asciiA != asciiB -> if (asciiA) -1 else 1
                else -> collator.compare(a.feed.title, b.feed.title).takeIf { it != 0 }
                    ?: a.feed.id.compareTo(b.feed.id)
            }
        }
        _rssFeedGroupsLoading.value = false
        sorted
    }.flowOn(computeDispatcher).stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val domainGroups: StateFlow<List<DomainGroupItem>> =
        privateVisible.flatMapLatest { visible ->
            store.observeDomainGroups(visible).onStart { _domainGroupsLoading.value = true }
        }.map { groups ->
            _domainGroupsLoading.value = false
            val collator = Collator.getInstance(Locale.JAPANESE)
            groups.sortedWith { a, b ->
                val isAsciiA = a.displayName.isNotEmpty() && a.displayName[0].code < 128
                val isAsciiB = b.displayName.isNotEmpty() && b.displayName[0].code < 128
                if (isAsciiA && !isAsciiB) {
                    -1
                } else if (!isAsciiA && isAsciiB) {
                    1
                } else {
                    collator.compare(a.displayName, b.displayName)
                }
            }
        }
        .flowOn(computeDispatcher)
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** A cancellable child of [scope] for one Pager cache. */
    private fun childScope() = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    private var currentSearchScope: CoroutineScope? = null
    private fun newSearchGenerationScope(): CoroutineScope {
        currentSearchScope?.cancel()
        val generationScope = childScope()
        currentSearchScope = generationScope
        return generationScope
    }

    private class LibraryPagingSession(
        override val key: String,
        override val flow: Flow<PagingData<BookmarkItem>>,
        val cacheScope: CoroutineScope
    ) : LibraryPages {
        var users: Int = 0
        override val isActive: Boolean get() = cacheScope.isActive
    }

    private var currentLibrarySession: LibraryPagingSession? = null

    /** Retain the selected window across detail navigation; outgoing destinations release on exit. */
    internal fun libraryPages(
        mode: LibraryFilterMode,
        domain: String?,
        feedId: String?,
        visible: Boolean,
        collectionId: String? = null,
        includeRss: Boolean = false
    ): LibraryPages {
        val key = buildLibraryFilterKey(mode, domain, feedId, visible, collectionId, includeRss)
        currentLibrarySession?.takeIf { it.key == key }?.let { return it }
        currentLibrarySession?.takeIf { it.users == 0 }?.cacheScope?.cancel()
        val cacheScope = childScope()
        val pages = store.isInitialized.flatMapLatest { ready ->
            if (ready && mode == LibraryFilterMode.COLLECTION && collectionId != null) store.pagedCollectionBookmarks(collectionId, visible)
            else if (ready) store.pagedBookmarks(
                domain, visible,
                libraryBookmarkSource(mode, includeRss),
                rssFeedId = feedId.takeIf { mode == LibraryFilterMode.RSS }
            ) else flowOf(loadingPage())
        }.cachedIn(cacheScope)
        return LibraryPagingSession(key, pages, cacheScope).also { currentLibrarySession = it }
    }

    /** Called when a composition starts showing [pages]. */
    internal fun retainLibraryPages(pages: LibraryPages) {
        (pages as LibraryPagingSession).users++
    }

    /** Called when a composition showing [pages] is disposed. */
    internal fun releaseLibraryPages(pages: LibraryPages) {
        val session = pages as LibraryPagingSession
        session.users--
        val mode = _filterMode.value
        val selectedKey = buildLibraryFilterKey(mode,
            _selectedDomainFilter.value.takeIf { mode == LibraryFilterMode.DOMAIN },
            _selectedRssFeedFilter.value?.id.takeIf { mode == LibraryFilterMode.RSS },
            privateVisible.value,
            selectedCollectionId.value.takeIf { mode == LibraryFilterMode.COLLECTION },
            mode == LibraryFilterMode.NEWEST_FIRST && _includeRssInNewestFirst.value)
        // A superseded window (replaced while still composed) is never reused, even if its key is selected again.
        if (session.users == 0 && (session.key != selectedKey || currentLibrarySession !== session)) {
            session.cacheScope.cancel()
            if (currentLibrarySession === session) currentLibrarySession = null
        }
    }

    /** A rapid A-to-B-to-A transition may reuse A's existing animated composition. */
    internal fun activateLibraryPages(pages: LibraryPages) {
        val session = pages as LibraryPagingSession
        if (currentLibrarySession === session) return
        currentLibrarySession?.takeIf { it.users == 0 }?.cacheScope?.cancel()
        currentLibrarySession = session
    }

    private fun buildSearchGeneration(
        query: String,
        visible: Boolean,
        ready: Boolean
    ): PagingGeneration<BookmarkSearchResult> {
        val key = buildSearchQueryKey(query, visible)
        val flow = if (ready) {
            store.pagedSearchResults(query, visible)
        } else {
            flowOf(loadingPage())
        }
        return PagingGeneration(key, flow.cachedIn(newSearchGenerationScope()))
    }

    private var activeSearchGeneration: PagingGeneration<BookmarkSearchResult>? = null
    private var activeSearchReady: Boolean = false

    /**
     * Active generation of search bookmarks, associating the flow with its debounced query key.
     */
    internal val searchGeneration: StateFlow<PagingGeneration<BookmarkSearchResult>> = combine(
        debouncedSearchQuery,
        privateVisible,
        store.isInitialized
    ) { query, visible, ready ->
        Triple(query, visible, ready)
    }.map { (query, visible, ready) ->
        val key = buildSearchQueryKey(query, visible)
        val current = activeSearchGeneration
        if (current != null && current.key == key && activeSearchReady == ready) {
            current
        } else {
            activeSearchReady = ready
            buildSearchGeneration(query, visible, ready).also {
                activeSearchGeneration = it
            }
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = run {
            val query = _searchQuery.value
            val visible = privateVisible.value
            val ready = store.isInitialized.value
            activeSearchReady = ready
            buildSearchGeneration(query, visible, ready).also {
                activeSearchGeneration = it
            }
        }
    )

    internal fun libraryCount(mode: LibraryFilterMode, domain: String?, feedId: String?, visible: Boolean, collectionId: String? = null, includeRss: Boolean = false): Flow<Int?> =
        if (mode == LibraryFilterMode.COLLECTION && collectionId != null) store.observeCollectionCount(collectionId, visible)
        else store.observeCount("", domain, visible,
            libraryBookmarkSource(mode, includeRss),
            rssFeedId = feedId.takeIf { mode == LibraryFilterMode.RSS })

    val searchCount = combine(debouncedSearchQuery, privateVisible) { query, visible -> query to visible }
        .flatMapLatest { (query, visible) -> store.observeCount(query, null, visible) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), 0)

    val isInitialized = store.isInitialized
    val initializationFailure = store.initializationFailure
    fun retryInitialization() = store.retryInitialization()

    private fun recordEngagement(showFailure: Boolean = false, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The store logs the failure; only a like toggle tells the user.
                if (showFailure) _events.emit(LibraryEvent.SaveFailed) // suspends on a full buffer instead of dropping
            }
        }
    }

    fun toggleLike(id: String) = recordEngagement(showFailure = true) { store.toggleLike(id) }
    fun recordBrowserOpen(id: String) = recordEngagement { store.recordBrowserOpen(id) }
    fun recordDetailView(id: String) = recordEngagement { store.recordDetailView(id) }
    fun recordSummaryPeek(id: String) = recordEngagement { store.recordSummaryPeek(id) }

    fun bookmarkDetails(id: String): Flow<BookmarkDetailState> = store.observeBookmark(id)
        .map { BookmarkDetailState(it, false) }
        .catch { emit(BookmarkDetailState(loading = false, error = it)) }
        .onStart { emit(BookmarkDetailState()) }

    internal fun tagCounts(selected: Set<String>, kind: TagKind): Flow<Map<String, Int>> =
        privateVisible.flatMapLatest { visible ->
            store.observeTagCounts(selected, visible, kind)
        }.map { rows -> rows.associate { it.value to it.count } }

    fun entityLibrary(): Flow<EntityLibraryState> = store.observeEntityLibrary()
        .map { EntityLibraryState(it, loading = false) }
        .catch { emit(EntityLibraryState(loading = false, error = it)) }
        .onStart { emit(EntityLibraryState()) }

    /**
     * Shared by every Detail note autosaver, so a final write flushed by a disposed screen (rotation,
     * quick reopen) can never land after a newer write from its replacement.
     */
    internal val noteWriteLock = Mutex()

    suspend fun saveNote(id: String, note: String) = store.saveNote(id, note)

    private val mutableTagSaves = MutableStateFlow<Map<String, TagSaveStatus>>(emptyMap())

    /** Tag-dialog commits per bookmark; kept here so a commit and its result survive Detail recreation. */
    internal val tagSaves: StateFlow<Map<String, TagSaveStatus>> = mutableTagSaves.asStateFlow()

    /** Commits a tag-dialog edit once; the result stays in [tagSaves] until [consumeTagSave]. */
    fun commitBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) {
        if (mutableTagSaves.value[id] == TagSaveStatus.Saving) return
        mutableTagSaves.update { it + (id to TagSaveStatus.Saving) }
        scope.launch {
            val result = try {
                store.updateBookmarkTags(id, tag, isPrivate, replacements)
                TagSaveStatus.Saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TagSaveStatus.Failed
            }
            mutableTagSaves.update { it + (id to result) }
        }
    }

    fun consumeTagSave(id: String) {
        mutableTagSaves.update { it - id }
    }

    suspend fun applyAiProposal(id: String, description: String? = null, tag: Tag? = null) =
        store.applyAiProposal(id, description, tag)


    suspend fun deleteBookmark(id: String): Boolean = store.moveToTrash(id)

    fun bookmarkHasCollections(id: String): Flow<Boolean> = store.observeMembershipIds(id)
        .map { it.isNotEmpty() }.catch { emit(false) }

    init {
        scope.launch { _searchHistory.value = store.loadSearchHistory() }
    }

    private fun saveSearchHistory(history: List<String>) {
        scope.launch { store.saveSearchHistory(history) }
    }

    /**
     * Adds search history (max 10 items, newest first, deduplicated).
     * Keeps full query string as single history entry.
     */
    fun addSearchHistory(query: String) {
        val updated = updateHistoryList(_searchHistory.value, query, limit = 10)
        if (updated == _searchHistory.value) return
        _searchHistory.value = updated
        saveSearchHistory(updated)
    }

    fun removeSearchHistoryItem(query: String) {
        val updated = _searchHistory.value.filter { it != query }
        _searchHistory.value = updated
        saveSearchHistory(updated)
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        scope.launch { store.clearSearchHistory() }
    }



    /**
     * Updates the raw query. Matching (AND of words and `#` tags, bilingual taxonomy labels) runs in SQL
     * through [LibraryStore.pagedSearchResults] once the query is debounced.
     */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearchQuery() {
        _searchQuery.value = ""
    }

    /**
     * Extracts only #-prefixed tags from the current search query.
     */
    fun getSelectedTagsFromCurrentQuery(): Set<String> {
        val current = _searchQuery.value
        if (current.isBlank()) return emptySet()
        return current.replace("　", " ")
            .split(" ")
            .filter { it.startsWith("#") && it.length > 1 }
            .map { it.removePrefix("#") }
            .toSet()
    }

    /**
     * Updates the search query with selected tags, retaining existing free-text words.
     */
    fun updateQueryWithSelectedTags(selectedTags: Set<String>) {
        val words = _searchQuery.value
            .replace("　", " ")
            .split(" ")
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val tagStrings = selectedTags.map { "#$it" }
        val newQuery = (words + tagStrings).joinToString(" ")
        _searchQuery.value = newQuery
        if (newQuery.isNotBlank()) {
            addSearchHistory(newQuery)
        }
    }

    private fun <T : Any> loadingPage(): PagingData<T> = PagingData.empty(
        sourceLoadStates = LoadStates(LoadState.Loading, LoadState.NotLoading(false), LoadState.NotLoading(false))
    )

    override fun onCleared() {
        super.onCleared()
        currentLibrarySession?.cacheScope?.cancel()
        currentSearchScope?.cancel()
    }

    companion object {
        /**
         * Pure function to update search history list (bounds count, prepends newest, deduplicates exact matches).
         */
        fun updateHistoryList(current: List<String>, newQuery: String, limit: Int = 10): List<String> {
            val trimmed = newQuery.trim().replace("\\s+".toRegex(), " ")
            if (trimmed.isBlank()) return current
            return (listOf(trimmed) + current.filter { it != trimmed }).take(limit)
        }

    }
}

data class BookmarkDetailState(val bookmark: BookmarkItem? = null, val loading: Boolean = true, val error: Throwable? = null)

/** A missing lookup is distinct from a successfully loaded empty library. */
data class EntityLibraryState(val spellings: Map<String, String> = emptyMap(), val loading: Boolean = true, val error: Throwable? = null)

data class CollectionLibraryState(val rows: List<me.bubblespark.tidilo.data.db.CollectionWithCount> = emptyList(), val loading: Boolean = true, val error: Throwable? = null)

/** Progress of a tag-dialog commit for one bookmark. */
internal enum class TagSaveStatus { Saving, Saved, Failed }
