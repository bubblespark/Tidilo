package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BookmarkRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("tidilo_bookmark_prefs", Context.MODE_PRIVATE)

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val database: AppDatabase = AppDatabase.getInstance(appContext)
    private val bookmarkDao = database.bookmarkDao()
    private val trashDao = database.trashDao()

    private val _bookmarks = MutableStateFlow<List<BookmarkItem>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkItem>> = _bookmarks.asStateFlow()

    private val _trashItems = MutableStateFlow<List<TrashedBookmarkItem>>(emptyList())

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        // 1. Initial migration and data initialization
        repositoryScope.launch {
            val count = bookmarkDao.getCount()
            if (count == 0) {
                // Check legacy SharedPreferences JSON or populate initial samples
                val legacyJson = prefs.getString("bookmarks_list", null)
                val initialList = if (!legacyJson.isNullOrBlank()) {
                    val parsed = parseBookmarks(legacyJson)
                    if (parsed.isNotEmpty()) parsed else getInitialSampleBookmarks()
                } else {
                    getInitialSampleBookmarks()
                }
                bookmarkDao.insertAll(initialList.map { BookmarkEntity.fromBookmarkItem(it) })
                warmUpDomainIconsAsync(initialList)
            } else {
                warmUpDomainIconsAsync()
            }

            // Check legacy trash in SharedPreferences
            if (trashDao.getCount() == 0) {
                val legacyTrashJson = prefs.getString("trashed_bookmarks_list", null)
                if (!legacyTrashJson.isNullOrBlank()) {
                    val parsedTrash = parseTrash(legacyTrashJson)
                    if (parsedTrash.isNotEmpty()) {
                        trashDao.insertAll(parsedTrash.map { TrashedBookmarkEntity.fromTrashedBookmarkItem(it) })
                    }
                }
            }

            purgeExpiredTrash()
        }

        // 2. Reactively observe Room database changes and publish into StateFlow
        repositoryScope.launch {
            bookmarkDao.getAllBookmarksFlow()
                .map { entities -> entities.map { it.toBookmarkItem() } }
                .collect { items ->
                    _bookmarks.value = items
                    _isLoaded.value = true
                }
        }

        // 3. Reactively observe trash changes
        repositoryScope.launch {
            trashDao.getAllTrashFlow()
                .map { entities -> entities.map { it.toTrashedBookmarkItem() } }
                .collect { items ->
                    _trashItems.value = items
                }
        }
    }

    /**
     * Prepends a bookmark item to the database and updates in-memory collection.
     */
    fun addBookmark(item: BookmarkItem) {
        val domain = DomainIconManager.extractDomain(item.url)
        val isDomainNsfw = DomainPolicyManager.isNsfwDomain(domain)
        val finalItem = if (isDomainNsfw && !item.isNsfw) item.copy(isNsfw = true) else item

        // Immediate optimistic in-memory update for 0ms perceived UI responsiveness
        val current = _bookmarks.value
        val updated = listOf(finalItem) + current.filter { it.url != finalItem.url && it.id != finalItem.id }
        _bookmarks.value = updated

        repositoryScope.launch {
            bookmarkDao.deleteByIdOrUrl(finalItem.id, finalItem.url)
            bookmarkDao.insertOrUpdate(BookmarkEntity.fromBookmarkItem(finalItem))
            DomainPolicyManager.registerDomain(domain)
            warmUpDomainIconsAsync(listOf(finalItem))
        }
    }

    /** Persists a bookmark before returning, retaining any existing record if replacement fails. */
    suspend fun addBookmarkAndAwait(item: BookmarkItem) = withContext(Dispatchers.IO) {
        val domain = DomainIconManager.extractDomain(item.url)
        val finalItem = if (DomainPolicyManager.isNsfwDomain(domain) && !item.isNsfw) {
            item.copy(isNsfw = true)
        } else item
        database.withTransaction {
            bookmarkDao.deleteByIdOrUrl(finalItem.id, finalItem.url)
            bookmarkDao.insertOrUpdate(BookmarkEntity.fromBookmarkItem(finalItem))
        }
        // Room publishes the committed bookmark; auxiliary cache work cannot fail the save.
        repositoryScope.launch {
            DomainPolicyManager.registerDomain(domain)
            warmUpDomainIconsAsync(listOf(finalItem))
        }
    }

    /**
     * Updates the isNsfw flag for all bookmarks belonging to the specified domain.
     */
    fun updateBookmarksNsfwForDomain(domain: String, isNsfw: Boolean) {
        val normalizedDomain = domain.trim().lowercase()
        val current = _bookmarks.value
        val matchingIds = current.filter { item ->
            DomainIconManager.extractDomain(item.url)?.lowercase() == normalizedDomain && item.isNsfw != isNsfw
        }.map { it.id }

        if (matchingIds.isNotEmpty()) {
            val updated = current.map { item ->
                if (matchingIds.contains(item.id)) item.copy(isNsfw = isNsfw) else item
            }
            _bookmarks.value = updated

            repositoryScope.launch {
                bookmarkDao.updateNsfwByIds(matchingIds, isNsfw)
            }
        }
    }

    /**
     * Updates and persists the user note for a specific bookmark.
     */
    fun updateBookmarkNote(id: String, note: String) {
        val now = System.currentTimeMillis()
        val current = _bookmarks.value
        val updated = current.map { item ->
            if (item.id == id) {
                item.copy(note = note, updatedAt = now)
            } else {
                item
            }
        }
        _bookmarks.value = updated

        repositoryScope.launch {
            bookmarkDao.updateNote(id, note, now)
        }
    }

    /**
     * Updates description and/or tag of a bookmark and persists the change.
     */
    fun updateBookmarkFields(
        id: String,
        description: String? = null,
        tag: Tag? = null
    ) {
        val now = System.currentTimeMillis()
        val current = _bookmarks.value
        val target = current.find { it.id == id } ?: return
        val newDesc = description ?: target.description
        val newTag = tag ?: target.tag

        val updated = current.map { item ->
            if (item.id == id) {
                item.copy(
                    description = newDesc,
                    tag = newTag,
                    updatedAt = now
                )
            } else {
                item
            }
        }
        _bookmarks.value = updated

        repositoryScope.launch {
            bookmarkDao.updateFields(
                id = id,
                description = newDesc,
                category = newTag.category,
                subcategory = newTag.subcategory,
                entities = newTag.entities,
                updatedAt = now
            )
        }
    }

    /**
     * Updates note, tag, and NSFW status of a bookmark and persists the change atomically.
     * Preserves existing isAiSkipped flag.
     */
    suspend fun updateBookmarkDetails(
        id: String,
        note: String,
        tag: Tag,
        isNsfw: Boolean
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        bookmarkDao.updateNoteAndTag(
            id = id,
            note = note,
            category = tag.category,
            subcategory = tag.subcategory,
            entities = tag.entities,
            isNsfw = isNsfw,
            updatedAt = now
        )

        val current = _bookmarks.value
        val updated = current.map { item ->
            if (item.id == id) {
                item.copy(
                    note = note,
                    tag = tag,
                    isNsfw = isNsfw,
                    updatedAt = now
                )
            } else {
                item
            }
        }
        _bookmarks.value = updated
    }

    /**
     * Moves a bookmark to the trash.
     * - Excludes the item from the active bookmark list.
     * - Retains in trash up to MAX_TRASH_CAPACITY (evicting oldest items beyond limit).
     */
    fun moveToTrash(bookmarkId: String): Boolean {
        val current = _bookmarks.value
        val target = current.find { it.id == bookmarkId } ?: return false

        // 1. Optimistic UI update
        val updated = current.filter { it.id != bookmarkId }
        _bookmarks.value = updated

        val trashedItem = TrashedBookmarkItem(target, System.currentTimeMillis())
        _trashItems.value = (listOf(trashedItem) + _trashItems.value.filter { it.bookmark.id != bookmarkId })
            .sortedByDescending { it.trashedAt }
            .take(MAX_TRASH_CAPACITY)

        repositoryScope.launch {
            bookmarkDao.deleteById(bookmarkId)
            trashDao.insert(TrashedBookmarkEntity.fromTrashedBookmarkItem(trashedItem))
            trashDao.enforceCapacity(MAX_TRASH_CAPACITY)
        }
        return true
    }

    /**
     * Purges trashed bookmarks older than TRASH_RETENTION_MS (30 days).
     */
    fun purgeExpiredTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
        repositoryScope.launch {
            trashDao.purgeExpired(cutoff)
        }
    }

    /**
     * Returns the list of currently trashed bookmarks.
     */
    fun getTrashItems(): List<TrashedBookmarkItem> {
        return _trashItems.value
    }

    /**
     * Synchronizes domain policies and fetches missing domain icons in the background.
     */
    private fun warmUpDomainIconsAsync(items: List<BookmarkItem>? = null) {
        repositoryScope.launch {
            val domains = items?.mapNotNull { DomainIconManager.extractDomain(it.url) }?.distinct()
                ?: bookmarkDao.distinctDomains()
            DomainPolicyManager.syncDomains(domains)
            DomainIconManager.preloadIcons(appContext, domains)
            for (domain in domains) {
                if (DomainIconManager.hasIcon(appContext, domain) || DomainIconManager.shouldSkipFetch(appContext, domain)) continue
                var groupHadTransientFailure = false
                var found = false
                var urls = bookmarkDao.domainUrls(domain, null, 64)
                while (urls.isNotEmpty() && !found) {
                    val next = bookmarkDao.domainUrls(domain, urls.last(), 64)
                    for ((index, url) in urls.withIndex()) {
                        val isLast = next.isEmpty() && index == urls.lastIndex
                        val result = DomainIconManager.fetchAndSaveDomainIcon(
                            context = appContext, url = url,
                            recordFailureMarker = isLast && !groupHadTransientFailure
                        )
                        if (result.hadTransientFailure) groupHadTransientFailure = true
                        if (result.success || DomainIconManager.hasIcon(appContext, domain)) {
                            found = true
                            break
                        }
                    }
                    urls = next
                }
            }
        }
    }

    /** Each input generation owns a Pager; callers switch generations before caching in the ViewModel. */
    fun pagedBookmarks(query: String, domain: String?, showNsfw: Boolean): Flow<PagingData<BookmarkItem>> =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 60,
            maxSize = 150, enablePlaceholders = false)) {
            when {
                query.isNotBlank() -> bookmarkDao.search(BookmarkQueries.results(query, showNsfw, domain))
                domain != null -> bookmarkDao.byDomain(domain, showNsfw)
                else -> bookmarkDao.timeline(showNsfw)
            }
        }.flow.map { page -> page.map { it.toBookmarkItem() } }

    fun observeBookmark(id: String): Flow<BookmarkItem?> = bookmarkDao.observeById(id).map { it?.toBookmarkItem() }

    fun observeDomainCounts(showNsfw: Boolean): Flow<List<DomainCount>> = bookmarkDao.domainCounts(showNsfw)

    suspend fun distinctDomains(): List<String> = withContext(Dispatchers.IO) { bookmarkDao.distinctDomains() }

    internal fun observeTagCounts(selected: Set<String>, showNsfw: Boolean, kind: TagKind): Flow<List<TagCount>> =
        bookmarkDao.tagCounts(BookmarkQueries.tags(selected, showNsfw, kind))

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L
        const val MAX_TRASH_CAPACITY = 100

        @Volatile
        private var INSTANCE: BookmarkRepository? = null

        fun getInstance(context: Context = TidiloApplication.instance): BookmarkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BookmarkRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun serializeBookmarkToObj(item: BookmarkItem): JSONObject {
            return JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("url", item.url)
                put("description", item.description)
                val tagObj = JSONObject().apply {
                    put("category", item.tag.category)
                    put("subcategory", item.tag.subcategory)
                    val entitiesArray = JSONArray()
                    item.tag.entities.forEach { entitiesArray.put(it) }
                    put("entities", entitiesArray)
                }
                put("tag", tagObj)
                put("createdAt", item.createdAt)
                put("updatedAt", item.updatedAt)
                put("note", item.note)
                put("isNsfw", item.isNsfw)
                put("isAiSkipped", item.isAiSkipped)
            }
        }

        fun parseBookmarkFromObj(obj: JSONObject): BookmarkItem {
            val id = obj.getString("id")
            val url = obj.getString("url")
            val isAiSkipped = obj.optBoolean("isAiSkipped", false)
            val tagObj = obj.optJSONObject("tag")
            val entitiesList = mutableListOf<String>()
            tagObj?.optJSONArray("entities")?.let { ea ->
                for (j in 0 until ea.length()) {
                    entitiesList.add(ea.getString(j))
                }
            }
            val tag = if (isAiSkipped && (tagObj == null || (tagObj.optString("category").isBlank() && tagObj.optString("subcategory").isBlank() && entitiesList.isEmpty()))) {
                Tag.createEmpty()
            } else {
                Tag.create(
                    category = tagObj?.optString("category", Tag.DEFAULT_TAG),
                    subcategory = tagObj?.optString("subcategory", Tag.DEFAULT_TAG),
                    entities = entitiesList
                )
            }
            val now = System.currentTimeMillis()
            val createdAt = obj.optLong("createdAt", now)
            val updatedAt = obj.optLong("updatedAt", createdAt)
            val note = obj.optString("note", "")
            val isNsfw = obj.optBoolean("isNsfw", false) ||
                DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(url))
            return BookmarkItem(
                id = id,
                title = obj.getString("title"),
                url = url,
                description = obj.getString("description"),
                tag = tag,
                createdAt = createdAt,
                updatedAt = updatedAt,
                note = note,
                isNsfw = isNsfw,
                isAiSkipped = isAiSkipped
            )
        }

        fun serializeBookmarks(items: List<BookmarkItem>): String {
            val array = JSONArray()
            items.forEach { item ->
                array.put(serializeBookmarkToObj(item))
            }
            return array.toString()
        }

        fun serializeTrash(items: List<TrashedBookmarkItem>): String {
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("trashedAt", item.trashedAt)
                    put("bookmark", serializeBookmarkToObj(item.bookmark))
                }
                array.put(obj)
            }
            return array.toString()
        }

        fun parseTrash(json: String): List<TrashedBookmarkItem> {
            return runCatching {
                val array = JSONArray(json)
                val list = mutableListOf<TrashedBookmarkItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val trashedAt = obj.getLong("trashedAt")
                    val bookmarkObj = obj.getJSONObject("bookmark")
                    val bookmark = parseBookmarkFromObj(bookmarkObj)
                    list.add(TrashedBookmarkItem(bookmark, trashedAt))
                }
                list
            }.getOrDefault(emptyList())
        }

        fun parseBookmarks(
            json: String,
            sampleBookmarks: List<BookmarkItem> = emptyList(),
            queueTagsMap: Map<String, Tag> = emptyMap()
        ): List<BookmarkItem> {
            return runCatching {
                val array = JSONArray(json)
                val list = mutableListOf<BookmarkItem>()
                val sampleMap = sampleBookmarks.associateBy { it.id }

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val url = obj.getString("url")
                    val tagObj = obj.optJSONObject("tag")
                    val entitiesList = mutableListOf<String>()
                    tagObj?.optJSONArray("entities")?.let { ea ->
                        for (j in 0 until ea.length()) {
                            entitiesList.add(ea.getString(j))
                        }
                    }
                    var tag = Tag.create(
                        category = tagObj?.optString("category", Tag.DEFAULT_TAG),
                        subcategory = tagObj?.optString("subcategory", Tag.DEFAULT_TAG),
                        entities = entitiesList
                    )

                    // Self-healing: if tag is DEFAULT_TAG or empty, recover from sample bookmarks or queue history
                    if (tag.category == Tag.DEFAULT_TAG || tag.subcategory == Tag.DEFAULT_TAG || tag.entities.isEmpty()) {
                        val repairedTag = sampleMap[id]?.tag
                            ?: sampleMap.values.find { it.url == url }?.tag
                            ?: queueTagsMap[url]
                            ?: when {
                                url.contains("share.google/PMNsmZyE1xb29GBQw") ->
                                    Tag.create("Tool", "Tech", listOf("Antigravity", "Android", "Codelab"))
                                url.contains("developer.android.com/design/ui/mobile/guides/foundations/motion") ->
                                    Tag.create("Reference", "Tech", listOf("Android", "Motion", "Gestures"))
                                url.contains("developer.android.com/compose") ->
                                    Tag.create("Reference", "Tech", listOf("Jetpack Compose", "Android Studio"))
                                else ->
                                    Tag.create("Article", "Tech", listOf("Web"))
                            }
                        tag = repairedTag
                    }

                    val now = System.currentTimeMillis()
                    val createdAt = obj.optLong("createdAt", now)
                    val updatedAt = obj.optLong("updatedAt", createdAt)
                    val note = obj.optString("note", "")
                    val isNsfw = obj.optBoolean("isNsfw", false) ||
                        DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(url))
                    val isAiSkipped = obj.optBoolean("isAiSkipped", false)
                    list.add(
                        BookmarkItem(
                            id = id,
                            title = obj.getString("title"),
                            url = url,
                            description = obj.getString("description"),
                            tag = tag,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                            note = note,
                            isNsfw = isNsfw,
                            isAiSkipped = isAiSkipped
                        )
                    )
                }
                list
            }.getOrDefault(emptyList())
        }

        fun getInitialSampleBookmarks(): List<BookmarkItem> {
            return listOf(
                BookmarkItem(
                    id = "b1",
                    title = "bubblespark/Tidilo: On-Device AI Bookmark & Knowledge Manager for Android",
                    url = "https://github.com/bubblespark/Tidilo",
                    description = "An intelligent, privacy-first personal bookmark and knowledge manager for Android that uses local Gemini Nano via AICore to automatically summarize, categorize, and extract entity keywords without cloud APIs.",
                    tag = Tag.create(
                        category = "Tool",
                        subcategory = "Tech",
                        entities = listOf("Android", "Gemini Nano", "AICore", "Jetpack Compose", "GitHub")
                    )
                )
            )
        }
    }
}
