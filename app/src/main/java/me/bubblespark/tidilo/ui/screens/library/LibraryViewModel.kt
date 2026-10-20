package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

import android.content.Context
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.Subcategory
import org.json.JSONArray
import java.text.Collator
import java.util.Locale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LibraryFilterMode {
    DOMAIN,
    NEWEST_FIRST
}

class LibraryViewModel : ViewModel() {

    private val prefs by lazy {
        TidiloApplication.instance.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
    }

    private val bookmarkRepository = BookmarkRepository.getInstance()
    val rawBookmarks: StateFlow<List<BookmarkItem>> = bookmarkRepository.bookmarks

    val isLoading: StateFlow<Boolean> = bookmarkRepository.isLoaded
        .map { !it }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = !bookmarkRepository.isLoaded.value
        )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchFocusTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val searchFocusTrigger: SharedFlow<Unit> = _searchFocusTrigger.asSharedFlow()

    fun triggerSearchFocus() {
        _searchFocusTrigger.tryEmit(Unit)
    }

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    // Bookmark list filtered based on NSFW visibility flag
    val bookmarks: StateFlow<List<BookmarkItem>> =
        combine(bookmarkRepository.bookmarks, me.bubblespark.tidilo.config.AppConfig.isNsfwContentVisible) { allItems, isNsfwVisible ->
            if (isNsfwVisible) allItems else allItems.filter { !it.isNsfw }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = bookmarkRepository.bookmarks.value.let { all ->
                if (me.bubblespark.tidilo.config.AppConfig.isNsfwContentVisible.value) all else all.filter { !it.isNsfw }
            }
        )

    private fun loadFilterMode(): LibraryFilterMode {
        val saved = prefs.getString("library_filter_mode", null)
        return if (saved == LibraryFilterMode.DOMAIN.name) {
            LibraryFilterMode.DOMAIN
        } else {
            LibraryFilterMode.NEWEST_FIRST
        }
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
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putString("library_view_mode", mode.name).apply()
        }
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

    fun setFilterMode(mode: LibraryFilterMode) {
        if (_filterMode.value != mode) {
            _filterMode.value = mode
            saveFilterMode(mode)
            _selectedDomainFilter.value = null
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
    val domainGroups: StateFlow<List<DomainGroupItem>> =
        combine(bookmarks, DomainPolicyManager.policies) { currentBookmarks, _ ->
            val countMap = mutableMapOf<String, Int>()
            for (b in currentBookmarks) {
                val domain = DomainIconManager.extractDomain(b.url)
                if (!domain.isNullOrBlank()) {
                    countMap[domain] = (countMap[domain] ?: 0) + 1
                }
            }
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
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Bookmarks filtered by selected domain (Newest first).
     */
    val domainFilteredBookmarks: StateFlow<List<BookmarkItem>> =
        combine(bookmarks, selectedDomainFilter) { currentBookmarks, selectedDomain ->
            filterBookmarksByDomain(currentBookmarks, selectedDomain)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun getBookmarkById(id: String): BookmarkItem? {
        return rawBookmarks.value.find { it.id == id }
    }

    fun updateBookmarkNote(id: String, note: String) {
        bookmarkRepository.updateBookmarkNote(id, note)
    }

    fun updateBookmarkFields(id: String, description: String? = null, tag: Tag? = null) {
        bookmarkRepository.updateBookmarkFields(id, description, tag)
    }

    suspend fun updateBookmarkDetails(id: String, note: String, tag: Tag, isNsfw: Boolean) {
        bookmarkRepository.updateBookmarkDetails(id, note, tag, isNsfw)
    }

    fun deleteBookmark(id: String) {
        bookmarkRepository.moveToTrash(id)
    }

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
    val filteredBookmarks: StateFlow<List<BookmarkItem>> =
        combine(bookmarks, _searchQuery) { allItems, query ->
            filterBookmarks(allItems, query)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = bookmarks.value
        )

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
