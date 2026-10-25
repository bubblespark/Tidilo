package me.bubblespark.tidilo.data

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AiCoreFallbackMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AiAnalysisMode
import kotlinx.coroutines.flow.flowOf
import androidx.room.withTransaction
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkDao
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportNotificationDao
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.NotificationType
import me.bubblespark.tidilo.data.db.ImportTaskDao
import me.bubblespark.tidilo.data.db.ImportTaskEntity

import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.parser.ArticleAcquirer
import me.bubblespark.tidilo.parser.ParsedWebPage
import me.bubblespark.tidilo.parser.ArticlePreparation
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.json.JSONArray
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class ResolvedTarget(
    val importingId: String,
    val realUrl: String,
    val domain: String
)

private val ACTIVE_QUEUE_STATUSES = setOf("READY", "WAITING_AI", "PROCESSING_AI", "ERROR")

private class DirectBookmarkSaveFailure(val target: ImportTask, cause: Exception) : Exception(cause)

class ImportRepository internal constructor(
    context: Context? = null,
    private val taskDao: ImportTaskDao = context?.let { AppDatabase.getInstance(it).importTaskDao() }
        ?: throw IllegalArgumentException("taskDao must be provided if context is null"),
    private val bookmarkDao: BookmarkDao? = context?.let { AppDatabase.getInstance(it).bookmarkDao() },
    private val notificationDao: ImportNotificationDao? = context?.let { AppDatabase.getInstance(it).importNotificationDao() },
    private val database: AppDatabase? = context?.let { AppDatabase.getInstance(it) },
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val isAppInForeground: () -> Boolean = { TidiloApplication.instance.isAppInForeground },
    private val openModel: () -> GenerativeModel = { Generation.getClient() },
    private val analyzeTask: suspend (GenerativeModel, ImportTask) -> Unit = ::analyzeImportTask,
    private val fallbackMode: () -> AiCoreFallbackMode = { AppConfig.aiCoreFallbackMode.value },
    private val retryIntervalMs: Long = 3000L,
    private val articleAcquirer: suspend (String) -> ParsedWebPage = { ArticleAcquirer.acquire(it) },
    private val persistFallback: suspend (BookmarkItem) -> Unit = {
        BookmarkRepository.getInstance().addBookmarkAndAwait(it)
    },
    private val aiAnalysisMode: () -> AiAnalysisMode = { AppConfig.aiAnalysisMode.value }
) {

    private val appContext: Context? = context?.applicationContext
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

    val notifications: kotlinx.coroutines.flow.Flow<List<ImportNotificationEntity>> =
        notificationDao?.observeNotifications() ?: flowOf(emptyList())

    val unreadNotificationCount: kotlinx.coroutines.flow.Flow<Int> =
        notificationDao?.observeUnreadCount() ?: flowOf(0)

    fun markAllNotificationsAsRead() {
        repositoryScope.launch {
            notificationDao?.markAllAsRead()
        }
    }

    fun clearAllNotifications() {
        repositoryScope.launch {
            notificationDao?.deleteAll()
        }
    }

    suspend fun findBookmarkIdByUrl(url: String): String? {
        return bookmarkDao?.getBookmarkByUrl(url)?.id
    }

    private val importMutex = Mutex()
    internal val inFlightResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, ResolvedTarget>()
    private val pendingDuplicateNotificationJobs = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Job>>()
    internal var onEnteringTransaction: (suspend (url: String) -> Unit)? = null
    internal var onDuplicateIdentified: ((activeId: String) -> Unit)? = null
    internal var onEnteringFinalization: ((importingId: String) -> Unit)? = null

    private suspend fun <T> runInDbOrLock(url: String? = null, block: suspend () -> T): T {
        if (url != null) {
            onEnteringTransaction?.invoke(url)
        }
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            importMutex.withLock { block() }
        }
    }
    private var batchProgressState = BatchProgressState()
    @Volatile
    private var isAiCancelPending = false
    private val cancelledTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun dismissQuotaExceededDialog() {
        _quotaExceededDialogVisible.value = false
    }

    init {
        if (appContext != null) repositoryScope.launch {
            me.bubblespark.tidilo.data.rss.RssRepository.acquisitions.collect {
                if (isAppInForeground() && AppConfig.isAutoRunAiOnActive.value) aiAnalysisAll()
            }
        }
        context?.let {
            repositoryScope.launch {
                if (taskDao.getCount() == 0) {
                    migrateFromLegacyPrefs()
                }
                me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).resumeAcquisitions()
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
    private suspend fun checkDuplicate(url: String): DuplicateCheckResult {
        val existingBookmark = bookmarkDao?.getBookmarkByUrl(url)
        if (existingBookmark != null) {
            return if (existingBookmark.rssFeedId != null) {
                val isAiCompleted = !existingBookmark.isAiSkipped &&
                    (existingBookmark.category != null || existingBookmark.subcategory != null || existingBookmark.entities.isNotEmpty())
                if (isAiCompleted) {
                    DuplicateCheckResult.PromotedFromRssToLibrary(existingBookmark)
                } else {
                    val pendingTask = taskDao.getTaskByUrl(url)
                    if (pendingTask != null && pendingTask.status in ACTIVE_QUEUE_STATUSES) {
                        DuplicateCheckResult.DuplicateInQueue(pendingTask)
                    } else {
                        DuplicateCheckResult.RequeuedFromRssToAi(existingBookmark)
                    }
                }
            } else {
                DuplicateCheckResult.DuplicateInLibrary(existingBookmark)
            }
        }

        val existingTask = taskDao.getTaskByUrl(url)
        if (existingTask != null && existingTask.status in ACTIVE_QUEUE_STATUSES) {
            return if (existingTask.rssFeedId != null && existingTask.status == "READY") {
                DuplicateCheckResult.MovedFromRssToReady(existingTask)
            } else {
                DuplicateCheckResult.DuplicateInQueue(existingTask)
            }
        }

        return DuplicateCheckResult.NoDuplicate
    }

    private suspend fun handleDuplicateOrConflict(
        url: String,
        result: DuplicateCheckResult,
        importingId: String?,
        onImported: ((Result<ImportOutcome>) -> Unit)?,
        preparedImport: ImportTask? = null
    ): Boolean {
        val domain = DomainIconManager.extractDomain(url) ?: url
        when (result) {
            is DuplicateCheckResult.NoDuplicate -> return false

            is DuplicateCheckResult.PromotedFromRssToLibrary -> {
                bookmarkDao?.clearRssFeedId(result.bookmark.id)
                if (importingId != null) {
                    _importingTasks.update { list -> list.filter { it.id != importingId } }
                }
                notificationDao?.insert(
                    ImportNotificationEntity(
                        type = NotificationType.INFO.name,
                        domain = domain,
                        url = url,
                        message = "Promoted from RSS to Library",
                        existingBookmarkId = result.bookmark.id
                    )
                )
                onImported?.invoke(Result.failure(DuplicateUrlException("Promoted from RSS to Library")))
                return true
            }

            is DuplicateCheckResult.RequeuedFromRssToAi -> {
                if (aiAnalysisMode() == AiAnalysisMode.DISABLE) {
                    bookmarkDao?.clearRssFeedId(result.bookmark.id)
                    importingId?.let { id -> _importingTasks.update { list -> list.filter { it.id != id } } }
                    notificationDao?.insert(ImportNotificationEntity(type = NotificationType.INFO.name,
                        domain = domain, url = url, message = "Promoted from RSS to Library", existingBookmarkId = result.bookmark.id))
                    onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(result.bookmark.id, url)))
                    return true
                }
                val item = ImportTask(
                    url = url,
                    title = result.bookmark.title,
                    status = TaskStatus.READY,
                    statusText = "Ready for AI Analysis",
                    isNsfw = result.bookmark.isNsfw,
                    description = result.bookmark.description,
                    ogImageUrl = result.bookmark.ogImageUrl,
                    articlePrepared = false,
                    rssFeedId = null
                )
                taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(item))
                if (importingId != null) {
                    _importingTasks.update { list -> list.filter { it.id != importingId } }
                }
                notificationDao?.insert(
                    ImportNotificationEntity(
                        type = NotificationType.INFO.name,
                        domain = domain,
                        url = url,
                        message = "Promoted from RSS to Library",
                        existingBookmarkId = result.bookmark.id
                    )
                )
                onImported?.invoke(Result.success(ImportOutcome.QueuedForAi(item)))
                return true
            }

            is DuplicateCheckResult.MovedFromRssToReady -> {
                if (aiAnalysisMode() == AiAnalysisMode.DISABLE) {
                    if (!result.task.articlePrepared && preparedImport == null) return false
                    val target = preparedImport?.copy(id = result.task.id, createdAt = result.task.createdAt)
                        ?: result.task.copy(rssFeedId = null).toImportTask()
                    val bookmark = skippedBookmark(target)
                    persistDirectBookmark(target)
                    taskDao.deleteById(target.id)
                    notificationDao?.insert(ImportNotificationEntity(type = NotificationType.INFO.name, domain = domain,
                        url = url, message = "Promoted from RSS to Library", existingBookmarkId = bookmark.id))
                    cacheDirectOgImage(target)
                    importingId?.let { id -> _importingTasks.update { list -> list.filter { it.id != id } } }
                    onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(bookmark.id, url)))
                    return true
                }
                taskDao.clearRssFeedId(result.task.id)
                if (importingId != null) {
                    _importingTasks.update { list -> list.filter { it.id != importingId } }
                }
                val movedTask = result.task.copy(rssFeedId = null).toImportTask()
                notificationDao?.insert(
                    ImportNotificationEntity(
                        type = NotificationType.INFO.name,
                        domain = domain,
                        url = url,
                        message = "Moved from RSS to Ready",
                        existingBookmarkId = null
                    )
                )
                onImported?.invoke(Result.success(ImportOutcome.QueuedForAi(movedTask)))
                return true
            }

            is DuplicateCheckResult.DuplicateInLibrary -> {
                if (importingId != null) {
                    _importingTasks.update { list -> list.filter { it.id != importingId } }
                }
                notificationDao?.insert(
                    ImportNotificationEntity(
                        type = NotificationType.WARNING.name,
                        domain = domain,
                        url = url,
                        message = "Duplicate URL (Already in Library)",
                        existingBookmarkId = result.bookmark.id
                    )
                )
                onImported?.invoke(Result.failure(DuplicateUrlException("Duplicate URL (Already in Library)")))
                return true
            }

            is DuplicateCheckResult.DuplicateInQueue -> {
                if (importingId != null) {
                    _importingTasks.update { list -> list.filter { it.id != importingId } }
                }
                notificationDao?.insert(
                    ImportNotificationEntity(
                        type = NotificationType.WARNING.name,
                        domain = domain,
                        url = url,
                        message = "Duplicate URL (Already in Queue)",
                        existingBookmarkId = null
                    )
                )
                onImported?.invoke(Result.failure(DuplicateUrlException("Duplicate URL (Already in Queue)")))
                return true
            }
        }
    }

    fun importUrl(
        rawUrl: String,
        title: String? = null,
        onImported: ((Result<ImportOutcome>) -> Unit)? = null
    ): Result<Int> {
        val trimmed = rawUrl.trim()
        val normalized = WebPageParser.normalizeUrl(trimmed)
        if (normalized == null || !isValidHttpUrl(normalized)) {
            val ex = IllegalArgumentException("Invalid HTTP/HTTPS URL")
            onImported?.invoke(Result.failure(ex))
            return Result.failure(ex)
        }

        val importing = ImportingTask(url = normalized, title = title)
        var notificationDeferred: CompletableDeferred<Unit>? = null
        var initialUrl: String = normalized
        var initialDomain: String = DomainIconManager.extractDomain(normalized).orEmpty()
        var activeId: String? = null

        val canProceed = synchronized(_importingTasks) {
            val existing = _importingTasks.value.firstOrNull { it.url == normalized }
            if (existing != null) {
                activeId = existing.id
                onDuplicateIdentified?.invoke(existing.id)

                val initialResolved = inFlightResolvedUrls[normalized]?.takeIf { it.importingId == existing.id }
                if (initialResolved != null) {
                    initialUrl = initialResolved.realUrl
                    initialDomain = initialResolved.domain
                }

                val deferred = CompletableDeferred<Unit>()
                notificationDeferred = deferred
                pendingDuplicateNotificationJobs.computeIfAbsent(existing.id) { java.util.concurrent.CopyOnWriteArrayList() }.add(deferred)
                deferred.invokeOnCompletion {
                    pendingDuplicateNotificationJobs[existing.id]?.remove(deferred)
                }
                false
            } else {
                _importingTasks.update { it + importing }
                true
            }
        }

        if (!canProceed) {
            val ex = DuplicateUrlException("Duplicate URL (Already in Queue)")
            val targetDeferred = notificationDeferred
            val targetActiveId = activeId
            val targetUrl: String = initialUrl
            val targetDomain: String = initialDomain
            try {
                repositoryScope.launch {
                    try {
                        notificationDao?.insert(
                            ImportNotificationEntity(
                                type = NotificationType.WARNING.name,
                                domain = targetDomain,
                                url = targetUrl,
                                message = "Duplicate URL (Already in Queue)",
                                existingBookmarkId = null
                            )
                        )
                        // Race safety: If resolution happened while insert was suspended/pending,
                        // re-apply update to guarantee resolved URL is preserved
                        val currentResolved = inFlightResolvedUrls[normalized]?.takeIf { it.importingId == targetActiveId }
                        if (currentResolved != null && currentResolved.realUrl != targetUrl) {
                            notificationDao?.updateResolvedUrl(
                                submittedUrl = normalized,
                                resolvedUrl = currentResolved.realUrl,
                                domain = currentResolved.domain
                            )
                        }
                    } finally {
                        targetDeferred?.complete(Unit)
                    }
                }
            } catch (t: Throwable) {
                targetDeferred?.complete(Unit)
                throw t
            }
            onImported?.invoke(Result.failure(ex))
            return Result.failure(ex)
        }

        val estimatedCount = _readyTasks.value.size + _importingTasks.value.size

        repositoryScope.launch {
            var duplicateOutcome: Result<ImportOutcome>? = null
            val recordDuplicateOutcome: (Result<ImportOutcome>) -> Unit = { duplicateOutcome = it }
            fun reportCommittedDuplicate() {
                duplicateOutcome?.let { onImported?.invoke(it) }
                duplicateOutcome = null
            }
            try {
                // Pre-Fetch Check (Primary Gate) - atomic duplicate check & mutation in database transaction
                if (appContext != null) TidiloApplication.instance.awaitAiAnalysisInitialization()
                val isEarlyHandled = runInDbOrLock(normalized) {
                    val preCheck = checkDuplicate(normalized)
                    handleDuplicateOrConflict(normalized, preCheck, importing.id, recordDuplicateOutcome)
                }
                reportCommittedDuplicate()
                if (isEarlyHandled) {
                    return@launch
                }

                // 1. Fetch & parse HTML, extract metadata, OGP, and Readability body snippet
                val parsed = articleAcquirer(normalized)
                val prepared = ArticlePreparation.prepare(parsed)

                val realUrl = WebPageParser.normalizeUrl(prepared.url) ?: prepared.url
                // Post-Redirect Check (handles redirects) - atomic duplicate check & mutation in database transaction
                if (realUrl != normalized) {
                    val isRedirectHandled = runInDbOrLock(realUrl) {
                        val redirectDomain = DomainIconManager.extractDomain(realUrl).orEmpty()
                        inFlightResolvedUrls[normalized] = ResolvedTarget(
                            importingId = importing.id,
                            realUrl = realUrl,
                            domain = redirectDomain
                        )
                        notificationDao?.updateResolvedUrl(
                            submittedUrl = normalized,
                            resolvedUrl = realUrl,
                            domain = redirectDomain
                        )
                        val postCheck = checkDuplicate(realUrl)
                        handleDuplicateOrConflict(realUrl, postCheck, importing.id, recordDuplicateOutcome)
                    }
                    reportCommittedDuplicate()
                    if (isRedirectHandled) {
                        return@launch
                    }
                }

                val hasCustomTitle = !title.isNullOrBlank() && !isRawUrlOrHost(title, normalized)
                val realTitle = if (hasCustomTitle) title.trim() else prepared.title
                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = prepared.siteName
                DomainPolicyManager.registerDomain(domain, false, siteName)
                val isNsfw = parsed.isNsfwSignal || DomainPolicyManager.isNsfwDomain(domain)

                // 2. Fetch and save domain icon (resolving watermark icon)
                if (appContext != null && domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
                    DomainIconManager.fetchAndSaveDomainIcon(
                        context = appContext,
                        url = realUrl,
                        appleTouchIconUrl = parsed.appleTouchIconUrl,
                        faviconUrl = parsed.faviconUrl,
                        knownCandidates = parsed.iconCandidates
                    )
                }

                val description = prepared.description
                val bodySnippet = prepared.bodySnippet
                val ogImageUrl = prepared.ogImageUrl

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
                    ogImageUrl = ogImageUrl,
                    articlePrepared = true
                )

                var outcome: ImportOutcome = ImportOutcome.QueuedForAi(newItem)
                val isInserted = runInDbOrLock(realUrl) {
                    val finalRecheck = checkDuplicate(realUrl)
                    if (finalRecheck !is DuplicateCheckResult.NoDuplicate) {
                        handleDuplicateOrConflict(realUrl, finalRecheck, importing.id, recordDuplicateOutcome, newItem)
                        false
                    } else {
                        if (aiAnalysisMode() == AiAnalysisMode.DISABLE) {
                            val bookmark = skippedBookmark(newItem)
                            persistDirectBookmark(newItem)
                            outcome = ImportOutcome.BookmarkSaved(bookmark.id, bookmark.url)
                        } else {
                            taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(newItem))
                        }
                        true
                    }
                }
                reportCommittedDuplicate()
                if (!isInserted) {
                    return@launch
                }
                if (outcome is ImportOutcome.BookmarkSaved) cacheDirectOgImage(newItem)
                onImported?.invoke(Result.success(outcome))
            } catch (e: DuplicateUrlException) {
                // Handled in handleDuplicateOrConflict; ignore
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectBookmarkSaveFailure) {
                // A failed nested Room transaction invalidates its outer transaction. Recover only after rollback.
                runInDbOrLock(e.target.url) { retainDirectSaveError(e.target, e.cause ?: e) }
                onImported?.invoke(Result.failure(e.cause ?: e))
            } catch (e: Exception) {
                duplicateOutcome = null
                // Fallback: If network/parsing throws, create fallback task with basic details
                val domain = DomainIconManager.extractDomain(normalized)
                val isDomainNsfw = DomainPolicyManager.isNsfwDomain(domain)
                val fallbackItem = ImportTask(
                    url = normalized,
                    title = title?.trim()?.takeIf { it.isNotBlank() } ?: extractHostOrTitle(normalized),
                    status = TaskStatus.ERROR,
                    statusText = "Article acquisition failed",
                    errorMessage = e.message,
                    isNsfw = isDomainNsfw
                )
                val isFallbackInserted = runInDbOrLock(normalized) {
                    val fallbackCheck = checkDuplicate(normalized)
                    if (fallbackCheck is DuplicateCheckResult.MovedFromRssToReady && aiAnalysisMode() == AiAnalysisMode.DISABLE) {
                        taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(fallbackItem.copy(id = fallbackCheck.task.id)))
                        true
                    } else if (fallbackCheck !is DuplicateCheckResult.NoDuplicate) {
                        handleDuplicateOrConflict(normalized, fallbackCheck, importing.id, recordDuplicateOutcome)
                        false
                    } else {
                        taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(fallbackItem))
                        true
                    }
                }
                reportCommittedDuplicate()
                if (!isFallbackInserted) {
                    return@launch
                }
                onImported?.invoke(Result.failure(e))
            } finally {
                onEnteringFinalization?.invoke(importing.id)
                val pendingJobs = synchronized(_importingTasks) {
                    _importingTasks.update { list -> list.filter { it.id != importing.id } }
                    pendingDuplicateNotificationJobs.remove(importing.id).orEmpty().toList()
                }
                withContext(NonCancellable) {
                    pendingJobs.joinAll()
                    val current = inFlightResolvedUrls[normalized]
                    if (current != null && current.importingId == importing.id) {
                        inFlightResolvedUrls.remove(normalized, current)
                    }
                }
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
            converted.forEach { taskDao.updateStatus(it.id, it.status.name, it.statusText, it.errorMessage) }

            startProcessingLoop()
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
            taskDao.updateStatus(promoted.id, promoted.status.name, promoted.statusText, promoted.errorMessage)
            startProcessingLoop()
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
                restored.forEach { taskDao.updateStatus(it.id, it.status.name, it.statusText, it.errorMessage) }
                restored.forEach {
                    retryNotBefore.remove(it.id)
                    foregroundRetryCounts.remove(it.id)
                }
            }
            reconcileRssPending()
            startProcessingLoop()
        }
    }

    /**
     * Moves an Error task back to Processing and retries AI analysis.
     */
    fun retryErrorTask(taskId: String) {
        repositoryScope.launch {
            val targetEntity = taskDao.getTaskById(taskId) ?: return@launch
            val target = targetEntity.toImportTask()
            if (target.rssFeedId != null && (!target.articlePrepared || target.intendedMode == "DIRECT")) {
                appContext?.let { me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).retryAcquisition(taskId) }
                return@launch
            }
            if (target.rssFeedId == null && target.errorMessage == me.bubblespark.tidilo.parser.ArticleContentUnavailable.MESSAGE) {
                // Explicit retry may recover text that was unavailable when the page was first acquired.
                taskDao.update(targetEntity.copy(articlePrepared = false))
            }
            val retried = target.copy(
                status = TaskStatus.WAITING_AI,
                statusText = "Waiting for AICore slot...",
                errorMessage = null
            )
            taskDao.updateStatus(retried.id, retried.status.name, retried.statusText, retried.errorMessage)
            startProcessingLoop()
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
            restoreTask(task)
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
            restoreTask(task)
        }
    }

    private suspend fun restoreTask(task: ImportTask) {
        if (task.rssFeedId != null && appContext != null) {
            me.bubblespark.tidilo.data.rss.RssIngestionStore(AppDatabase.getInstance(appContext))
                .restoreTask(ImportTaskEntity.fromImportTask(task))
        } else taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(task))
    }

    private suspend fun reconcileRssPending() {
        appContext?.let { me.bubblespark.tidilo.data.rss.RssIngestionStore(AppDatabase.getInstance(it)).reconcilePending() }
    }

    private val foregroundRetryCounts = ConcurrentHashMap<String, Int>()
    private val retryNotBefore = ConcurrentHashMap<String, Long>()
    private val MAX_FOREGROUND_RETRIES = 3

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
        startProcessingLoop()
    }

    private val processingWorker = AiProcessingWorker(
        scope = repositoryScope,
        mutex = executionMutex,
        nextTask = { nextProcessingTask() },
        openSession = openModel,
        closeSession = { model: GenerativeModel -> model.close() },
        processTask = { model, task -> processAiTask(model, task) },
        handleFailure = { task, exception -> handleExecutionFailure(task, exception) },
        reportSessionFailure = { exception -> Log.e("ImportRepository", "AI processing session failed", exception) },
        processWithoutSession = { task ->
            if (aiAnalysisMode() == AiAnalysisMode.DISABLE) {
                processDirectTask(task)
                true
            } else false
        }
    )

    private fun startProcessingLoop() {
        processingWorker.wakeUp()
    }

    /**
     * Executes the Gemini Nano analysis and summarization pipeline for the specified task.
     */
    fun executeTaskAiAnalysis(taskId: String) {
        repositoryScope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            if (task.status == TaskStatus.WAITING_AI.name || task.status == TaskStatus.PROCESSING_AI.name) {
                startProcessingLoop()
            }
        }
    }

    private suspend fun nextProcessingTask(): ImportTask? {
        if (appContext != null) TidiloApplication.instance.awaitAiAnalysisInitialization()
        taskDao.rejectUnavailableRss()
        reconcileRssPending()
        while (true) {
            val candidates = taskDao.getTasksByStatuses(
                listOf(TaskStatus.WAITING_AI.name, TaskStatus.PROCESSING_AI.name)
            )
            if (candidates.isEmpty()) return null

            // If cancellation is requested and task is in the cancelled queue, restore to READY and skip.
            val (cancelled, runnable) = candidates.partition { entity ->
                val shouldCancel = isAiCancelPending &&
                    (cancelledTaskIds.contains(entity.id) || cancelledTaskIds.isEmpty())
                shouldCancel && entity.status == TaskStatus.WAITING_AI.name
            }
            for (entity in cancelled) {
                taskDao.updateStatus(entity.id, TaskStatus.READY.name, "")
                retryNotBefore.remove(entity.id)
                foregroundRetryCounts.remove(entity.id)
                cancelledTaskIds.remove(entity.id)
            }
            if (runnable.isEmpty()) return null

            // If app is currently in background, do not start inference; wait for foreground.
            if (!isAppInForeground()) {
                for (entity in runnable) {
                    updateProcessingTaskStatus(entity.id, TaskStatus.WAITING_AI, "Paused • Retrying when app is active")
                }
                return null
            }

            val now = System.nanoTime()
            val prioritized = runnable.filter { it.rssFeedId == null }.ifEmpty { runnable }
            val next = prioritized.firstOrNull { (retryNotBefore[it.id] ?: now) <= now }
            if (next != null) return next.toImportTask()
            val waitNanos = prioritized.minOf { retryNotBefore[it.id] ?: now } - now
            processingWorker.awaitWork((waitNanos / 1_000_000L).coerceAtLeast(1L))
        }
    }

    private suspend fun processAiTask(model: GenerativeModel, selected: ImportTask) {
        val taskId = selected.id
        // Recheck Room after client initialization; cancellation or deletion may have changed the task.
        val target = taskDao.getTaskById(taskId)?.toImportTask() ?: return
        if (target.status != TaskStatus.WAITING_AI && target.status != TaskStatus.PROCESSING_AI) return
        val shouldCancel = isAiCancelPending &&
            (cancelledTaskIds.contains(taskId) || cancelledTaskIds.isEmpty())
        if (shouldCancel && target.status == TaskStatus.WAITING_AI) {
            updateProcessingTaskStatus(taskId, TaskStatus.READY, "")
            retryNotBefore.remove(taskId)
            foregroundRetryCounts.remove(taskId)
            return
        }
        if (!isAppInForeground()) {
            updateProcessingTaskStatus(taskId, TaskStatus.WAITING_AI, "Paused • Retrying when app is active")
            return
        }

        if (target.rssFeedId != null && !target.articlePrepared) {
            if (appContext == null) taskDao.updateStatus(taskId, "ERROR", "Article preparation unavailable")
            else me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).retryAcquisition(taskId, true)
            return
        }
        // 1. Update status to PROCESSING_AI
        if (taskDao.claimForAi(taskId, "Gemini Nano analyzing content...") != 1) {
            taskDao.rejectUnavailableRss()
            return
        }

        // 1.5 Cache og:image into cacheDir during Processing asynchronously (Issue #57, #71)
        val targetContext = appContext
        val candidateOgImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() }
        if (targetContext != null && !candidateOgImageUrl.isNullOrBlank()) {
            repositoryScope.launch {
                try {
                    OgImageManager.processAndSaveOgImage(
                        context = targetContext,
                        pageUrl = target.url,
                        ogImageUrl = candidateOgImageUrl,
                        network = if (target.rssFeedId != null) me.bubblespark.tidilo.data.rss.RssRepository.getInstance(targetContext).articleNetwork() else null

                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Non-critical background image cache failure
                }
            }
        }

        // 2. Gemini Nano inference using precomputed metadata -> Save to Library
        analyzeTask(model, target)

        // 3. Success: Remove from database and register in library
        foregroundRetryCounts.remove(taskId)
        retryNotBefore.remove(taskId)
        taskDao.deleteById(taskId)
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
    private suspend fun handleExecutionFailure(target: ImportTask, throwable: Throwable) {
        if (throwable is me.bubblespark.tidilo.data.rss.RssImportSkipped) {
            taskDao.deleteById(target.id)
            return
        }
        val acquisitionFailure = throwable is me.bubblespark.tidilo.parser.ArticleAcquisitionFailure
        if (throwable is me.bubblespark.tidilo.parser.ArticleContentUnavailable || acquisitionFailure) {
            retryNotBefore.remove(target.id)
            foregroundRetryCounts.remove(target.id)
            taskDao.updateStatus(target.id, TaskStatus.ERROR.name,
                if (acquisitionFailure) "Article acquisition failed" else "Article content unavailable", throwable.message)
            return
        }
        val errorMessage = throwable.message.orEmpty()
        val isForeground = isAppInForeground()
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
                    retryNotBefore[target.id] = System.nanoTime() + retryIntervalMs * 1_000_000L
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
                    retryNotBefore[target.id] = System.nanoTime() + retryIntervalMs * 1_000_000L
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
        return Companion.isFallbackApplicable(fallbackMode(), errorCode, throwable)
    }

    private fun skippedBookmark(target: ImportTask): BookmarkItem = BookmarkItem(
            id = target.id,
            title = target.title,
            url = target.url,
            description = target.description,
            tag = Tag.createEmpty(),
            isNsfw = target.isNsfw,
            isAiSkipped = true,
            ogImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() },
            createdAt = if (target.rssFeedId != null) target.createdAt else System.currentTimeMillis(),
            rssFeedId = target.rssFeedId, rssPublishedAt = target.rssPublishedAt
        )

    private fun cacheDirectOgImage(target: ImportTask) {
        val context = appContext ?: return
        val ogImage = target.ogImageUrl?.takeIf { it.isNotBlank() } ?: return
        repositoryScope.launch {
            try {
                OgImageManager.processAndSaveOgImage(context, target.url, ogImage,
                    network = if (target.rssFeedId != null) me.bubblespark.tidilo.data.rss.RssRepository.getInstance(context).articleNetwork() else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional cache recovery remains independent of the durable save. */ }
        }
    }

    private suspend fun persistDirectBookmark(target: ImportTask) {
        try {
            persistFallback(skippedBookmark(target))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw DirectBookmarkSaveFailure(target, e) }
    }

    private suspend fun retainDirectSaveError(target: ImportTask, error: Throwable) {
        taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(target.copy(status = TaskStatus.ERROR,
            statusText = "Save failed", errorMessage = "Bookmark persistence failed: ${error.message.orEmpty()}")))
    }

    private suspend fun processDirectTask(selected: ImportTask) {
        var target = taskDao.getTaskById(selected.id)?.toImportTask() ?: return
        if (target.status !in listOf(TaskStatus.WAITING_AI, TaskStatus.PROCESSING_AI)) return
        if (isAiCancelPending && target.status == TaskStatus.WAITING_AI &&
            (cancelledTaskIds.contains(target.id) || cancelledTaskIds.isEmpty())) {
            taskDao.updateStatus(target.id, "READY", "")
            return
        }
        if (target.rssFeedId != null && !target.articlePrepared) {
            appContext?.let { me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).retryAcquisition(target.id) }
            return
        }
        if (taskDao.claimForAi(target.id, "Saving bookmark...") != 1) {
            taskDao.rejectUnavailableRss()
            return
        }
        try {
            if (!target.articlePrepared) {
                val parsed = articleAcquirer(target.url)
                val article = ArticlePreparation.prepare(parsed)
                target = target.copy(url = WebPageParser.normalizeUrl(article.url) ?: article.url,
                    title = if (isRawUrlOrHost(target.title, target.url)) article.title else target.title,
                    siteName = article.siteName, description = article.description, bodySnippet = article.bodySnippet,
                    ogImageUrl = article.ogImageUrl, isNsfw = article.isNsfw || DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(article.url)),
                    articlePrepared = true, status = TaskStatus.PROCESSING_AI, errorMessage = null)
                val current = taskDao.getTaskById(target.id) ?: return
                if (current.status != "PROCESSING_AI") return
                taskDao.update(ImportTaskEntity.fromImportTask(target))
                val context = appContext
                val domain = DomainIconManager.extractDomain(target.url)
                if (context != null && domain != null) repositoryScope.launch {
                    try {
                        DomainIconManager.fetchAndSaveDomainIcon(context, target.url, parsed.appleTouchIconUrl,
                            parsed.faviconUrl, parsed.iconCandidates)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Optional enrichment. */ }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            taskDao.updateStatus(target.id, "ERROR", "Article acquisition failed", e.message)
            return
        }
        try {
            runInDbOrLock(target.url) {
                if (taskDao.getTaskById(target.id)?.status != "PROCESSING_AI") return@runInDbOrLock
                val existing = bookmarkDao?.getBookmarkByUrl(target.url)
                if (existing != null) {
                    if (target.rssFeedId == null && existing.rssFeedId != null) bookmarkDao?.clearRssFeedId(existing.id)
                } else {
                    persistDirectBookmark(target)
                }
                taskDao.deleteById(target.id)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            retainDirectSaveError(target, e.cause ?: e)
            return
        }
        if (taskDao.getTaskById(target.id) == null) cacheDirectOgImage(target)
    }

    private suspend fun saveBookmarkFallback(target: ImportTask) {
        foregroundRetryCounts.remove(target.id)
        retryNotBefore.remove(target.id)
        val fallbackBookmark = skippedBookmark(target)
        try {
            persistFallback(fallbackBookmark)
        } catch (e: Exception) {
            if (e is me.bubblespark.tidilo.data.rss.RssImportSkipped) {
                taskDao.deleteById(target.id)
                return
            }
            currentCoroutineContext().ensureActive()
            taskDao.updateStatus(
                target.id, TaskStatus.ERROR.name, "Save failed",
                "Bookmark persistence failed: ${e.message.orEmpty()}"
            )
            return
        }
        taskDao.deleteById(target.id)
        if (appContext != null) {
            // Domain metadata and image caching are auxiliary to the durable bookmark save.
            repositoryScope.launch {
                val domain = DomainIconManager.extractDomain(target.url)
                DomainPolicyManager.registerDomain(domain, false, target.siteName)
                if (domain != null && !DomainIconManager.hasIcon(appContext, domain)) {
                    DomainIconManager.fetchAndSaveDomainIcon(appContext, target.url, network = if (target.rssFeedId != null) me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).articleNetwork() else null)
                }
            }
        }
    }

    /**
     * Moves all tasks currently in Processing back to the Ready group due to battery quota limit.
     */
    private suspend fun moveAllProcessingToReady() {
        _quotaExceededDialogVisible.value = true
        foregroundRetryCounts.clear()
        retryNotBefore.clear()
        val currentProcessing = taskDao.getTasksByStatuses(
            listOf(TaskStatus.PROCESSING_AI.name, TaskStatus.WAITING_AI.name)
        ).map { it.toImportTask() }
        val converted = currentProcessing.map { item ->
            item.copy(
                status = TaskStatus.READY,
                statusText = "Ready • Quota exceeded"
            )
        }
        converted.forEach { taskDao.updateStatus(it.id, it.status.name, it.statusText, it.errorMessage) }
        reconcileRssPending()
    }

    /**
     * Removes the failed task and all other tasks in Processing, moving them to the Error group.
     */
    private suspend fun moveAllProcessingToError(primaryFailedItem: ImportTask, errorMessage: String) {
        val fallbackMsg = errorMessage.ifBlank { "Unknown failure" }
        foregroundRetryCounts.clear()
        retryNotBefore.clear()
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
        errorItemsToAdd.forEach { taskDao.updateStatus(it.id, it.status.name, it.statusText, it.errorMessage) }
    }

    private suspend fun updateProcessingTaskStatus(taskId: String, status: TaskStatus, statusText: String) {
        taskDao.updateStatus(taskId, status.name, statusText)
    }

    private fun fetchAndPreparseTaskAsync(taskId: String, url: String) {
        repositoryScope.launch {
            try {
                val task = taskDao.getTaskById(taskId) ?: return@launch
                if (task.rssFeedId != null) {
                    appContext?.let { me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).retryAcquisition(task.id) }
                    return@launch
                }
                val parsed = ArticleAcquirer.acquire(url)
                val prepared = ArticlePreparation.prepare(parsed)
                val realTitle = prepared.title
                val realUrl = prepared.url
                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = prepared.siteName

                if (domain != null) {
                    DomainPolicyManager.registerDomain(domain, false, siteName)
                    if (appContext != null && !DomainIconManager.hasIcon(appContext, domain)) {
                        DomainIconManager.fetchAndSaveDomainIcon(
                            context = appContext,
                            url = realUrl,
                            appleTouchIconUrl = parsed.appleTouchIconUrl,
                            faviconUrl = parsed.faviconUrl,
                            knownCandidates = parsed.iconCandidates
                        )
                    }
                }

                val description = prepared.description
                val bodySnippet = prepared.bodySnippet

                val isNsfw = parsed.isNsfwSignal || (domain != null && DomainPolicyManager.isNsfwDomain(domain))

                val existing = taskDao.getTaskById(taskId)
                if (existing != null) {
                    val updated = existing.copy(
                        url = realUrl,
                        title = if (isRawUrlOrHost(existing.title, existing.url)) realTitle else existing.title,
                        siteName = siteName,
                        description = description,
                        bodySnippet = bodySnippet,
                        ogImageUrl = prepared.ogImageUrl,
                        ogImageKey = me.bubblespark.tidilo.data.db.bookmarkOgImageKey(realUrl, prepared.ogImageUrl),
                        articlePrepared = true,
                        isNsfw = isNsfw || existing.isNsfw
                    )
                    taskDao.update(updated)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
            }
        }
    }

    private fun fetchTitlesForReadyTasks() {
        repositoryScope.launch {
            val toFetch = _readyTasks.value.filter(::needsArticlePreparse)
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
        internal fun needsArticlePreparse(task: ImportTask): Boolean =
            if (task.rssFeedId != null) !task.articlePrepared else !task.articlePrepared && (isRawUrlOrHost(task.title, task.url) || task.bodySnippet.isBlank())
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

/** Runs one import task using the session-owned model. */
private suspend fun analyzeImportTask(model: GenerativeModel, target: ImportTask) {
    GeminiTaggingService.processAndSaveBookmark(
        model = model,
        url = target.url,
        language = AppConfig.currentLanguage.value,
        preferredTitle = target.title,
        bodySnippet = target.bodySnippet.takeIf { it.isNotBlank() },
        description = target.description.takeIf { it.isNotBlank() },
        siteName = target.siteName.takeIf { it.isNotBlank() },
        isNsfw = target.isNsfw,
        ogImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() },
        rssFeedId = target.rssFeedId,
        rssPublishedAt = target.rssPublishedAt,
        rssFirstSeenAt = if (target.rssFeedId != null) target.createdAt else null,
        articlePrepared = target.articlePrepared
    ).getOrThrow()
}


class DuplicateUrlException(message: String) : Exception(message)

sealed class DuplicateCheckResult {
    data object NoDuplicate : DuplicateCheckResult()
    data class PromotedFromRssToLibrary(val bookmark: BookmarkEntity) : DuplicateCheckResult()
    data class RequeuedFromRssToAi(val bookmark: BookmarkEntity) : DuplicateCheckResult()
    data class MovedFromRssToReady(val task: ImportTaskEntity) : DuplicateCheckResult()
    data class DuplicateInLibrary(val bookmark: BookmarkEntity) : DuplicateCheckResult()
    data class DuplicateInQueue(val task: ImportTaskEntity) : DuplicateCheckResult()
}
