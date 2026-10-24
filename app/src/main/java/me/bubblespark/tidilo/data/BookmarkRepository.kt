package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import me.bubblespark.tidilo.data.db.moveBookmarkToTrash
import me.bubblespark.tidilo.data.db.updateDomainNsfwWithPolicy
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.db.normalizeTagCounts
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    private val _trashItems = MutableStateFlow<List<TrashedBookmarkItem>>(emptyList())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initializationFailure = MutableStateFlow<Throwable?>(null)
    val initializationFailure: StateFlow<Throwable?> = _initializationFailure.asStateFlow()
    private var initializationJob: Job? = null
    private val queryRetry = MutableStateFlow(0)

    /** Retry startup reads without depending on an eager full-library emission. */
    fun retryInitialization() {
        if (initializationJob?.isActive == true) return
        if (_isInitialized.value) {
            _initializationFailure.value = null
            queryRetry.value++
            return
        }
        initializationJob = repositoryScope.launch {
            _initializationFailure.value = null
            try {
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

                trashDao.purgeExpired(System.currentTimeMillis() - TRASH_RETENTION_MS)
                _isInitialized.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _initializationFailure.value = e
            }
        }
    }

    init {
        retryInitialization()
        // 3. Reactively observe trash changes
        repositoryScope.launch {
            observeSafely { trashDao.getAllTrashFlow() }
                .map { entities -> entities.map { it.toTrashedBookmarkItem() } }
                .collect { items ->
                    _trashItems.value = items
                }
        }
    }

    /**
     * Persists a bookmark and lets Room refresh its active queries.
     */
    suspend fun addBookmark(item: BookmarkItem) = addBookmarkAndAwait(item)

    /** Persists a bookmark before returning, retaining any existing record if replacement fails. */
    suspend fun addBookmarkAndAwait(item: BookmarkItem) = withContext(Dispatchers.IO) {
        val domain = DomainIconManager.extractDomain(item.url)
        val resolvedItem = if (DomainPolicyManager.isNsfwDomain(domain) && !item.isNsfw) {
            item.copy(isNsfw = true)
        } else item
        val savedItem = database.withTransaction {
            val existing = bookmarkDao.getBookmarkByUrl(resolvedItem.url)
            val finalItem = if (existing != null) {
                resolvedItem.copy(
                    id = existing.id,
                    note = if (resolvedItem.note.isNotBlank()) resolvedItem.note else existing.note,
                    createdAt = existing.createdAt
                )
            } else resolvedItem

            if (finalItem.rssFeedId != null) {
                val feed = database.rssDao().feed(finalItem.rssFeedId)
                val blocked = database.rssDao().rssWriteConflict(finalItem.url, finalItem.id)
                if (feed == null || feed.deleted || blocked ||
                    (finalItem.isNsfw && !me.bubblespark.tidilo.config.AppConfig.isNsfwContentBookmarkable.value)) {
                    throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
                }
            }
            bookmarkDao.deleteByIdOrUrl(finalItem.id, finalItem.url)
            bookmarkDao.insertOrUpdate(BookmarkEntity.fromBookmarkItem(finalItem))
            finalItem
        }
        // Room publishes the committed bookmark; auxiliary cache work cannot fail the save.
        repositoryScope.launch {
            DomainPolicyManager.registerDomain(domain)
            warmUpDomainIconsAsync(listOf(savedItem))
            if (savedItem.rssFeedId != null) me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).cleanup()
        }
    }

    /**
     * Updates the isNsfw flag for all bookmarks belonging to the specified domain.
     */
    suspend fun updateBookmarksNsfwForDomain(
        domain: String, isNsfw: Boolean, persistPolicy: suspend () -> Unit
    ) = withContext(Dispatchers.IO) {
        database.updateDomainNsfwWithPolicy(domain.trim().lowercase(), isNsfw, persistPolicy)
    }

    /** Updates the durable row by ID; no loaded page is required. */
    suspend fun updateBookmarkNote(id: String, note: String) = withContext(Dispatchers.IO) {
        bookmarkDao.updateNote(id, note, System.currentTimeMillis())
    }

    /** Applies optional fields atomically against the current durable row. */
    suspend fun updateBookmarkFields(id: String, description: String? = null, tag: Tag? = null) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val target = bookmarkDao.getBookmarkById(id) ?: return@withTransaction
            bookmarkDao.updateFields(id, description ?: target.description,
                tag?.category ?: if (tag == null) target.category else null,
                tag?.subcategory ?: if (tag == null) target.subcategory else null,
                tag?.entities ?: target.entities, System.currentTimeMillis())
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
    }

    /**
     * Moves a bookmark to the trash.
     * - Excludes the item from the active bookmark list.
     * - Retains in trash up to MAX_TRASH_CAPACITY (evicting oldest items beyond limit).
     */
    suspend fun moveToTrash(bookmarkId: String): Boolean = withContext(Dispatchers.IO) {
        database.moveBookmarkToTrash(bookmarkId, System.currentTimeMillis(), MAX_TRASH_CAPACITY)
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
     * Synchronizes domain policies and preloads cached icons without network access.
     */
    private fun warmUpDomainIconsAsync(items: List<BookmarkItem>? = null) {
        repositoryScope.launch {
            val domains = items?.mapNotNull { DomainIconManager.extractDomain(it.url) }?.distinct()
                ?: bookmarkDao.distinctDomains()
            DomainPolicyManager.syncDomains(domains)
            DomainIconManager.preloadIcons(appContext, domains)
        }
    }

    /** Each input generation owns a Pager; callers switch generations before caching in the ViewModel. */
    fun pagedBookmarks(query: String, domain: String?, showNsfw: Boolean, source: BookmarkSource = BookmarkSource.ALL): Flow<PagingData<BookmarkItem>> =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 60,
            maxSize = 150, enablePlaceholders = false)) {
            when {
                query.isNotBlank() || source != BookmarkSource.ALL -> bookmarkDao.search(BookmarkQueries.results(query, showNsfw, domain, source))
                domain != null -> bookmarkDao.byDomain(domain, showNsfw)
                else -> bookmarkDao.timeline(showNsfw)
            }
        }.flow.map { page -> page.map { it.toBookmarkItem() } }

    fun observeBookmark(id: String): Flow<BookmarkItem?> = bookmarkDao.observeById(id).map { it?.toBookmarkItem() }

    fun observeDomainCounts(showNsfw: Boolean, source: BookmarkSource = BookmarkSource.ALL): Flow<List<DomainCount>> = observeSafely { bookmarkDao.domainCounts(showNsfw, source.sqlValue) }

    fun observeCollectionCounts(): Flow<CollectionCounts> = observeSafely { bookmarkDao.observeCollectionCounts() }

    fun observeCount(query: String, domain: String?, showNsfw: Boolean, source: BookmarkSource = BookmarkSource.ALL): Flow<Int> =
        observeSafely { bookmarkDao.observeCount(BookmarkQueries.count(query, showNsfw, domain, source)) }

    suspend fun distinctDomains(): List<String> = withContext(Dispatchers.IO) { bookmarkDao.distinctDomains() }

    fun observeDomains(): Flow<List<String>> = observeSafely { bookmarkDao.observeDistinctDomains() }

    internal fun observeTagCounts(selected: Set<String>, showNsfw: Boolean, kind: TagKind): Flow<List<TagCount>> =
        observeSafely { bookmarkDao.tagCounts(BookmarkQueries.tags(selected, showNsfw, kind)) }
            .map { normalizeTagCounts(it, kind) }

    private fun <T> observeSafely(query: () -> Flow<T>): Flow<T> =
        recoverableBookmarkQuery(isInitialized, queryRetry, { _initializationFailure.value = it }, query)

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
