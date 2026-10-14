package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
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
                val existing = bookmarkDao.getAllBookmarks().map { it.toBookmarkItem() }
                warmUpDomainIconsAsync(existing)
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
     * Synchronizes domain policies (icons are loaded on demand by individual card composables).
     */
    private fun warmUpDomainIconsAsync(items: List<BookmarkItem>) {
        repositoryScope.launch {
            val domains = items.mapNotNull { DomainIconManager.extractDomain(it.url) }
                .distinct()

            DomainPolicyManager.syncDomains(domains)
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
            }
        }

        fun parseBookmarkFromObj(obj: JSONObject): BookmarkItem {
            val id = obj.getString("id")
            val url = obj.getString("url")
            val tagObj = obj.optJSONObject("tag")
            val entitiesList = mutableListOf<String>()
            tagObj?.optJSONArray("entities")?.let { ea ->
                for (j in 0 until ea.length()) {
                    entitiesList.add(ea.getString(j))
                }
            }
            val tag = Tag.create(
                category = tagObj?.optString("category", Tag.DEFAULT_TAG),
                subcategory = tagObj?.optString("subcategory", Tag.DEFAULT_TAG),
                entities = entitiesList
            )
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
                isNsfw = isNsfw
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
                            isNsfw = isNsfw
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
                    title = "Android Developers: Material 3 Expressive",
                    url = "https://m3.material.io/",
                    description = "Expressive design system featuring fluid motion, adaptive shapes, and modern layout tokens.",
                    tag = Tag.create(
                        category = "Article",
                        subcategory = "Tech",
                        entities = listOf("Material 3", "Compose", "Design System")
                    )
                ),
                BookmarkItem(
                    id = "b2",
                    title = "Gemini Nano によるオンデバイス生成AIの活用とアーキテクチャ",
                    url = "https://developer.android.com/ai/aicore",
                    description = "Android 14以降のAICoreシステムサービスを活用し、ローカル端末上でGemini Nanoを低遅延・高プライバシーに推論実行する仕組みの解説。",
                    tag = Tag.create(
                        category = "Article",
                        subcategory = "Tech",
                        entities = listOf("Gemini Nano", "AICore", "OnDevice")
                    )
                ),
                BookmarkItem(
                    id = "b3",
                    title = "映画レビュー・最新予告編＆メイキング映像特集",
                    url = "https://www.youtube.com/watch?v=sample-trailer",
                    description = "最新のSF大作のハイクオリティな予告編映像と、VFXメイキング解説コンテンツ。",
                    tag = Tag.create(
                        category = "Media",
                        subcategory = "Entertainment",
                        entities = listOf("YouTube", "Cinema", "VFX")
                    )
                ),
                BookmarkItem(
                    id = "b4",
                    title = "GitHub CLI & Actions ワークフロー自動化ガイド",
                    url = "https://cli.github.com/",
                    description = "コマンドラインからPRレビューやIssue管理、CI/CDパイプライン実行を行える開発者向けツール。",
                    tag = Tag.create(
                        category = "Tool",
                        subcategory = "Tech",
                        entities = listOf("GitHub", "Git", "CLI")
                    )
                ),
                BookmarkItem(
                    id = "b5",
                    title = "スペシャルティコーヒー生豆・焙煎器具オンラインストア",
                    url = "https://example-coffee.shop/items",
                    description = "世界中のシングルオリジン豆と家庭用ハンドドリップ器具を取り扱うセレクトショップ。",
                    tag = Tag.create(
                        category = "Commerce",
                        subcategory = "Food",
                        entities = listOf("Coffee", "Roastery", "Beans")
                    )
                ),
                BookmarkItem(
                    id = "b6",
                    title = "シャドウバース プレイヤーコミュニティ＆大会戦略フォーラム",
                    url = "https://community.shadowverse.example/forums",
                    description = "新パック環境のデッキ構築議論や大会メタレポート、プレイヤー同士の対戦募集掲示板。",
                    tag = Tag.create(
                        category = "Community",
                        subcategory = "Game",
                        entities = listOf("Shadowverse", "eSports", "Tournament")
                    )
                ),
                BookmarkItem(
                    id = "b7",
                    title = "JR東日本 列車運行情報・新幹線チケットレス予約",
                    url = "https://www.jreast.co.jp/",
                    description = "リアルタイムの列車運行状況確認や、新幹線・特急列車の座席指定予約ができる公式ポータル。",
                    tag = Tag.create(
                        category = "Official",
                        subcategory = "Travel",
                        entities = listOf("JR East", "Shinkansen", "Booking")
                    )
                ),
                BookmarkItem(
                    id = "b8",
                    title = "世界モバイルOS市場シェア推移レポート 2026",
                    url = "https://statcounter.com/mobile-os/",
                    description = "グローバルおよび地域別のスマートフォンOS利用シェア推移を可視化した統計・リファレンスデータ。",
                    tag = Tag.create(
                        category = "Reference",
                        subcategory = "Business",
                        entities = listOf("Market Data", "Mobile OS", "Statistics")
                    )
                ),
                BookmarkItem(
                    id = "b9",
                    title = "睡眠の質を高めるスマートウォッチ活用術",
                    url = "https://wellness-journal.example/sleep-optimization",
                    description = "睡眠ステージ分析や心拍変動トラッキングによるリカバリー向上の実践的なアドバイス。",
                    tag = Tag.create(
                        category = "Article",
                        subcategory = "Life",
                        entities = listOf("Sleep Tech", "Smartwatch", "Wellness")
                    )
                ),
                BookmarkItem(
                    id = "b10",
                    title = "Coursera | Professional Certificate Programs",
                    url = "https://www.coursera.org/",
                    description = "世界各国の主要大学や企業が提供するデータサイエンス・AI専門講座の公式認定プログラム。",
                    tag = Tag.create(
                        category = "Official",
                        subcategory = "Education",
                        entities = listOf("Coursera", "Data Science", "Certification")
                    )
                ),
                BookmarkItem(
                    id = "b11",
                    title = "週末ロードバイク車載POVライド映像ログ",
                    url = "https://vimeo.com/sample-cycling-pov",
                    description = "雄大な山岳コースを4K 60fpsで記録した、臨場感あふれるヒルクライムロードのライド動画。",
                    tag = Tag.create(
                        category = "Media",
                        subcategory = "Hobby",
                        entities = listOf("Cycling", "POV", "GoPro")
                    )
                ),
                BookmarkItem(
                    id = "b12",
                    title = "Notion – Connected workspace for wiki, docs & projects",
                    url = "https://www.notion.so/",
                    description = "ドキュメント、タスク管理、ナレッジベースを一元管理できる多機能プロダクティビティツール。",
                    tag = Tag.create(
                        category = "Tool",
                        subcategory = "Business",
                        entities = listOf("Notion", "Productivity", "Workspace")
                    )
                )
            )
        }
    }
}
