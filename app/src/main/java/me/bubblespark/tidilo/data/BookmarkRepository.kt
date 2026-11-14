package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import me.bubblespark.tidilo.data.db.logicalBookmarkTag
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import me.bubblespark.tidilo.data.db.BookmarkQueries
import me.bubblespark.tidilo.data.db.TagKind
import me.bubblespark.tidilo.data.db.normalizeTagCounts
import me.bubblespark.tidilo.data.db.TagCount
import me.bubblespark.tidilo.data.db.DomainCount
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkDao
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class TrashMutationResult { Restored, Deleted, DuplicateInLibrary, ImportPending, Missing, IdentityConflict }

internal interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

internal interface LegacyBookmarkAdapter {
    fun readLegacyBookmarksJson(): String?
    fun readLegacyTrashJson(): String?
    fun preloadDomainIcons(domains: List<String>)
}

class BookmarkRepository internal constructor(
    private val database: AppDatabase,
    private val legacyAdapter: LegacyBookmarkAdapter? = null,
    private val transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
    },
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    autoInitialize: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val domainPolicyStore: DomainPolicyStore? = null
) {

    private val bookmarkDao = database.bookmarkDao()
    private val trashDao = database.trashDao()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initializationFailure = MutableStateFlow<Throwable?>(null)
    val initializationFailure: StateFlow<Throwable?> = _initializationFailure.asStateFlow()
    @Volatile
    private var initializationJob: Job? = null
    private val queryRetry = MutableStateFlow(0)

    /** Retry startup reads without depending on an eager full-library emission. */
    @Synchronized
    fun retryInitialization() {
        if (initializationJob?.isActive == true) return
        if (_isInitialized.value) {
            _initializationFailure.value = null
            queryRetry.update { it + 1 }
            return
        }
        initializationJob = repositoryScope.launch {
            _initializationFailure.value = null
            try {
                val adapter = legacyAdapter
                val privateLookup: (String?) -> Boolean = { domainPolicyStore?.isPrivateDomain(it) == true }
                val count = bookmarkDao.getCount()
                if (count == 0) {
                    if (adapter != null) {
                        domainPolicyStore?.awaitReady()
                        val legacyJson = adapter.readLegacyBookmarksJson()
                        val initialList = if (!legacyJson.isNullOrBlank()) {
                            val parsed = parseBookmarks(legacyJson, isPrivateDomain = privateLookup)
                            if (parsed.isNotEmpty()) parsed else getInitialSampleBookmarks()
                        } else {
                            getInitialSampleBookmarks()
                        }
                        bookmarkDao.insertAll(initialList.map { BookmarkEntity.fromBookmarkItem(it) })
                        warmUpDomainIconsAsync(initialList)
                    }
                } else {
                    warmUpDomainIconsAsync()
                }

                // Check legacy trash in SharedPreferences
                if (adapter != null && trashDao.getCount() == 0) {
                    domainPolicyStore?.awaitReady()
                    val legacyTrashJson = adapter.readLegacyTrashJson()
                    if (!legacyTrashJson.isNullOrBlank()) {
                        val parsedTrash = parseTrash(legacyTrashJson, isPrivateDomain = privateLookup)
                        if (parsedTrash.isNotEmpty()) {
                            trashDao.insertAll(parsedTrash.map { TrashedBookmarkEntity.fromTrashedBookmarkItem(it) })
                        }
                    }
                }

                trashDao.purgeExpired(clock() - TRASH_RETENTION_MS)
                _isInitialized.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _initializationFailure.value = e
            }
        }
    }

    /** Waits for startup attempts to settle before readers subscribe again. */
    suspend fun retryInitializationAndAwait() {
        initializationJob?.join()
        retryInitialization()
        initializationJob?.join()
    }

    init {
        if (autoInitialize) {
            retryInitialization()
        }
    }

    /** Persists a bookmark before returning, retaining any existing record if replacement fails. */
    suspend fun addBookmarkAndAwait(item: BookmarkItem) = withContext(Dispatchers.IO) {
        domainPolicyStore?.awaitReady()
        val domain = DomainIconManager.extractDomain(item.url)
        val savedItem = transactionRunner.run {
            persistMergedBookmarkInTransaction(
                item = item,
                bookmarkDao = bookmarkDao,
                isPrivateDomain = { domainPolicyStore?.isPrivateDomainCommitted(it) == true },
                validateRssWrite = { finalItem, feedId ->
                    val feed = database.rssDao().feed(feedId)
                    val blocked = database.rssDao().rssWriteConflict(finalItem.url, finalItem.id)
                    if (feed == null || feed.deleted || blocked ||
                        (finalItem.isPrivate && !me.bubblespark.tidilo.config.AppConfig.isPrivateContentBookmarkable.value)) {
                        throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
                    }
                }
            )
        }
        // Room publishes the committed bookmark; auxiliary cache work cannot fail the save.
        repositoryScope.launch {
            domainPolicyStore?.registerDomain(domain)
            warmUpDomainIconsAsync(listOf(savedItem))
        }
    }

    /** Updates the durable row by ID; no loaded page is required. */
    suspend fun updateBookmarkNote(id: String, note: String) = withContext(Dispatchers.IO) {
        bookmarkDao.updateNote(id, note, clock())
    }

    suspend fun toggleLike(id: String, now: Long) = withContext(Dispatchers.IO) {
        bookmarkDao.toggleLike(id, now)
    }

    suspend fun recordBrowserOpen(id: String, now: Long) = withContext(Dispatchers.IO) {
        bookmarkDao.recordBrowserOpen(id, now)
    }

    suspend fun recordDetailView(id: String, now: Long) = withContext(Dispatchers.IO) {
        bookmarkDao.recordDetailView(id, now)
    }

    suspend fun recordSummaryPeek(id: String, now: Long) = withContext(Dispatchers.IO) {
        bookmarkDao.recordSummaryPeek(id, now)
    }

    /** Applies optional fields atomically against the current durable row. */
    suspend fun updateBookmarkFields(id: String, description: String? = null, tag: Tag? = null) = withContext(Dispatchers.IO) {
        transactionRunner.run {
            val target = bookmarkDao.getBookmarkById(id) ?: return@run
            bookmarkDao.updateFields(id, description ?: target.description,
                tag?.category ?: if (tag == null) target.category else null,
                tag?.subcategory ?: if (tag == null) target.subcategory else null,
                tag?.entities ?: target.entities, clock())
        }
    }

    /**
     * Updates note, tag, and Private status of a bookmark and persists the change atomically.
     * Applies explicit library spellings before re-resolving a possibly stale display-value draft.
     */
    suspend fun updateBookmarkDetails(
        id: String,
        note: String,
        tag: Tag,
        isPrivate: Boolean,
        replacements: Map<String, String> = emptyMap(),
        updatedAt: Long = clock()
    ) = withContext(Dispatchers.IO) {
        transactionRunner.run {
            requireNotNull(bookmarkDao.getBookmarkById(id)) { "Bookmark no longer exists" }
            bookmarkDao.replaceEntitySpellings(replacements, updatedAt)
            val entities = tag.entities.map { replacements[entitySkeletonKey(it)] ?: it }
            bookmarkDao.updateNoteAndTag(id, note, tag.category, tag.subcategory, entities, isPrivate, updatedAt)
        }
    }

    /**
     * Moves a bookmark to the trash.
     * - Excludes the item from the active bookmark list.
     * - Retains in trash up to [capacity] (evicting oldest items beyond limit).
     */
    suspend fun moveToTrash(
        bookmarkId: String,
        now: Long = clock(),
        capacity: Int = MAX_TRASH_CAPACITY
    ): Boolean = withContext(Dispatchers.IO) {
        transactionRunner.run {
            val bookmark = bookmarkDao.getBookmarkById(bookmarkId) ?: return@run false
            trashDao.insert(
                TrashedBookmarkEntity.fromTrashedBookmarkItem(
                    TrashedBookmarkItem(bookmark.copy(isLiked = false).toBookmarkItem(), now)
                )
            )
            bookmarkDao.deleteById(bookmarkId)
            trashDao.enforceCapacity(capacity)
            true
        }
    }

    /** Trash consumers own their error UI and must not wait forever on a failed startup/read. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeTrash(showPrivate: Boolean): Flow<List<TrashBookmarkRow>> =
        combine(isInitialized, initializationFailure) { initialized, error -> initialized to error }
            .flatMapLatest { (initialized, error) ->
                when {
                    error != null -> flow { throw error }
                    initialized -> trashDao.observeTrash(showPrivate)
                    else -> emptyFlow()
                }
            }

    /** Never replace an active URL owner; the snapshot and its removal commit together. */
    suspend fun restoreFromTrash(id: String): TrashMutationResult = withContext(Dispatchers.IO) {
        transactionRunner.run {
            val snapshot = trashDao.getById(id) ?: return@run TrashMutationResult.Missing
            if (bookmarkDao.getBookmarkByUrl(snapshot.url) != null) return@run TrashMutationResult.DuplicateInLibrary
            if (bookmarkDao.getBookmarkById(id) != null) return@run TrashMutationResult.IdentityConflict
            if (trashDao.hasConflictingImport(snapshot.url)) return@run TrashMutationResult.ImportPending
            val bookmark = snapshot.toTrashedBookmarkItem().bookmark.copy(isLiked = false)
            bookmarkDao.insertOrUpdate(BookmarkEntity.fromBookmarkItem(bookmark))
            trashDao.deleteById(id)
            TrashMutationResult.Restored
        }
    }

    /** A stale Trash action cannot remove the restored bookmark or shared image files. */
    suspend fun deleteFromTrash(id: String): TrashMutationResult = withContext(Dispatchers.IO) {
        transactionRunner.run {
            if (trashDao.deleteByIdCounted(id) == 0) TrashMutationResult.Missing else TrashMutationResult.Deleted
        }
    }

    /**
     * Synchronizes domain policies and preloads cached icons without network access.
     */
    private fun warmUpDomainIconsAsync(items: List<BookmarkItem>? = null) {
        repositoryScope.launch {
            val domains = items?.mapNotNull { DomainIconManager.extractDomain(it.url) }?.distinct()
                ?: bookmarkDao.distinctDomains()
            domainPolicyStore?.syncDomains(domains)
            legacyAdapter?.preloadDomainIcons(domains)
        }
    }

    /** Each input generation owns a Pager; callers switch generations before caching in the ViewModel. */
    fun pagedBookmarks(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): Flow<PagingData<BookmarkItem>> =
        Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, initialLoadSize = 60,
            maxSize = 150, enablePlaceholders = false)) {
            when {
                query.isNotBlank() || source != BookmarkSource.ALL || rssFeedId != null -> bookmarkDao.search(BookmarkQueries.results(query, showPrivate, domain, source, rssFeedId))
                domain != null -> bookmarkDao.byDomain(domain, showPrivate)
                else -> bookmarkDao.timeline(showPrivate)
            }
        }.flow.map { page -> page.map { it.toBookmarkItem() } }

    fun observeBookmark(id: String): Flow<BookmarkItem?> = bookmarkDao.observeById(id).map { it?.toBookmarkItem() }

    fun observeDomainCounts(showPrivate: Boolean, source: BookmarkSource = BookmarkSource.ALL): Flow<List<DomainCount>> = observeSafely { bookmarkDao.domainCounts(showPrivate, source.sqlValue) }

    fun observeRssFeedGroups(showPrivate: Boolean): Flow<List<me.bubblespark.tidilo.data.db.RssFeedGroup>> =
        observeSafely { database.rssDao().observeLibraryGroups(showPrivate) }

    fun observeCollectionCounts(): Flow<CollectionCounts> = observeSafely { bookmarkDao.observeCollectionCounts() }

    fun observeCount(query: String, domain: String?, showPrivate: Boolean, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): Flow<Int> =
        observeSafely { bookmarkDao.observeCount(BookmarkQueries.count(query, showPrivate, domain, source, rssFeedId)) }

    suspend fun distinctDomains(): List<String> = withContext(Dispatchers.IO) { bookmarkDao.distinctDomains() }

    fun observeDomains(): Flow<List<String>> = observeSafely { bookmarkDao.observeDistinctDomains() }

    internal fun observeTagCounts(selected: Set<String>, showPrivate: Boolean, kind: TagKind): Flow<List<TagCount>> =
        observeSafely { bookmarkDao.tagCounts(BookmarkQueries.tags(selected, showPrivate, kind)) }
            .map { normalizeTagCounts(it, kind) }

    /** Unfiltered active entities; failures propagate so manual entry cannot silently bypass lookup. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeEntityLibrary(): Flow<Map<String, String>> = isInitialized.flatMapLatest { ready ->
        if (!ready) kotlinx.coroutines.flow.emptyFlow() else bookmarkDao.tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.ENTITY))
            .map { rows -> rows.associate { entitySkeletonKey(it.value) to it.value } }
    }

    /** Keeps failed observers retryable and prevents reads racing database initialization. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> observeSafely(query: () -> Flow<T>): Flow<T> =
        combine(isInitialized, queryRetry) { initialized, attempt -> initialized to attempt }
            .flatMapLatest { (initialized, _) ->
                if (!initialized) emptyFlow() else flow { emitAll(query()) }.catch {
                    if (it is CancellationException) throw it
                    _initializationFailure.value = it
                }
            }

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000L
        const val MAX_TRASH_CAPACITY = 100

        @Volatile
        private var INSTANCE: BookmarkRepository? = null

        fun getInstance(context: Context = TidiloApplication.instance): BookmarkRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createProductionInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun createProductionInstance(appContext: Context): BookmarkRepository {
            val prefs: SharedPreferences =
                appContext.getSharedPreferences("tidilo_bookmark_prefs", Context.MODE_PRIVATE)
            val adapter = object : LegacyBookmarkAdapter {
                override fun readLegacyBookmarksJson(): String? = prefs.getString("bookmarks_list", null)
                override fun readLegacyTrashJson(): String? = prefs.getString("trashed_bookmarks_list", null)
                override fun preloadDomainIcons(domains: List<String>) {
                    DomainIconManager.preloadIcons(appContext, domains)
                }
            }
            return BookmarkRepository(
                database = AppDatabase.getInstance(appContext),
                legacyAdapter = adapter,
                domainPolicyStore = DomainPolicyStore.getInstance(appContext)
            )
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
                put("isPrivate", item.isPrivate)
            }
        }

        fun parseBookmarkFromObj(
            obj: JSONObject,
            isPrivateDomain: (String?) -> Boolean = { false }
        ): BookmarkItem {
            val id = obj.getString("id")
            val url = obj.getString("url")
            val tagObj = obj.optJSONObject("tag")
            val entitiesList = mutableListOf<String>()
            tagObj?.optJSONArray("entities")?.let { ea ->
                for (j in 0 until ea.length()) {
                    entitiesList.add(ea.getString(j))
                }
            }
            val tag = logicalBookmarkTag(
                category = tagObj?.opt("category") as? String,
                subcategory = tagObj?.opt("subcategory") as? String,
                entities = entitiesList
            )
            val now = System.currentTimeMillis()
            val createdAt = obj.optLong("createdAt", now)
            val updatedAt = obj.optLong("updatedAt", createdAt)
            val note = obj.optString("note", "")
            val isPrivate = obj.readLegacyCompatiblePrivateFlag() ||
                isPrivateDomain(DomainIconManager.extractDomain(url))
            return BookmarkItem(
                id = id,
                title = obj.getString("title"),
                url = url,
                description = obj.getString("description"),
                tag = tag,
                createdAt = createdAt,
                updatedAt = updatedAt,
                note = note,
                isPrivate = isPrivate
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

        fun parseTrash(
            json: String,
            isPrivateDomain: (String?) -> Boolean = { false }
        ): List<TrashedBookmarkItem> {
            return runCatching {
                val array = JSONArray(json)
                val list = mutableListOf<TrashedBookmarkItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val trashedAt = obj.getLong("trashedAt")
                    val bookmarkObj = obj.getJSONObject("bookmark")
                    val bookmark = parseBookmarkFromObj(bookmarkObj, isPrivateDomain)
                    list.add(TrashedBookmarkItem(bookmark, trashedAt))
                }
                list
            }.getOrDefault(emptyList())
        }

        fun parseBookmarks(
            json: String,
            sampleBookmarks: List<BookmarkItem> = emptyList(),
            queueTagsMap: Map<String, Tag> = emptyMap(),
            isPrivateDomain: (String?) -> Boolean = { false }
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
                    var tag = Tag.fromDisplayValues(
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
                    val isPrivate = obj.readLegacyCompatiblePrivateFlag() ||
                        isPrivateDomain(DomainIconManager.extractDomain(url))
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
                            isPrivate = isPrivate
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

/**
 * Resolves the effective Private flag from [isPrivateDomain], merges [item] with any existing
 * row matched by URL or ID, validates RSS writes when applicable, and persists via
 * [BookmarkDao.insertReplacingUrl].
 */
