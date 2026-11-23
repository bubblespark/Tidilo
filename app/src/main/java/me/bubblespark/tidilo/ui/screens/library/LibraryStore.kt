package me.bubblespark.tidilo.ui.screens.library

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.paging.PagingData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.BookmarkSearchResult
import me.bubblespark.tidilo.data.BookmarkSource
import me.bubblespark.tidilo.data.CollectionRepository
import me.bubblespark.tidilo.data.DomainPolicyStore
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.db.CollectionWithCount
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.TagKind
import org.json.JSONArray

/** Every read, write, and preference [LibraryViewModel] needs; substitutable in JVM tests. */
internal interface LibraryStore {
    val isInitialized: StateFlow<Boolean>
    val initializationFailure: StateFlow<Throwable?>
    fun retryInitialization()

    fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?): Flow<PagingData<BookmarkItem>>
    fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean): Flow<PagingData<BookmarkItem>>
    fun pagedSearchResults(query: String, showPrivate: Boolean): Flow<PagingData<BookmarkSearchResult>>
    fun observeCount(
        query: String,
        domain: String?,
        showPrivate: Boolean,
        source: BookmarkSource = BookmarkSource.ALL,
        rssFeedId: String? = null
    ): Flow<Int>
    fun observeCollectionCount(collectionId: String, showPrivate: Boolean): Flow<Int>

    /** Manual-source domains with display names; unsorted; re-emits when domain policies change. */
    fun observeDomainGroups(showPrivate: Boolean): Flow<List<DomainGroupItem>>
    fun observeRssFeedGroups(showPrivate: Boolean): Flow<List<RssFeedGroup>>
    fun observeCollections(showPrivate: Boolean): Flow<List<CollectionWithCount>>
    fun observeMembershipIds(bookmarkId: String): Flow<List<String>>
    fun observeBookmark(id: String): Flow<BookmarkItem?>
    fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind): Flow<List<TagCount>>
    fun observeEntityLibrary(): Flow<Map<String, String>>

    suspend fun toggleLike(id: String, surface: EngagementSurface)
    suspend fun recordBrowserOpen(id: String, surface: EngagementSurface)
    suspend fun recordDetailView(id: String, surface: EngagementSurface)
    suspend fun recordSummaryPeek(id: String, surface: EngagementSurface)
    suspend fun saveNote(id: String, note: String)
    suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>)
    /** Returns `false` only when no bookmark has [id]; `true` otherwise, including the no-op when both values are null. */
    suspend fun applyAiProposal(id: String, description: String?, tag: Tag?): Boolean
    suspend fun moveToTrash(id: String): Boolean

    fun loadFilterMode(): LibraryFilterMode
    suspend fun saveFilterMode(mode: LibraryFilterMode)
    fun loadViewMode(): LibraryViewMode
    fun saveViewMode(mode: LibraryViewMode)
    fun loadIncludeRssInNewestFirst(): Boolean
    fun saveIncludeRssInNewestFirst(include: Boolean)
    suspend fun loadSearchHistory(): List<String>
    suspend fun saveSearchHistory(history: List<String>)
    suspend fun clearSearchHistory()
}

