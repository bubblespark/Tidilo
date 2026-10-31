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
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.TagKind
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

import android.content.Context
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.BookmarkSource
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.Subcategory
import org.json.JSONArray
import java.text.Collator
import java.util.Locale

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibraryFilterMode {
    DOMAIN,
    NEWEST_FIRST,
    RSS
}

internal class LibraryPagingSession(
    val key: String,
    val flow: Flow<PagingData<BookmarkItem>>,
    val cacheScope: CoroutineScope
) {
    var users: Int = 0
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel : ViewModel() {

    private val prefs by lazy {
        TidiloApplication.instance.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
    }

    private val bookmarkRepository = BookmarkRepository.getInstance()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val debouncedSearchQuery = _searchQuery
        .debounce { if (it.isBlank()) 0L else 250L }
        .distinctUntilChanged()

    private val _searchFocusTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchFocusTrigger: SharedFlow<Unit> = _searchFocusTrigger.asSharedFlow()

    fun triggerSearchFocus() {
        _searchFocusTrigger.tryEmit(Unit)
    }

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private fun loadFilterMode(): LibraryFilterMode {
        val saved = prefs.getString("library_filter_mode", null)
        return LibraryFilterMode.entries.firstOrNull { it.name == saved } ?: LibraryFilterMode.NEWEST_FIRST
    }

    private fun saveFilterMode(mode: LibraryFilterMode) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString("library_filter_mode", mode.name).apply()
        }
    }

    private fun loadViewMode(): LibraryViewMode {
        val saved = prefs.getString("library_view_mode", null)
        return if (saved == LibraryViewMode.GRID.name) {
            LibraryViewMode.GRID
        } else {
            LibraryViewMode.LIST
        }
    }

    private fun saveViewMode(mode: LibraryViewMode) {
        prefs.edit().putString("library_view_mode", mode.name).apply()
    }

    private val _viewMode = MutableStateFlow(loadViewMode())
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    fun setViewMode(mode: LibraryViewMode) {
        if (_viewMode.value != mode) {
            _viewMode.value = mode
            saveViewMode(mode)
        }
    }

    private val _filterMode = MutableStateFlow(loadFilterMode())
    val filterMode: StateFlow<LibraryFilterMode> = _filterMode.asStateFlow()

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
            saveFilterMode(mode)
            _selectedDomainFilter.value = null
            _selectedRssFeedFilter.value = null
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
    val rssFeedGroups = AppConfig.isNsfwContentVisible.flatMapLatest { visible ->
        bookmarkRepository.observeRssFeedGroups(visible).onStart { _rssFeedGroupsLoading.value = true }
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
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val domainGroups: StateFlow<List<DomainGroupItem>> =
        combine(AppConfig.isNsfwContentVisible.flatMapLatest { bookmarkRepository.observeDomainCounts(it, BookmarkSource.MANUAL).onStart { _domainGroupsLoading.value = true } }, DomainPolicyManager.policies) { counts, _ ->
            _domainGroupsLoading.value = false
            val countMap = counts.associate { it.domain to it.count }
            val collator = Collator.getInstance(Locale.JAPANESE)
            countMap.map { (domain, count) ->
                val displayName = DomainPolicyManager.getDisplayName(domain)
                DomainGroupItem(
                    domain = domain,
                    displayName = displayName,
                    count = count
                )
            }.sortedWith { a, b ->
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
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var currentSearchScope: CoroutineScope? = null
    private fun newSearchGenerationScope(): CoroutineScope {
        currentSearchScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + Job(viewModelScope.coroutineContext[Job]))
        currentSearchScope = scope
        return scope
    }

    private var currentLibrarySession: LibraryPagingSession? = null

    /** Retain the selected window across detail navigation; outgoing destinations release on exit. */
    internal fun libraryPages(
        mode: LibraryFilterMode,
        domain: String?,
        feedId: String?,
        visible: Boolean
    ): LibraryPagingSession {
        val key = buildLibraryFilterKey(mode, domain, feedId, visible)
        currentLibrarySession?.takeIf { it.key == key }?.let { return it }
        currentLibrarySession?.takeIf { it.users == 0 }?.cacheScope?.cancel()
        val scope = CoroutineScope(viewModelScope.coroutineContext + Job(viewModelScope.coroutineContext[Job]))
        val pages = bookmarkRepository.isInitialized.flatMapLatest { ready ->
            if (ready) bookmarkRepository.pagedBookmarks(
                "", domain, visible,
                if (mode == LibraryFilterMode.RSS) BookmarkSource.RSS else BookmarkSource.MANUAL,
                rssFeedId = feedId.takeIf { mode == LibraryFilterMode.RSS }
            ) else flowOf(loadingPage())
        }.cachedIn(scope)
        return LibraryPagingSession(key, pages, scope).also { currentLibrarySession = it }
    }

    internal fun releaseLibraryPages(session: LibraryPagingSession) {
        session.users--
        val mode = _filterMode.value
        val selectedKey = buildLibraryFilterKey(mode,
            _selectedDomainFilter.value.takeIf { mode == LibraryFilterMode.DOMAIN },
            _selectedRssFeedFilter.value?.id.takeIf { mode == LibraryFilterMode.RSS },
            AppConfig.isNsfwContentVisible.value)
        if (session.users == 0 && session.key != selectedKey) {
            session.cacheScope.cancel()
            if (currentLibrarySession === session) currentLibrarySession = null
        }
    }

    /** A rapid A-to-B-to-A transition may reuse A's existing animated composition. */
    internal fun activateLibraryPages(session: LibraryPagingSession) {
        if (currentLibrarySession === session) return
        currentLibrarySession?.takeIf { it.users == 0 }?.cacheScope?.cancel()
        currentLibrarySession = session
    }

    private fun buildSearchGeneration(
        query: String,
        visible: Boolean,
        ready: Boolean
    ): PagingGeneration {
        val key = buildSearchQueryKey(query, visible)
        val flow = if (ready) {
            bookmarkRepository.pagedBookmarks(query, null, visible)
        } else {
            flowOf(loadingPage())
        }
        val scope = newSearchGenerationScope()
        return PagingGeneration(key, flow.cachedIn(scope))
    }

    private var activeSearchGeneration: PagingGeneration? = null
    private var activeSearchReady: Boolean = false

    /**
     * Active generation of search bookmarks, associating the flow with its debounced query key.
     */
    val searchGeneration: StateFlow<PagingGeneration> = combine(
        debouncedSearchQuery,
        AppConfig.isNsfwContentVisible,
        bookmarkRepository.isInitialized
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
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = run {
            val query = _searchQuery.value
            val visible = AppConfig.isNsfwContentVisible.value
            val ready = bookmarkRepository.isInitialized.value
            activeSearchReady = ready
            buildSearchGeneration(query, visible, ready).also {
                activeSearchGeneration = it
            }
        }
    )

    internal fun libraryCount(mode: LibraryFilterMode, domain: String?, feedId: String?, visible: Boolean): Flow<Int?> =
        bookmarkRepository.observeCount("", domain, visible,
            if (mode == LibraryFilterMode.RSS) BookmarkSource.RSS else BookmarkSource.MANUAL,
            rssFeedId = feedId.takeIf { mode == LibraryFilterMode.RSS })

    val searchCount = combine(debouncedSearchQuery, AppConfig.isNsfwContentVisible) { query, visible -> query to visible }
        .flatMapLatest { (query, visible) -> bookmarkRepository.observeCount(query, null, visible) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isInitialized = bookmarkRepository.isInitialized
    val initializationFailure = bookmarkRepository.initializationFailure
    fun retryInitialization() = bookmarkRepository.retryInitialization()

    fun toggleLike(id: String) { viewModelScope.launch(Dispatchers.IO) { bookmarkRepository.toggleLike(id, System.currentTimeMillis()) } }
    fun recordBrowserOpen(id: String) { viewModelScope.launch(Dispatchers.IO) { bookmarkRepository.recordBrowserOpen(id, System.currentTimeMillis()) } }
    fun recordDetailView(id: String) { viewModelScope.launch(Dispatchers.IO) { bookmarkRepository.recordDetailView(id, System.currentTimeMillis()) } }
    fun recordSummaryPeek(id: String) { viewModelScope.launch(Dispatchers.IO) { bookmarkRepository.recordSummaryPeek(id, System.currentTimeMillis()) } }

    fun bookmarkDetails(id: String): Flow<BookmarkDetailState> = bookmarkRepository.observeBookmark(id)
        .map { BookmarkDetailState(it, false) }
        .catch { emit(BookmarkDetailState(loading = false, error = it)) }
        .onStart { emit(BookmarkDetailState()) }

    internal fun tagCounts(selected: Set<String>, kind: TagKind): Flow<Map<String, Int>> =
        AppConfig.isNsfwContentVisible.flatMapLatest { visible ->
            bookmarkRepository.observeTagCounts(selected, visible, kind)
        }.map { rows -> rows.associate { it.value to it.count } }

    fun entityLibrary(): Flow<EntityLibraryState> = bookmarkRepository.observeEntityLibrary()
        .map { EntityLibraryState(it, loading = false) }
        .catch { emit(EntityLibraryState(loading = false, error = it)) }
        .onStart { emit(EntityLibraryState()) }

    val entityNames = tagCounts(emptySet(), TagKind.ENTITY).map { it.keys.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun updateBookmarkNote(id: String, note: String) = bookmarkRepository.updateBookmarkNote(id, note)

    suspend fun updateBookmarkFields(id: String, description: String? = null, tag: Tag? = null) =
        bookmarkRepository.updateBookmarkFields(id, description, tag)

    suspend fun updateBookmarkDetails(id: String, note: String, tag: Tag, isNsfw: Boolean, replacements: Map<String, String> = emptyMap()) =
        bookmarkRepository.updateBookmarkDetails(id, note, tag, isNsfw, replacements)

    suspend fun deleteBookmark(id: String): Boolean = bookmarkRepository.moveToTrash(id)

    init {
        // Load search history on background IO
        viewModelScope.launch(Dispatchers.IO) {
            val history = loadSearchHistory()
            _searchHistory.value = history
        }
    }

    private fun loadSearchHistory(): List<String> {
        val json = prefs.getString("search_history", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list.take(10)
        }.getOrDefault(emptyList())
    }

    private fun saveSearchHistory(history: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val array = JSONArray()
            history.take(10).forEach { array.put(it) }
            prefs.edit().putString("search_history", array.toString()).apply()
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().remove("search_history").apply()
        }
    }



    /**
     * AND search logic supporting both free-text word search and tag search.
     * - Delimiter: half-width space (full-width spaces normalized to half-width)
     * - Word search (without '#' prefix): searches Title, Description, and URL
     * - Tag search (with '#' prefix): searches Category, Subcategory, or Entities
     * - Bilingual support (case-insensitive)
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

    private fun loadingPage(): PagingData<BookmarkItem> = PagingData.empty(
        sourceLoadStates = LoadStates(LoadState.Loading, LoadState.NotLoading(false), LoadState.NotLoading(false))
    )

    override fun onCleared() {
        super.onCleared()
        currentLibrarySession?.cacheScope?.cancel()
        currentSearchScope?.cancel()
    }

    companion object {
        fun filterBookmarks(items: List<BookmarkItem>, rawQuery: String): List<BookmarkItem> {
            if (rawQuery.isBlank()) return items

            // Delimit by half-width space (normalizing full-width spaces)
            val tokens = rawQuery.trim()
                .replace("　", " ")
                .split(" ")
                .filter { it.isNotBlank() }

            if (tokens.isEmpty()) return items

            return items.filter { item ->
                // Match all tokens (AND search)
                tokens.all { token ->
                    if (token.startsWith("#")) {
                        // Tag search: strip '#' and search category, subcategory, or entity
                        val tagQuery = token.removePrefix("#").trim()
                        if (tagQuery.isEmpty()) {
                            true // Allow all tags if solitary '#' is entered
                        } else {
                            // Category (English ID or Japanese label)
                            val catMatch = item.tag.category?.contains(tagQuery, ignoreCase = true) == true ||
                                    (item.tag.category?.let { Category.fromId(it) }?.let {
                                         it.jaLabel.contains(tagQuery, ignoreCase = true) ||
                                                 it.enLabel.contains(tagQuery, ignoreCase = true)
                                     } ?: false)

                            // Subcategory (English ID or Japanese label)
                            val subMatch = item.tag.subcategory?.contains(tagQuery, ignoreCase = true) == true ||
                                    (item.tag.subcategory?.let { Subcategory.fromId(it) }?.let {
                                         it.jaLabel.contains(tagQuery, ignoreCase = true) ||
                                                 it.enLabel.contains(tagQuery, ignoreCase = true)
                                     } ?: false)

                            // Entity category (specific keyword list)
                            val entityMatch = item.tag.entities.any { entity ->
                                entity.contains(tagQuery, ignoreCase = true)
                            }

                            catMatch || subMatch || entityMatch
                        }
                    } else {
                        // Word search: Title, Description, URL
                        item.title.contains(token, ignoreCase = true) ||
                                item.description.contains(token, ignoreCase = true) ||
                                item.url.contains(token, ignoreCase = true)
                    }
                }
            }
        }

        /**
         * Pure function to update search history list (bounds count, prepends newest, deduplicates exact matches).
         */
        fun updateHistoryList(current: List<String>, newQuery: String, limit: Int = 10): List<String> {
            val trimmed = newQuery.trim().replace("\\s+".toRegex(), " ")
            if (trimmed.isBlank()) return current
            return (listOf(trimmed) + current.filter { it != trimmed }).take(limit)
        }

        /**
         * Filter bookmarks by domain (case-insensitive normalized).
         * Returns empty list if domain is null or blank.
         */
        fun filterBookmarksByDomain(items: List<BookmarkItem>, domain: String?): List<BookmarkItem> {
            if (domain.isNullOrBlank()) return emptyList()
            val normalized = domain.trim().lowercase()
            return items.filter {
                DomainIconManager.extractDomain(it.url)?.lowercase() == normalized
            }
        }
    }
}

data class BookmarkDetailState(val bookmark: BookmarkItem? = null, val loading: Boolean = true, val error: Throwable? = null)

/** A missing lookup is distinct from a successfully loaded empty library. */
data class EntityLibraryState(val spellings: Map<String, String> = emptyMap(), val loading: Boolean = true, val error: Throwable? = null)
