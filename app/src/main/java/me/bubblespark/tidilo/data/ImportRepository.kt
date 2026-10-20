package me.bubblespark.tidilo.data

import android.content.Context
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.OgImageDao
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.json.JSONArray
import java.net.URI
import java.util.UUID

class ImportRepository internal constructor(
    context: Context? = null,
    private val taskDao: ImportTaskDao = context?.let { AppDatabase.getInstance(it).importTaskDao() }
        ?: throw IllegalArgumentException("taskDao must be provided if context is null"),
    private val ogImageDao: OgImageDao? = context?.let { AppDatabase.getInstance(it).ogImageDao() }
) {

    private val appContext: Context? = context?.applicationContext
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executionMutex = Mutex()

    private val _processingTasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val processingTasks: StateFlow<List<ImportTask>> = _processingTasks.asStateFlow()

    private val _readyTasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val readyTasks: StateFlow<List<ImportTask>> = _readyTasks.asStateFlow()

    private val _importingTasks = MutableStateFlow<List<ImportingTask>>(emptyList())
    val importingTasks: StateFlow<List<ImportingTask>> = _importingTasks.asStateFlow()

    private val _errorTasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val errorTasks: StateFlow<List<ImportTask>> = _errorTasks.asStateFlow()

    private val _quotaExceededDialogVisible = MutableStateFlow(false)
    val quotaExceededDialogVisible: StateFlow<Boolean> = _quotaExceededDialogVisible.asStateFlow()

    private val _aiProcessingProgress = MutableStateFlow(AiProcessingProgress())
    val aiProcessingProgress: StateFlow<AiProcessingProgress> = _aiProcessingProgress.asStateFlow()

    private var batchProgressState = BatchProgressState()
    private var isAiCancelPending = false
    private val cancelledTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun dismissQuotaExceededDialog() {
        _quotaExceededDialogVisible.value = false
    }

    init {
        context?.let {
            repositoryScope.launch {
                if (taskDao.getCount() == 0) {
                    migrateFromLegacyPrefs()
                }
                fetchTitlesForReadyTasks()
            }
        }

        // Reactively observe Room database changes and populate StateFlows
        repositoryScope.launch {
            taskDao.getAllTasksFlow().collect { entities ->
                val all = entities.map { it.toImportTask() }
                val processing = all.filter {
                    it.status == TaskStatus.PROCESSING_AI || it.status == TaskStatus.WAITING_AI
                }
                _processingTasks.value = processing
                _readyTasks.value = all.filter {
                    it.status == TaskStatus.READY
                }
                _errorTasks.value = all.filter {
                    it.status == TaskStatus.ERROR
                }
                updateBatchProgress(processing)
            }
        }
    }

    private fun updateBatchProgress(processingTasks: List<ImportTask>) {
        val (progress, newState) = computeBatchProgress(
            processingTasks = processingTasks,
            state = batchProgressState,
            isCancelPending = isAiCancelPending
        )
        batchProgressState = newState
        if (!progress.isProcessing) {
            isAiCancelPending = false
            cancelledTaskIds.clear()
        }
        _aiProcessingProgress.value = progress
    }

    /**
     * Imports a URL into the Ready group.
     * Completes HTML parsing, body text extraction, metadata detection, and domain icon resolution
     * BEFORE storing the item in the database.
     */
    fun importUrl(
        rawUrl: String,
        title: String? = null,
        onImported: ((Result<ImportTask>) -> Unit)? = null
    ): Result<Int> {
        val trimmed = rawUrl.trim()
        if (!isValidHttpUrl(trimmed)) {
            val ex = IllegalArgumentException("Invalid HTTP/HTTPS URL")
            onImported?.invoke(Result.failure(ex))
            return Result.failure(ex)
        }

        val importing = ImportingTask(url = trimmed, title = title)
        _importingTasks.update { it + importing }

        val estimatedCount = _readyTasks.value.size + _importingTasks.value.size

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
                if (appContext != null && domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
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
                val ogImageUrl = parsed?.ogpTags?.get("og:image")?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed?.twitterTags?.get("twitter:image")?.trim()?.takeIf { it.isNotBlank() }

                // 3. Create fully pre-parsed ImportTask and save into database
                val newItem = ImportTask(
                    url = realUrl,
                    title = realTitle,
                    status = TaskStatus.READY,
                    statusText = "Ready for AI Analysis",
                    isNsfw = isNsfw,
                    siteName = siteName,
                    description = description,
                    bodySnippet = bodySnippet,
                    ogImageUrl = ogImageUrl
                )

                taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(newItem))
                onImported?.invoke(Result.success(newItem))
            } catch (e: Exception) {
                // Fallback: If network/parsing throws, create fallback task with basic details
                val domain = DomainIconManager.extractDomain(trimmed)
                val isDomainNsfw = DomainPolicyManager.isNsfwDomain(domain)
                val fallbackItem = ImportTask(
                    url = trimmed,
                    title = title?.trim()?.takeIf { it.isNotBlank() } ?: extractHostOrTitle(trimmed),
                    status = TaskStatus.READY,
                    statusText = "Ready • Added without metadata",
                    isNsfw = isDomainNsfw
                )
                taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(fallbackItem))
                onImported?.invoke(Result.failure(e))
            } finally {
                _importingTasks.update { list -> list.filter { it.id != importing.id } }
            }
        }

        return Result.success(estimatedCount)
    }

    /**
     * "AI Analysis All": Moves all Ready URLs to Processing and sequentially executes AI analysis.
     */
    fun aiAnalysisAll() {
        isAiCancelPending = false
        cancelledTaskIds.clear()
        batchProgressState = BatchProgressState()
        repositoryScope.launch {
            val readyEntities = taskDao.getTasksByStatuses(listOf(TaskStatus.READY.name))
            if (readyEntities.isEmpty()) return@launch

            val converted = readyEntities.map { entity ->
                val item = entity.toImportTask()
                item.copy(
                    status = TaskStatus.WAITING_AI,
                    statusText = "Waiting for AICore slot..."
                )
            }
            taskDao.insertAll(converted.map { ImportTaskEntity.fromImportTask(it) })

            converted.forEach { item ->
                executeTaskAiAnalysis(item.id)
            }
        }
    }

    /**
     * Moves a single Ready task to Processing and starts AI analysis.
     */
    fun promoteTaskToProcessing(taskId: String) {
        cancelledTaskIds.remove(taskId)
        repositoryScope.launch {
            val targetEntity = taskDao.getTaskById(taskId) ?: return@launch
            val target = targetEntity.toImportTask()
            val promoted = target.copy(
                status = TaskStatus.WAITING_AI,
                statusText = "Waiting for AICore slot..."
            )
            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(promoted))
            executeTaskAiAnalysis(promoted.id)
        }
    }

    /**
     * Cancels pending queue items: restores all WAITING_AI tasks back to READY,
     * while allowing any currently executing PROCESSING_AI task to finish cleanly.
     */
    fun cancelProcessing() {
        isAiCancelPending = true
        _aiProcessingProgress.value = _aiProcessingProgress.value.copy(isCancelPending = true)
        repositoryScope.launch {
            val waitingOrActive = taskDao.getTasksByStatuses(
                listOf(TaskStatus.WAITING_AI.name, TaskStatus.PROCESSING_AI.name)
            )
            cancelledTaskIds.addAll(waitingOrActive.map { it.id })

            val waitingEntities = waitingOrActive.filter { it.status == TaskStatus.WAITING_AI.name }
            if (waitingEntities.isNotEmpty()) {
                val restored = waitingEntities.map { entity ->
                    entity.toImportTask().copy(
                        status = TaskStatus.READY,
                        statusText = ""
                    )
                }
                taskDao.insertAll(restored.map { ImportTaskEntity.fromImportTask(it) })
            }
        }
    }

    /**
     * Moves an Error task back to Processing and retries AI analysis.
     */
    fun retryErrorTask(taskId: String) {
        repositoryScope.launch {
            val targetEntity = taskDao.getTaskById(taskId) ?: return@launch
            val target = targetEntity.toImportTask()
            val retried = target.copy(
                status = TaskStatus.WAITING_AI,
                statusText = "Waiting for AICore slot...",
                errorMessage = null
            )
            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(retried))
            executeTaskAiAnalysis(retried.id)
        }
    }

    /**
     * Removes the specified Ready task, returning the removed task and its original index (for Undo).
     */
    fun removeReadyTask(taskId: String): Pair<ImportTask, Int>? {
        val current = _readyTasks.value
        val index = current.indexOfFirst { it.id == taskId }
        if (index == -1) return null
        val target = current[index]
        repositoryScope.launch {
            taskDao.deleteById(taskId)
        }
        return target to index
    }

    /**
     * Restores a previously removed Ready task.
     */
    fun restoreReadyTask(task: ImportTask, index: Int = 0) {
        repositoryScope.launch {
            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(task))
        }
    }

    /**
     * Removes the specified Error task, returning the removed task and its original index (for Undo).
     */
    fun removeErrorTask(taskId: String): Pair<ImportTask, Int>? {
        val current = _errorTasks.value
        val index = current.indexOfFirst { it.id == taskId }
        if (index == -1) return null
        val target = current[index]
        repositoryScope.launch {
            taskDao.deleteById(taskId)
        }
        return target to index
    }

    /**
     * Restores a previously removed Error task.
     */
    fun restoreErrorTask(task: ImportTask, index: Int = 0) {
        repositoryScope.launch {
            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(task))
        }
    }

    private val foregroundRetryCounts = mutableMapOf<String, Int>()
    private val MAX_FOREGROUND_RETRIES = 3
    private val RETRY_INTERVAL_MS = 3000L

    /**
     * Called when the app transitions to active/foreground.
     * Promotes Ready tasks to Processing if auto-run setting is enabled,
     * and resumes any interrupted or waiting Processing tasks.
     */
    fun onAppBecameActive() {
        if (AppConfig.isAutoRunAiOnActive.value) {
            aiAnalysisAll()
        }
        retryWaitingProcessingTasks()
    }

    /**
     * Resumes or retries all tasks in the Processing group that are in WAITING_AI or interrupted state.
     */
    fun retryWaitingProcessingTasks() {
        repositoryScope.launch {
            val waitingEntities = taskDao.getTasksByStatuses(
                listOf(TaskStatus.WAITING_AI.name, TaskStatus.PROCESSING_AI.name)
            )
            waitingEntities.forEach { entity ->
                executeTaskAiAnalysis(entity.id)
            }
        }
    }

    /**
     * Executes the Gemini Nano analysis and summarization pipeline for the specified task.
     */
    fun executeTaskAiAnalysis(taskId: String) {
        repositoryScope.launch {
            executionMutex.withLock {
                val targetEntity = taskDao.getTaskById(taskId) ?: return@withLock
                val target = targetEntity.toImportTask()

                // If cancellation is requested and task is in the cancelled queue, restore to READY and skip
                val shouldCancel = isAiCancelPending && (cancelledTaskIds.contains(taskId) || cancelledTaskIds.isEmpty())
                if (shouldCancel && target.status == TaskStatus.WAITING_AI) {
                    cancelledTaskIds.remove(taskId)
                    val restored = target.copy(status = TaskStatus.READY, statusText = "")
                    taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(restored))
                    return@withLock
                }

                // If task is no longer in a runnable state (e.g. deleted or already completed/errored), skip
                if (target.status != TaskStatus.WAITING_AI && target.status != TaskStatus.PROCESSING_AI) {
                    return@withLock
                }

                // If app is currently in background, do not start inference; wait for foreground
                if (!TidiloApplication.instance.isAppInForeground) {
                    updateProcessingTaskStatus(
                        taskId = taskId,
                        status = TaskStatus.WAITING_AI,
                        statusText = "Paused • Retrying when app is active"
                    )
                    return@withLock
                }

                // 1. Update status to PROCESSING_AI
                updateProcessingTaskStatus(
                    taskId = taskId,
                    status = TaskStatus.PROCESSING_AI,
                    statusText = "Gemini Nano analyzing content..."
                )

                // 1.5 Cache og:image into database during Processing (Issue #57)
                val activeOgImageDao = ogImageDao ?: appContext?.let { AppDatabase.getInstance(it).ogImageDao() }
                if (activeOgImageDao != null) {
                    val candidateOgImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() }
                        ?: runCatching {
                            WebPageParser.parseUrl(target.url).getOrNull()?.let {
                                it.ogpTags["og:image"] ?: it.twitterTags["twitter:image"]
                            }
                        }.getOrNull()
                    if (!candidateOgImageUrl.isNullOrBlank()) {
                        runCatching {
                            OgImageManager.processAndSaveOgImage(
                                pageUrl = target.url,
                                ogImageUrl = candidateOgImageUrl,
                                ogImageDao = activeOgImageDao
                            )
                        }
                    }
                }

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
                        // 3. Success: Remove from database and register in library
                        foregroundRetryCounts.remove(taskId)
                        taskDao.deleteById(taskId)
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
     * Handles failure during AI analysis execution in accordance with Issue #22:
     * - Background: Suspend execution, keep task in Processing with WAITING_AI status until foreground.
     * - BUSY (9): Retry up to 3 times with 3-second interval. On retry limit, fail entire Processing batch.
     * - PER_APP_BATTERY_USE_QUOTA_EXCEEDED (27):
     *     - Show dialog: "AICore使用制限を超過しました。利用が制限されています。"
     *     - Move current URL and all other Waiting Processing tasks back to Ready group.
     * - BACKGROUND_USE_BLOCKED (30):
     *     - If in foreground, retry up to 3 times with 3-second interval. On retry limit, fail entire Processing batch.
     *     - If in background, pause and wait for foreground.
     * - Retry limit reached OR any other errors:
     *     - Move current task and all other pending Processing tasks to Error group.
     */
    private fun handleExecutionFailure(target: ImportTask, throwable: Throwable) {
        val errorMessage = throwable.message.orEmpty()
        val isForeground = TidiloApplication.instance.isAppInForeground
        val errorCode = extractAiCoreErrorCode(throwable)

        // 1. If app is in background, suspend and retain in Processing with WAITING_AI status
        if (!isForeground) {
            updateProcessingTaskStatus(
                taskId = target.id,
                status = TaskStatus.WAITING_AI,
                statusText = "Paused • Retrying when app is active"
            )
            return
        }

        // 2. Handle specific AICore error codes
        when (errorCode) {
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> {
                if (isFallbackApplicable(errorCode, throwable)) {
                    saveBookmarkFallback(target)
                } else {
                    moveAllProcessingToReady()
                }
            }

            GenAiException.ErrorCode.BUSY -> {
                val retryCount = (foregroundRetryCounts[target.id] ?: 0) + 1
                foregroundRetryCounts[target.id] = retryCount
                if (retryCount <= MAX_FOREGROUND_RETRIES) {
                    updateProcessingTaskStatus(
                        taskId = target.id,
                        status = TaskStatus.WAITING_AI,
                        statusText = "AICore busy • Retrying ($retryCount/$MAX_FOREGROUND_RETRIES)..."
                    )
                    repositoryScope.launch {
                        delay(RETRY_INTERVAL_MS)
                        if (TidiloApplication.instance.isAppInForeground) {
                            executeTaskAiAnalysis(target.id)
                        }
                    }
                } else {
                    if (isFallbackApplicable(errorCode, throwable)) {
                        saveBookmarkFallback(target)
                    } else {
                        moveAllProcessingToError(target, errorMessage.ifBlank { "AICore busy: Retry limit exceeded (3/3)" })
                    }
                }
            }

            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> {
                val retryCount = (foregroundRetryCounts[target.id] ?: 0) + 1
                foregroundRetryCounts[target.id] = retryCount
                if (retryCount <= MAX_FOREGROUND_RETRIES) {
                    updateProcessingTaskStatus(
                        taskId = target.id,
                        status = TaskStatus.WAITING_AI,
                        statusText = "AICore background blocked • Retrying ($retryCount/$MAX_FOREGROUND_RETRIES)..."
                    )
                    repositoryScope.launch {
                        delay(RETRY_INTERVAL_MS)
                        if (TidiloApplication.instance.isAppInForeground) {
                            executeTaskAiAnalysis(target.id)
                        }
                    }
                } else {
                    if (isFallbackApplicable(errorCode, throwable)) {
                        saveBookmarkFallback(target)
                    } else {
                        moveAllProcessingToError(target, errorMessage.ifBlank { "AICore background blocked: Retry limit exceeded (3/3)" })
                    }
                }
            }

            else -> {
                if (isFallbackApplicable(errorCode, throwable)) {
                    saveBookmarkFallback(target)
                } else {
                    moveAllProcessingToError(target, errorMessage.ifBlank { "AICore inference failure" })
                }
            }
        }
    }

    private fun isAiCoreError(errorCode: Int?, throwable: Throwable): Boolean {
        return errorCode != null || isAiCoreOrInterruptionException(throwable)
    }

    private fun isFallbackApplicable(errorCode: Int?, throwable: Throwable): Boolean {
        return Companion.isFallbackApplicable(AppConfig.aiCoreFallbackMode.value, errorCode, throwable)
    }

    private fun saveBookmarkFallback(target: ImportTask) {
        foregroundRetryCounts.remove(target.id)
        repositoryScope.launch {
            taskDao.deleteById(target.id)
        }

        val domain = DomainIconManager.extractDomain(target.url)
        DomainPolicyManager.registerDomain(domain, false, target.siteName)
        if (appContext != null && domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
            repositoryScope.launch {
                DomainIconManager.fetchAndSaveDomainIcon(appContext, target.url)
            }
        }

        val fallbackBookmark = BookmarkItem(
            id = UUID.randomUUID().toString(),
            title = target.title,
            url = target.url,
            description = target.description,
            tag = Tag.createEmpty(),
            isNsfw = target.isNsfw,
            isAiSkipped = true
        )
        BookmarkRepository.getInstance().addBookmark(fallbackBookmark)
    }

    /**
     * Moves all tasks currently in Processing back to the Ready group due to battery quota limit.
     */
    private fun moveAllProcessingToReady() {
        _quotaExceededDialogVisible.value = true
        foregroundRetryCounts.clear()
        repositoryScope.launch {
            val currentProcessing = taskDao.getTasksByStatuses(
                listOf(TaskStatus.PROCESSING_AI.name, TaskStatus.WAITING_AI.name)
            ).map { it.toImportTask() }
            val converted = currentProcessing.map { item ->
                item.copy(
                    status = TaskStatus.READY,
                    statusText = "Ready • Quota exceeded"
                )
            }
            taskDao.insertAll(converted.map { ImportTaskEntity.fromImportTask(it) })
        }
    }

    /**
     * Removes the failed task and all other tasks in Processing, moving them to the Error group.
     */
    private fun moveAllProcessingToError(primaryFailedItem: ImportTask, errorMessage: String) {
        val fallbackMsg = errorMessage.ifBlank { "Unknown failure" }
        foregroundRetryCounts.clear()
        repositoryScope.launch {
            val currentProcessing = taskDao.getTasksByStatuses(
                listOf(TaskStatus.PROCESSING_AI.name, TaskStatus.WAITING_AI.name)
            ).map { it.toImportTask() }
            val errorItemsToAdd = currentProcessing.map { item ->
                item.copy(
                    status = TaskStatus.ERROR,
                    statusText = "Failed",
                    errorMessage = if (item.id == primaryFailedItem.id) {
                        fallbackMsg
                    } else {
                        "Batch stopped: $fallbackMsg"
                    }
                )
            }
            taskDao.insertAll(errorItemsToAdd.map { ImportTaskEntity.fromImportTask(it) })
        }
    }

    private fun updateProcessingTaskStatus(taskId: String, status: TaskStatus, statusText: String) {
        repositoryScope.launch {
            taskDao.updateStatus(taskId, status.name, statusText)
        }
    }

    private fun fetchAndPreparseTaskAsync(taskId: String, url: String) {
        repositoryScope.launch {
            try {
                val parsed = WebPageParser.parseUrl(url).getOrNull() ?: return@launch
                val realTitle = parsed.ogpTags["og:title"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed.title?.trim()?.takeIf { it.isNotBlank() }
                val realUrl = parsed.resolvedUrl.takeIf { it.isNotBlank() } ?: url
                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = parsed.ogpTags["og:site_name"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: extractHostOrTitle(realUrl)

                if (domain != null) {
                    DomainPolicyManager.registerDomain(domain, false, siteName)
                    if (appContext != null && !DomainIconManager.hasIcon(appContext, domain)) {
                        DomainIconManager.fetchAndSaveDomainIcon(
                            context = appContext,
                            url = realUrl,
                            appleTouchIconUrl = parsed.appleTouchIconUrl,
                            faviconUrl = parsed.faviconUrl
                        )
                    }
                }

                val description = parsed.ogpTags["og:description"]
                    ?: parsed.standardMetaTags["description"]
                    ?: ""
                val bodySnippet = (parsed.readabilityArticle?.textContent?.takeIf { it.isNotBlank() }
                    ?: parsed.jsoupBodyText).take(800).replace("\n", " ")

                val isNsfw = parsed.isNsfwSignal || (domain != null && DomainPolicyManager.isNsfwDomain(domain))

                val existing = taskDao.getTaskById(taskId)
                if (existing != null) {
                    val updated = existing.copy(
                        url = realUrl,
                        title = if (realTitle != null && isRawUrlOrHost(existing.title, existing.url)) realTitle else existing.title,
                        siteName = siteName,
                        description = description.ifBlank { existing.description },
                        bodySnippet = bodySnippet.ifBlank { existing.bodySnippet },
                        isNsfw = isNsfw || existing.isNsfw
                    )
                    taskDao.update(updated)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchTitlesForReadyTasks() {
        repositoryScope.launch {
            val toFetch = _readyTasks.value.filter {
                isRawUrlOrHost(it.title, it.url) || it.bodySnippet.isBlank()
            }
            toFetch.forEach { item ->
                fetchAndPreparseTaskAsync(item.id, item.url)
            }
        }
    }

    val readyCount: Int
        get() = _readyTasks.value.size

    private suspend fun migrateFromLegacyPrefs() {
        val legacyPrefs = appContext?.getSharedPreferences("tidilo_queue_prefs", Context.MODE_PRIVATE) ?: return
        val runningJson = legacyPrefs.getString("running_items", null)
        val queuingJson = legacyPrefs.getString("queuing_items", null)
        val errorJson = legacyPrefs.getString("error_items", null)

        val toInsert = mutableListOf<ImportTaskEntity>()
        parseLegacyItems(runningJson).filterNot {
            it.id in setOf("queue-1", "queue-2", "queue-3", "queue-4", "queue-5", "queue-6")
        }.forEach {
            toInsert.add(ImportTaskEntity.fromImportTask(it))
        }

        parseLegacyItems(queuingJson).filterNot {
            it.id in setOf("queue-1", "queue-2", "queue-3", "queue-4", "queue-5", "queue-6")
        }.forEach {
            toInsert.add(ImportTaskEntity.fromImportTask(it))
        }

        parseLegacyItems(errorJson).filterNot {
            it.id == "err-1"
        }.forEach {
            toInsert.add(ImportTaskEntity.fromImportTask(it))
        }

        if (toInsert.isNotEmpty()) {
            taskDao.insertAll(toInsert)
        }
        legacyPrefs.edit().clear().apply()
    }

    private fun parseLegacyItems(json: String?): List<ImportTask> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val list = mutableListOf<ImportTask>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", TaskStatus.READY.name)
                val status = when (statusStr) {
                    "QUEUED" -> TaskStatus.READY
                    else -> runCatching { TaskStatus.valueOf(statusStr) }.getOrDefault(TaskStatus.READY)
                }

                val tagsList = mutableListOf<String>()
                obj.optJSONArray("assignedTags")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        tagsList.add(arr.getString(j))
                    }
                }
                val isNsfw = obj.optBoolean("isNsfw", false)

                list.add(
                    ImportTask(
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

    internal data class BatchProgressState(
        val batchTotal: Int = 0,
        val lastProcessingIds: Set<String> = emptySet()
    )

    companion object {
        @Volatile
        private var INSTANCE: ImportRepository? = null

        fun getInstance(context: Context = TidiloApplication.instance): ImportRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImportRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Pure function to calculate updated AiProcessingProgress and tracking state.
         * Shared between production repository and unit tests to ensure strict consistency.
         */
        internal fun computeBatchProgress(
            processingTasks: List<ImportTask>,
            state: BatchProgressState,
            isCancelPending: Boolean = false
        ): Pair<AiProcessingProgress, BatchProgressState> {
            if (processingTasks.isEmpty()) {
                return Pair(
                    AiProcessingProgress(
                        isProcessing = false,
                        completedCount = 0,
                        totalCount = 0,
                        currentTaskTitle = null,
                        isCancelPending = false
                    ),
                    BatchProgressState()
                )
            }

            val currentIds = processingTasks.map { it.id }.toSet()
            val newBatchTotal = if (state.lastProcessingIds.isEmpty()) {
                processingTasks.size
            } else {
                val newlyAdded = currentIds - state.lastProcessingIds
                state.batchTotal + newlyAdded.size
            }

            val total = maxOf(newBatchTotal, processingTasks.size)
            val remaining = processingTasks.size
            val completed = (total - remaining).coerceAtLeast(0)
            val currentTask = processingTasks.find { it.status == TaskStatus.PROCESSING_AI }
                ?: processingTasks.firstOrNull()

            val progress = AiProcessingProgress(
                isProcessing = true,
                completedCount = completed,
                totalCount = total,
                currentTaskTitle = currentTask?.title?.takeIf { it.isNotBlank() } ?: currentTask?.url,
                isCancelPending = isCancelPending
            )
            return Pair(progress, BatchProgressState(batchTotal = total, lastProcessingIds = currentIds))
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
                    className.contains("CancellationException", ignoreCase = true) ||
                    throwable is GenAiException ||
                    throwable is AiCoreExecutionException
        }

        fun extractAiCoreErrorCode(throwable: Throwable): Int? {
            var curr: Throwable? = throwable
            while (curr != null) {
                when (curr) {
                    is AiCoreExecutionException -> return curr.errorCode
                    is GenAiException -> return curr.errorCode
                }
                curr = curr.cause
            }
            val msg = throwable.message.orEmpty()
            return when {
                msg.contains("PER_APP_BATTERY_USE_QUOTA_EXCEEDED", ignoreCase = true) ||
                        msg.contains("battery use quota", ignoreCase = true) ||
                        msg.contains("quota exceeded", ignoreCase = true) ||
                        msg.contains("code 27", ignoreCase = true) -> GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
                msg.contains("BACKGROUND_USE_BLOCKED", ignoreCase = true) ||
                        msg.contains("background use blocked", ignoreCase = true) -> GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
                msg.contains("BUSY", ignoreCase = true) ||
                        msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        msg.contains("rate limit", ignoreCase = true) -> GenAiException.ErrorCode.BUSY
                else -> null
            }
        }

        fun isFallbackApplicable(mode: AiCoreFallbackMode, errorCode: Int?, throwable: Throwable): Boolean {
            return when (mode) {
                AiCoreFallbackMode.DISABLE -> false
                AiCoreFallbackMode.QUOTA_EXCEEDED_ONLY -> {
                    errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ||
                            throwable.message?.contains("27") == true ||
                            throwable.message?.contains("QUOTA", ignoreCase = true) == true
                }
                AiCoreFallbackMode.ALL -> {
                    errorCode != null || isAiCoreOrInterruptionException(throwable)
                }
            }
        }
    }
}