internal suspend fun persistMergedBookmarkInTransaction(
    item: BookmarkItem,
    bookmarkDao: BookmarkDao?,
    isPrivateDomain: suspend (String?) -> Boolean,
    validateRssWrite: suspend (BookmarkItem, String) -> Unit
): BookmarkItem {
    val domain = DomainIconManager.extractDomain(item.url)
    val resolvedItem = if (!item.isPrivate && isPrivateDomain(domain)) {
        item.copy(isPrivate = true)
    } else {
        item
    }
    val existing = bookmarkDao?.getBookmarkByUrl(resolvedItem.url)
        ?: bookmarkDao?.getBookmarkById(resolvedItem.id)
    val finalItem = mergeWithExistingBookmark(resolvedItem, existing)
    if (finalItem.rssFeedId != null) {
        validateRssWrite(finalItem, finalItem.rssFeedId)
    }
    bookmarkDao?.insertReplacingUrl(BookmarkEntity.fromBookmarkItem(finalItem))
    return finalItem
}

/**
 * Preserves existing tag fields, notes, creation timestamp, and engagement counters
 * when updating an already-persisted bookmark row.
 */
internal fun mergeWithExistingBookmark(
    incoming: BookmarkItem,
    existing: BookmarkEntity?
): BookmarkItem {
    if (existing == null) return incoming
    val existingTag = existing.logicalTag()
    return incoming.copy(
        id = existing.id,
        tag = Tag(
            category = incoming.tag.category ?: existingTag.category,
            subcategory = incoming.tag.subcategory ?: existingTag.subcategory,
            entities = incoming.tag.entities.ifEmpty { existingTag.entities }
        ),
        note = if (incoming.note.isNotBlank()) incoming.note else existing.note,
        createdAt = existing.createdAt,
        isLiked = existing.isLiked,
        browserOpenCount = existing.browserOpenCount,
        detailViewCount = existing.detailViewCount,
        summaryPeekCount = existing.summaryPeekCount,
        lastInteractedAt = existing.lastInteractedAt
    )
}
