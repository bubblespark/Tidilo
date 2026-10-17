package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.queue.QueueItem
import me.bubblespark.tidilo.ui.screens.queue.QueueItemStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class QueueRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tidilo_queue_prefs", Context.MODE_PRIVATE)

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executionMutex = Mutex()

    private val _runningItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val runningItems: StateFlow<List<QueueItem>> = _runningItems.asStateFlow()

    private val _queuingItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val queuingItems: StateFlow<List<QueueItem>> = _queuingItems.asStateFlow()

    private val _errorItems = MutableStateFlow<List<QueueItem>>(emptyList())
    val errorItems: StateFlow<List<QueueItem>> = _errorItems.asStateFlow()

    init {
        loadFromPrefs()
        // Prefetch and resolve titles for queued items that have unresolved titles (e.g. domain name only)
        fetchTitlesForQueuingItems()
    }

    /**
     * Enqueues a URL into the Queuing group.
     * Completes HTML parsing, body text extraction, metadata detection, and domain icon resolution
     * BEFORE adding the item to the Queuing list.
     */
    fun enqueue(
        rawUrl: String,
        title: String? = null,
        onEnqueued: ((Result<QueueItem>) -> Unit)? = null
    ): Result<Int> {
        val trimmed = rawUrl.trim()
        if (!isValidHttpUrl(trimmed)) {
            val ex = IllegalArgumentException("Invalid HTTP/HTTPS URL")
            onEnqueued?.invoke(Result.failure(ex))
            return Result.failure(ex)
        }

        val estimatedCount = _queuingItems.value.size + 1

        repositoryScope.launch {
            try {
                // 1. Fetch & parse HTML, extract metadata, OGP, and Readability body snippet
                val parseResult = WebPageParser.parseUrl(trimmed)
                val parsed = parseResult.getOrNull()

                val realUrl = parsed?.resolvedUrl?.takeIf { it.isNotBlank() } ?: trimmed
                val hasCustomTitle = !title.isNullOrBlank() && !isRawUrlOrHost(title, trimmed)
                val realTitle = if (hasCustomTitle) {
                    title.trim()
                } else {
                    parsed?.ogpTags?.get("og:title")?.trim()?.takeIf { it.isNotBlank() }
                        ?: parsed?.title?.trim()?.takeIf { it.isNotBlank() }
                        ?: extractHostOrTitle(realUrl)
                }

                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = parsed?.ogpTags?.get("og:site_name")?.trim()?.takeIf { it.isNotBlank() }
                    ?: extractHostOrTitle(realUrl)
                DomainPolicyManager.registerDomain(domain, false, siteName)
                val isNsfw = parsed?.isNsfwSignal == true || DomainPolicyManager.isNsfwDomain(domain)

                // 2. Fetch and save domain icon (resolving watermark icon)
                if (domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
                    DomainIconManager.fetchAndSaveDomainIcon(
                        context = appContext,
                        url = realUrl,
                        appleTouchIconUrl = parsed?.appleTouchIconUrl,
                        faviconUrl = parsed?.faviconUrl
                    )
                }

                val description = parsed?.ogpTags?.get("og:description")
                    ?: parsed?.standardMetaTags?.get("description")
                    ?: ""
                val bodySnippet = (parsed?.readabilityArticle?.textContent?.takeIf { it.isNotBlank() }
                    ?: parsed?.jsoupBodyText
                    ?: "").take(800).replace("\n", " ")

                // 3. Create fully pre-parsed QueueItem and enqueue into Queuing group
                val newItem = QueueItem(
                    url = realUrl,
                    title = realTitle,
                    status = QueueItemStatus.QUEUED,
                    statusText = "Queued • Ready for AI",
                    isNsfw = isNsfw,
                    siteName = siteName,
                    description = description,
                    bodySnippet = bodySnippet
                )

                _queuingItems.value = listOf(newItem) + _queuingItems.value
                saveToPrefs()

                onEnqueued?.invoke(Result.success(newItem))
            } catch (e: Exception) {
                // Fallback: If network/parsing throws, enqueue fallback item with basic details
                val domain = DomainIconManager.extractDomain(trimmed)
                val isDomainNsfw = DomainPolicyManager.isNsfwDomain(domain)
                val fallbackItem = QueueItem(
                    url = trimmed,
                    title = title?.trim()?.takeIf { it.isNotBlank() } ?: extractHostOrTitle(trimmed),
                    status = QueueItemStatus.QUEUED,
                    statusText = "Queued • Added without metadata",
                    isNsfw = isDomainNsfw
                )
                _queuingItems.value = listOf(fallbackItem) + _queuingItems.value
                saveToPrefs()
                onEnqueued?.invoke(Result.failure(e))
            }
        }

        return Result.success(estimatedCount)
    }

    /**
     * "AI Tagging All": Moves all Queued URLs to Running and sequentially executes AI tagging.
     */
    fun aiTaggingAll() {
        val currentQueuing = _queuingItems.value
        if (currentQueuing.isEmpty()) return

        val convertedItems = currentQueuing.map { item ->
            item.copy(
                status = QueueItemStatus.WAITING_AI,
                statusText = "Waiting for AICore slot..."
            )
        }
        _runningItems.value = _runningItems.value + convertedItems
        _queuingItems.value = emptyList()
        saveToPrefs()

        // Sequentially trigger AI processing
        convertedItems.forEach { item ->
            executeItemAiTagging(item.id)
        }
    }

    /**
     * Moves a single Queued item to Running and starts AI tagging.
     */
    fun promoteItemToRunning(itemId: String) {
        val currentQueuing = _queuingItems.value
        val target = currentQueuing.find { it.id == itemId } ?: return

        val updatedQueuing = currentQueuing.filter { it.id != itemId }
        val promoted = target.copy(
            status = QueueItemStatus.WAITING_AI,
            statusText = "Waiting for AICore slot..."
        )
        _queuingItems.value = updatedQueuing
        _runningItems.value = _runningItems.value + listOf(promoted)
        saveToPrefs()

        // Asynchronously execute AI tagging
        executeItemAiTagging(promoted.id)
    }

    /**
     * Moves an Error item back to Running and retries AI tagging.
     */
    fun retryErrorItem(itemId: String) {
        val currentErrors = _errorItems.value
        val target = currentErrors.find { it.id == itemId } ?: return

        val updatedErrors = currentErrors.filter { it.id != itemId }
        val retried = target.copy(
            status = QueueItemStatus.WAITING_AI,
            statusText = "Waiting for AICore slot...",
            errorMessage = null
        )
        _errorItems.value = updatedErrors
        _runningItems.value = _runningItems.value + listOf(retried)
        saveToPrefs()

        executeItemAiTagging(retried.id)
    }

    /**
     * Removes the specified Queued item, returning the removed item and its original index (for Undo).
     */
    fun removeQueuingItem(itemId: String): Pair<QueueItem, Int>? {
        val current = _queuingItems.value
        val index = current.indexOfFirst { it.id == itemId }
        if (index == -1) return null
        val target = current[index]
        _queuingItems.value = current.filter { it.id != itemId }
        saveToPrefs()
        return target to index
    }

    /**
     * Restores a previously removed Queued item.
     */
    fun restoreQueuingItem(item: QueueItem, index: Int = 0) {
        val current = _queuingItems.value.toMutableList()
        val safeIndex = index.coerceIn(0, current.size)
        current.add(safeIndex, item)
        _queuingItems.value = current
        saveToPrefs()
    }

    /**
     * Removes the specified Error item, returning the removed item and its original index (for Undo).
     */
    fun removeErrorItem(itemId: String): Pair<QueueItem, Int>? {
        val current = _errorItems.value
        val index = current.indexOfFirst { it.id == itemId }
        if (index == -1) return null
        val target = current[index]
        _errorItems.value = current.filter { it.id != itemId }
        saveToPrefs()
        return target to index
    }

    /**
     * Restores a previously removed Error item.
     */
    fun restoreErrorItem(item: QueueItem, index: Int = 0) {
        val current = _errorItems.value.toMutableList()
        val safeIndex = index.coerceIn(0, current.size)
        current.add(safeIndex, item)
        _errorItems.value = current
        saveToPrefs()
    }

    private val foregroundRetryCounts = mutableMapOf<String, Int>()
    private val MAX_FOREGROUND_RETRIES = 3

    /**
     * Called when the app transitions to active/foreground.
     * Promotes Queuing tasks to Running if auto-run setting is enabled,
     * and resumes any interrupted or waiting Running tasks.
     */
    fun onAppBecameActive() {
        if (AppConfig.isAutoRunAiOnActive.value) {
            aiTaggingAll()
        }
        retryWaitingRunningItems()
    }

    /**
     * Resumes or retries all items in the Running group that are in WAITING_AI or interrupted state.
     */
    fun retryWaitingRunningItems() {
        val waiting = _runningItems.value.filter {
            it.status == QueueItemStatus.WAITING_AI || it.status == QueueItemStatus.PROCESSING_AI
        }
        waiting.forEach { item ->
            executeItemAiTagging(item.id)
        }
    }

    /**
     * Executes the Gemini Nano tagging and summarization pipeline for the specified item.
     */
    fun executeItemAiTagging(itemId: String) {
        repositoryScope.launch {
            executionMutex.withLock {
                val target = _runningItems.value.find { it.id == itemId } ?: return@withLock

                // If app is currently in background, do not start inference; wait for foreground
                if (!TidiloApplication.instance.isAppInForeground) {
                    updateRunningItemStatus(
                        itemId = itemId,
                        status = QueueItemStatus.WAITING_AI,
                        statusText = "Paused • Retrying when app is active"
                    )
                    return@withLock
                }

                // 1. Update status to PROCESSING_AI
                updateRunningItemStatus(
                    itemId = itemId,
                    status = QueueItemStatus.PROCESSING_AI,
                    statusText = "Gemini Nano generating semantic tags..."
                )

                try {
                    // 2. Gemini Nano inference using precomputed metadata -> Save to Library
                    val result = GeminiTaggingService.processAndSaveBookmark(
                        url = target.url,
                        language = AppConfig.currentLanguage.value,
                        preferredTitle = target.title,
                        bodySnippet = target.bodySnippet.takeIf { it.isNotBlank() },
                        description = target.description.takeIf { it.isNotBlank() },
                        siteName = target.siteName.takeIf { it.isNotBlank() },
                        isNsfw = target.isNsfw
                    )

                    if (result.isSuccess) {
                        // 3. Success: Remove from Running and register in library
                        foregroundRetryCounts.remove(itemId)
                        _runningItems.value = _runningItems.value.filter { it.id != itemId }
                        saveToPrefs()
                    } else {
                        // Failure: check if it should be retried or moved to Error
                        val throwable = result.exceptionOrNull() ?: Exception("Unknown tagging failure")
                        handleExecutionFailure(target, throwable)
                    }
                } catch (e: Exception) {
                    handleExecutionFailure(target, e)
                }
            }
        }
    }

    /**
     * Handles failure during AI tagging execution.
     * If the failure occurred due to backgrounding, app switching, or AICore unavailability,
     * retains the item in Running with WAITING_AI status for automatic retry instead of failing to Error.
     */
    private fun handleExecutionFailure(target: QueueItem, throwable: Throwable) {
        val errorMessage = throwable.message.orEmpty()
        val isBackground = !TidiloApplication.instance.isAppInForeground
        val isAiCoreError = isAiCoreOrInterruptionException(throwable)

        if (isBackground || isAiCoreError) {
            // App switched to background or AICore service interrupted: do NOT move to Error.
            // Retain in Running group with WAITING_AI status to be automatically retried.
            updateRunningItemStatus(
                itemId = target.id,
                status = QueueItemStatus.WAITING_AI,
                statusText = "Paused • Retrying when app is active"
            )
            // If app is active in foreground, retry with backoff up to limit
            if (TidiloApplication.instance.isAppInForeground) {
                val retryCount = (foregroundRetryCounts[target.id] ?: 0) + 1
                foregroundRetryCounts[target.id] = retryCount
                if (retryCount <= MAX_FOREGROUND_RETRIES) {
                    updateRunningItemStatus(
                        itemId = target.id,
                        status = QueueItemStatus.WAITING_AI,
                        statusText = "Temporary AICore issue • Retrying ($retryCount/$MAX_FOREGROUND_RETRIES)..."
                    )
                    repositoryScope.launch {
                        delay(retryCount * 1500L)
                        if (TidiloApplication.instance.isAppInForeground) {
                            executeItemAiTagging(target.id)
                        }
                    }
                } else {
                    foregroundRetryCounts.remove(target.id)
                    moveRunningToError(target, errorMessage)
                }
            }
        } else {
            // Permanent failure (e.g., HTTP 404, invalid URL syntax)
            foregroundRetryCounts.remove(target.id)
            moveRunningToError(target, errorMessage.ifBlank { "Unknown failure" })
        }
    }

    private fun moveRunningToError(target: QueueItem, errorMessage: String) {
        _runningItems.value = _runningItems.value.filter { it.id != target.id }
        val errorItem = target.copy(
            status = QueueItemStatus.ERROR,
            statusText = "Failed",
            errorMessage = errorMessage
        )
        _errorItems.value = listOf(errorItem) + _errorItems.value
        saveToPrefs()
    }

    private fun updateRunningItemStatus(itemId: String, status: QueueItemStatus, statusText: String) {
        _runningItems.value = _runningItems.value.map { item ->
            if (item.id == itemId) {
                item.copy(status = status, statusText = statusText)
            } else {
                item
            }
        }
        saveToPrefs()
    }

    /**
     * Fetches metadata and body snippets in background for any legacy/unparsed Queuing items,
     * ensuring they have precomputed data ready for zero-overhead Running execution.
     */
    private fun fetchAndPreparseItemAsync(itemId: String, url: String) {
        repositoryScope.launch {
            try {
                val parsed = WebPageParser.parseUrl(url).getOrNull() ?: return@launch
                val realTitle = parsed.ogpTags["og:title"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed.title?.trim()?.takeIf { it.isNotBlank() }
                val realUrl = parsed.resolvedUrl.takeIf { it.isNotBlank() } ?: url
                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = parsed.ogpTags["og:site_name"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: extractHostOrTitle(realUrl)
                DomainPolicyManager.registerDomain(domain, false, siteName)
                val isNsfw = parsed.isNsfwSignal || DomainPolicyManager.isNsfwDomain(domain)

                // Fetch domain icon (saves only >=96x96 without duplicates)
                if (domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
                    DomainIconManager.fetchAndSaveDomainIcon(
                        context = appContext,
                        url = realUrl,
                        appleTouchIconUrl = parsed.appleTouchIconUrl,
                        faviconUrl = parsed.faviconUrl
                    )
                }

                val description = parsed.ogpTags["og:description"]
                    ?: parsed.standardMetaTags["description"]
                    ?: ""
                val bodySnippet = (parsed.readabilityArticle?.textContent?.takeIf { it.isNotBlank() }
                    ?: parsed.jsoupBodyText).take(800).replace("\n", " ")

                val hasNewTitle = !realTitle.isNullOrBlank()

                val updateItem = { item: QueueItem ->
                    if (item.id == itemId) {
                        item.copy(
                            title = if (hasNewTitle) realTitle else item.title,
                            url = realUrl,
                            isNsfw = isNsfw,
                            siteName = siteName,
                            description = description,
                            bodySnippet = bodySnippet
                        )
                    } else {
                        item
                    }
                }
                _queuingItems.value = _queuingItems.value.map(updateItem)
                _runningItems.value = _runningItems.value.map(updateItem)
                saveToPrefs()
            } catch (_: Exception) {
                // Ignore failure and retain current values
            }
        }
    }

    private fun fetchTitlesForQueuingItems() {
        repositoryScope.launch {
            val toFetch = _queuingItems.value.filter {
                it.bodySnippet.isBlank() || isRawUrlOrHost(it.title, it.url) || WebPageParser.isWrapperUrl(it.url)
            }
            toFetch.forEach { item ->
                fetchAndPreparseItemAsync(item.id, item.url)
            }
        }
    }

    val currentQueuingCount: Int
        get() = _queuingItems.value.size

    private fun loadFromPrefs() {
        val runningJson = prefs.getString("running_items", null)
        val queuingJson = prefs.getString("queuing_items", null)
        val errorJson = prefs.getString("error_items", null)

        // Filter out legacy test Running task (run-1)
        val running = parseItems(runningJson).filterNot {
            it.id == "run-1" || it.url == "https://source.android.com/devices/tech/gemini-nano"
        }
        // Filter out legacy demo queuing items
        val queuing = parseItems(queuingJson).filterNot {
            it.id in setOf("queue-1", "queue-2", "queue-3", "queue-4", "queue-5", "queue-6")
        }
        // Filter out legacy demo error item
        val error = parseItems(errorJson).filterNot {
            it.id == "err-1"
        }

        if (runningJson == null && queuingJson == null) {
            // Clean empty state on first launch: no demo seed tasks
            _runningItems.value = emptyList()
            _queuingItems.value = emptyList()
            _errorItems.value = emptyList()

            saveToPrefs()
        } else {
            _runningItems.value = running
            _queuingItems.value = queuing
            _errorItems.value = error
            // Purge legacy demo items or completed items from SharedPreferences immediately
            if (running.size != parseItems(runningJson).size ||
                queuing.size != parseItems(queuingJson).size ||
                error.size != parseItems(errorJson).size ||
                prefs.contains("completed_items")
            ) {
                saveToPrefs()
            }
        }
    }

    private fun saveToPrefs() {
        val running = _runningItems.value
        val queuing = _queuingItems.value
        val error = _errorItems.value
        repositoryScope.launch {
            val runningJson = serializeItems(running)
            val queuingJson = serializeItems(queuing)
            val errorJson = serializeItems(error)
            prefs.edit()
                .putString("running_items", runningJson)
                .putString("queuing_items", queuingJson)
                .putString("error_items", errorJson)
                .remove("completed_items")
                .apply()
        }
    }

    private fun serializeItems(items: List<QueueItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("url", item.url)
                put("title", item.title)
                put("status", item.status.name)
                put("statusText", item.statusText)
                if (item.errorMessage != null) put("errorMessage", item.errorMessage)
                if (item.assignedTags.isNotEmpty()) {
                    val tagsArr = JSONArray()
                    item.assignedTags.forEach { tagsArr.put(it) }
                    put("assignedTags", tagsArr)
                }
                put("createdAt", item.createdAt)
                put("isNsfw", item.isNsfw)
                put("siteName", item.siteName)
                put("description", item.description)
                put("bodySnippet", item.bodySnippet)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseItems(json: String?): List<QueueItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val list = mutableListOf<QueueItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", QueueItemStatus.QUEUED.name)
                val status = runCatching { QueueItemStatus.valueOf(statusStr) }.getOrDefault(QueueItemStatus.QUEUED)

                val tagsList = mutableListOf<String>()
                obj.optJSONArray("assignedTags")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        tagsList.add(arr.getString(j))
                    }
                }
                val isNsfw = obj.optBoolean("isNsfw", false)

                list.add(
                    QueueItem(
                        id = obj.getString("id"),
                        url = obj.getString("url"),
                        title = obj.getString("title"),
                        status = status,
                        statusText = obj.optString("statusText", ""),
                        errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotBlank() },
                        assignedTags = tagsList,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        isNsfw = isNsfw,
                        siteName = obj.optString("siteName", ""),
                        description = obj.optString("description", ""),
                        bodySnippet = obj.optString("bodySnippet", "")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    companion object {
        @Volatile
        private var INSTANCE: QueueRepository? = null

        fun getInstance(context: Context): QueueRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QueueRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isValidHttpUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return false
            }
            return runCatching {
                val uri = URI(trimmed)
                !uri.host.isNullOrBlank() && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
            }.getOrDefault(false)
        }

        fun extractUrlFromText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val trimmed = text.trim()
            if (isValidHttpUrl(trimmed)) return trimmed

            val urlRegex = Regex("""https?://[^\s<>"{}|\\^`]+""")
            val match = urlRegex.find(trimmed)?.value
            if (match != null && isValidHttpUrl(match)) {
                return match
            }
            return null
        }

        fun extractHostOrTitle(url: String): String {
            return runCatching {
                val uri = URI(url)
                uri.host ?: url
            }.getOrDefault(url)
        }

        fun isRawUrlOrHost(title: String, url: String): Boolean {
            val host = extractHostOrTitle(url)
            return title.equals(url, ignoreCase = true) ||
                    title.equals(host, ignoreCase = true) ||
                    title.startsWith("http://", ignoreCase = true) ||
                    title.startsWith("https://", ignoreCase = true)
        }

        fun isAiCoreOrInterruptionException(throwable: Throwable): Boolean {
            val msg = throwable.message.orEmpty()
            val className = throwable.javaClass.name
            return msg.contains("AICore", ignoreCase = true) ||
                    msg.contains("rate limit", ignoreCase = true) ||
                    msg.contains("UNAVAILABLE", ignoreCase = true) ||
                    msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    msg.contains("Inference Failed", ignoreCase = true) ||
                    className.contains("genai", ignoreCase = true) ||
                    className.contains("RemoteException", ignoreCase = true) ||
                    className.contains("CancellationException", ignoreCase = true)
        }
    }
}