/** Production [LibraryStore] over the current repositories and `tidilo_library_prefs`. */
internal class RepositoryLibraryStore(
    private val bookmarks: BookmarkRepository,
    private val collections: CollectionRepository,
    private val domainPolicies: DomainPolicyStore,
    private val prefs: SharedPreferences
) : LibraryStore {
    override val isInitialized: StateFlow<Boolean> = bookmarks.isInitialized
    override val initializationFailure: StateFlow<Throwable?> = bookmarks.initializationFailure
    override fun retryInitialization() = bookmarks.retryInitialization()

    override fun pagedBookmarks(domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) =
        bookmarks.pagedBookmarks("", domain, showPrivate, source, rssFeedId)

    override fun pagedCollectionBookmarks(collectionId: String, showPrivate: Boolean) =
        collections.pagedBookmarks(collectionId, showPrivate)

    override fun pagedSearchResults(query: String, showPrivate: Boolean) = bookmarks.pagedSearchResults(query, showPrivate)

    override fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource, rssFeedId: String?) =
        bookmarks.observeCount(query, domain, showPrivate, source, rssFeedId)

    override fun observeCollectionCount(collectionId: String, showPrivate: Boolean) =
        collections.observeCount(collectionId, showPrivate)

    override fun observeDomainGroups(showPrivate: Boolean): Flow<List<DomainGroupItem>> =
        combine(bookmarks.observeDomainCounts(showPrivate, BookmarkSource.MANUAL), domainPolicies.policies) { counts, _ ->
            counts.associate { it.domain to it.count }.map { (domain, count) ->
                DomainGroupItem(domain = domain, displayName = domainPolicies.getDisplayName(domain), count = count)
            }
        }

    override fun observeRssFeedGroups(showPrivate: Boolean) = bookmarks.observeRssFeedGroups(showPrivate)
    override fun observeCollections(showPrivate: Boolean) = collections.observeCollections(showPrivate)
    override fun observeMembershipIds(bookmarkId: String) = collections.observeMembershipIds(bookmarkId)
    override fun observeBookmark(id: String) = bookmarks.observeBookmark(id)
    override fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind) =
        bookmarks.observeTagCounts(selected, showPrivate, kind)
    override fun observeEntityLibrary() = bookmarks.observeEntityLibrary()

    override suspend fun toggleLike(id: String, surface: EngagementSurface) = logged { bookmarks.toggleLike(id, System.currentTimeMillis(), surface) }
    override suspend fun recordBrowserOpen(id: String, surface: EngagementSurface) = logged { bookmarks.recordBrowserOpen(id, System.currentTimeMillis(), surface) }
    override suspend fun recordDetailView(id: String, surface: EngagementSurface) = logged { bookmarks.recordDetailView(id, System.currentTimeMillis(), surface) }
    override suspend fun recordSummaryPeek(id: String, surface: EngagementSurface) = logged { bookmarks.recordSummaryPeek(id, System.currentTimeMillis(), surface) }

    override suspend fun saveNote(id: String, note: String) {
        bookmarks.saveNote(id, note)
    }

    override suspend fun updateBookmarkTags(id: String, tag: Tag, isPrivate: Boolean, replacements: Map<String, String>) {
        bookmarks.updateBookmarkTags(id, tag, isPrivate, replacements)
    }

    override suspend fun applyAiProposal(id: String, description: String?, tag: Tag?): Boolean =
        bookmarks.applyAiProposal(id, description, tag)

    override suspend fun moveToTrash(id: String): Boolean = bookmarks.moveToTrash(id)

    override fun loadFilterMode(): LibraryFilterMode {
        val saved = prefs.getString(FILTER_MODE_KEY, null)
        return LibraryFilterMode.entries.firstOrNull { it.name == saved } ?: LibraryFilterMode.NEWEST_FIRST
    }

    override suspend fun saveFilterMode(mode: LibraryFilterMode) = withContext(Dispatchers.IO) {
        prefs.edit { putString(FILTER_MODE_KEY, mode.name) }
    }

    override fun loadViewMode(): LibraryViewMode =
        if (prefs.getString(VIEW_MODE_KEY, null) == LibraryViewMode.GRID.name) LibraryViewMode.GRID else LibraryViewMode.LIST

    override fun saveViewMode(mode: LibraryViewMode) = prefs.edit { putString(VIEW_MODE_KEY, mode.name) }

    override fun loadIncludeRssInNewestFirst(): Boolean = prefs.getBoolean(INCLUDE_RSS_KEY, false)

    override fun saveIncludeRssInNewestFirst(include: Boolean) = prefs.edit { putBoolean(INCLUDE_RSS_KEY, include) }

    override suspend fun loadSearchHistory(): List<String> = withContext(Dispatchers.IO) {
        val json = prefs.getString(SEARCH_HISTORY_KEY, null) ?: return@withContext emptyList()
        runCatching {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }.take(HISTORY_LIMIT)
        }.getOrDefault(emptyList())
    }

    override suspend fun saveSearchHistory(history: List<String>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        history.take(HISTORY_LIMIT).forEach { array.put(it) }
        prefs.edit { putString(SEARCH_HISTORY_KEY, array.toString()) }
    }

    override suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        prefs.edit { remove(SEARCH_HISTORY_KEY) }
    }

    /** Engagement failures are logged here so [LibraryViewModel] stays free of Android logging. */
    private suspend fun logged(write: suspend () -> Unit) {
        try {
            write()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Failed to record bookmark interaction", error)
            throw error
        }
    }

    companion object {
        private const val TAG = "LibraryStore"
        private const val PREFS_NAME = "tidilo_library_prefs"
        private const val FILTER_MODE_KEY = "library_filter_mode"
        private const val VIEW_MODE_KEY = "library_view_mode"
        private const val INCLUDE_RSS_KEY = "library_newest_include_rss"
        private const val SEARCH_HISTORY_KEY = "search_history"
        private const val HISTORY_LIMIT = 10

        fun create(context: Context = TidiloApplication.instance): RepositoryLibraryStore = RepositoryLibraryStore(
            BookmarkRepository.getInstance(context),
            CollectionRepository(AppDatabase.getInstance(context)),
            DomainPolicyStore.getInstance(context),
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
}
