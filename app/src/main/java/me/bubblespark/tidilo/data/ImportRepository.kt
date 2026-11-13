package me.bubblespark.tidilo.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.parser.ArticleAcquirer
import me.bubblespark.tidilo.parser.ParsedWebPage
import me.bubblespark.tidilo.parser.classifyStandardMetadata
import me.bubblespark.tidilo.parser.ArticlePreparation
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.json.JSONArray
import java.net.URI
import java.util.UUID

internal data class ResolvedTarget(
    val importingId: String,
    val realUrl: String,
    val domain: String
)

private val ACTIVE_QUEUE_STATUSES = setOf("QUEUED", "PROCESSING", "ERROR")

internal interface ImportDomainPolicyAdapter {
    fun isNsfwDomain(domain: String?): Boolean
    fun registerDomain(domain: String?, isNsfw: Boolean = false, displayName: String = "")

    companion object {
        val NO_OP: ImportDomainPolicyAdapter = object : ImportDomainPolicyAdapter {
            override fun isNsfwDomain(domain: String?): Boolean = false
            override fun registerDomain(domain: String?, isNsfw: Boolean, displayName: String) = Unit
        }
    }
}

internal interface ImportMediaCacheAdapter {
    fun hasDomainIcon(domain: String): Boolean
    suspend fun fetchDomainIcon(
        url: String,
        appleTouchIconUrl: String?,
        faviconUrl: String?,
        knownCandidates: List<DomainIconCandidate>?
    )
    suspend fun cacheOgImage(url: String, ogImageUrl: String, rssFeedId: String?)

    companion object {
        val NO_OP: ImportMediaCacheAdapter = object : ImportMediaCacheAdapter {
            override fun hasDomainIcon(domain: String): Boolean = false
            override suspend fun fetchDomainIcon(
                url: String,
                appleTouchIconUrl: String?,
                faviconUrl: String?,
                knownCandidates: List<DomainIconCandidate>?
            ) = Unit
            override suspend fun cacheOgImage(url: String, ogImageUrl: String, rssFeedId: String?) = Unit
        }
    }
}

internal interface ImportRssAdapter {
    suspend fun resumeAcquisitions()
    suspend fun retryAcquisition(taskId: String)
    suspend fun restoreTask(entity: ImportTaskEntity)
    suspend fun validateRssWrite(url: String, bookmarkId: String, feedId: String, isNsfw: Boolean)
    suspend fun recordRecoveredDirectSave(taskId: String)
}

internal interface ImportNotificationAdapter {
    fun importError(error: Throwable)
    suspend fun publishCompletedRssRuns()

    companion object {
        val NO_OP: ImportNotificationAdapter = object : ImportNotificationAdapter {
            override fun importError(error: Throwable) = Unit
            override suspend fun publishCompletedRssRuns() = Unit
        }
    }
}

private sealed class DuplicateResolution {
    data object NotHandled : DuplicateResolution()
    data class Handled(val postCommitTask: ImportTask? = null) : DuplicateResolution()
}

private sealed class FinalSaveResult {
    data object NotHandled : FinalSaveResult()
    data class HandledDuplicate(val postCommitTask: ImportTask?) : FinalSaveResult()
    data class Inserted(val savedTask: ImportTask) : FinalSaveResult()
}

private fun defaultCandidateExtractor(): EntityCandidateExtractor = EntityCandidateExtractor.default()

private fun defaultTransactionRunner(database: AppDatabase?): TransactionRunner {
    val fallbackMutex = Mutex()
    return object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R =
            if (database != null) {
                database.withTransaction { block() }
            } else {
                fallbackMutex.withLock { block() }
            }
    }
}

private fun defaultRssAdapter(
    database: AppDatabase?,
    taskDao: ImportTaskDao,
    isNsfwContentBookmarkable: () -> Boolean,
    onResumeAcquisitions: suspend () -> Unit = {},
    onRetryAcquisition: suspend (String) -> Unit = {},
    onRestoreTask: (suspend (ImportTaskEntity) -> Unit)? = null
): ImportRssAdapter = object : ImportRssAdapter {
    override suspend fun resumeAcquisitions() {
        onResumeAcquisitions()
    }

    override suspend fun retryAcquisition(taskId: String) {
        onRetryAcquisition(taskId)
    }

    override suspend fun restoreTask(entity: ImportTaskEntity) {
        if (onRestoreTask != null) {
            onRestoreTask(entity)
        } else if (database != null) {
            me.bubblespark.tidilo.data.rss.RssIngestionStore(database).restoreTask(entity)
        } else {
            taskDao.insertOrUpdate(entity)
        }
    }

    override suspend fun validateRssWrite(url: String, bookmarkId: String, feedId: String, isNsfw: Boolean) {
        val db = database ?: return
        val rssDao = db.rssDao()
        val feed = rssDao.feed(feedId)
        val blocked = rssDao.rssWriteConflict(url, bookmarkId)
        if (feed == null || feed.deleted || blocked || (isNsfw && !isNsfwContentBookmarkable())) {
            throw me.bubblespark.tidilo.data.rss.RssImportSkipped()
        }
    }

    override suspend fun recordRecoveredDirectSave(taskId: String) {
        database?.rssNotificationDao()?.recordRecoveredDirectSave(taskId)
    }
}

class ImportRepository internal constructor(
    context: Context? = null,
    private val taskDao: ImportTaskDao = context?.let { AppDatabase.getInstance(it).importTaskDao() }
        ?: throw IllegalArgumentException("taskDao must be provided if context is null"),
    private val bookmarkDao: BookmarkDao? = context?.let { AppDatabase.getInstance(it).bookmarkDao() },
    private val notificationDao: ImportNotificationDao? = context?.let { AppDatabase.getInstance(it).importNotificationDao() },
    private val database: AppDatabase? = context?.let { AppDatabase.getInstance(it) },
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val articleAcquirer: suspend (String) -> ParsedWebPage = { ArticleAcquirer.acquire(it) },
    private val candidateExtractor: EntityCandidateExtractor = defaultCandidateExtractor(),
    private val transactionRunner: TransactionRunner = defaultTransactionRunner(database),
    private val domainPolicyAdapter: ImportDomainPolicyAdapter = ImportDomainPolicyAdapter.NO_OP,
    private val mediaCacheAdapter: ImportMediaCacheAdapter = ImportMediaCacheAdapter.NO_OP,
    private val isNsfwContentBookmarkable: () -> Boolean = { true },
    private val rssAdapter: ImportRssAdapter = defaultRssAdapter(database, taskDao, isNsfwContentBookmarkable),
    private val notificationAdapter: ImportNotificationAdapter = ImportNotificationAdapter.NO_OP,
    private val reportImportError: (Throwable) -> Unit = { error -> notificationAdapter.importError(error) },
    private val legacyQueuePrefsProvider: () -> SharedPreferences? = { null }
) {

    private val directExecutionMutex = Mutex()

    private val _processingTasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val processingTasks: StateFlow<List<ImportTask>> = _processingTasks.asStateFlow()

    private val _importingTasks = MutableStateFlow<List<ImportingTask>>(emptyList())
    val importingTasks: StateFlow<List<ImportingTask>> = _importingTasks.asStateFlow()

    private val _errorTasks = MutableStateFlow<List<ImportTask>>(emptyList())
    val errorTasks: StateFlow<List<ImportTask>> = _errorTasks.asStateFlow()

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

    internal val inFlightResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, ResolvedTarget>()
    private val pendingDuplicateNotificationJobs = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Job>>()
    internal var onEnteringTransaction: (suspend (url: String) -> Unit)? = null
    internal var onDuplicateIdentified: ((activeId: String) -> Unit)? = null
    internal var onEnteringFinalization: ((importingId: String) -> Unit)? = null

    private suspend fun <T> runInDbOrLock(url: String? = null, block: suspend () -> T): T {
        if (url != null) {
            onEnteringTransaction?.invoke(url)
        }
        return transactionRunner.run(block)
    }

    init {
        if (context != null) {
            repositoryScope.launch {
                if (taskDao.getCount() == 0) {
                    migrateFromLegacyPrefs()
                }
                rssAdapter.resumeAcquisitions()
                resumeDirectImports()
            }
        }

        // Reactively observe Room database changes and populate StateFlows
        repositoryScope.launch {
            taskDao.getAllTasksFlow().collect { entities ->
                val all = entities.map { it.toImportTask() }
                _processingTasks.value = all.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.PROCESSING }
                _errorTasks.value = all.filter { it.status == TaskStatus.ERROR }
            }
        }
    }

    private suspend fun checkDuplicate(url: String): DuplicateCheckResult {
        val existingBookmark = bookmarkDao?.getBookmarkByUrl(url)
        if (existingBookmark != null) {
            return if (existingBookmark.rssFeedId != null) {
                DuplicateCheckResult.PromotedFromRssToLibrary(existingBookmark)
            } else {
                DuplicateCheckResult.DuplicateInLibrary(existingBookmark)
            }
        }

        val existingTask = taskDao.getTaskByUrl(url)
        if (existingTask != null && existingTask.status in ACTIVE_QUEUE_STATUSES) {
            return if (existingTask.rssFeedId != null && existingTask.status == "QUEUED") {
                DuplicateCheckResult.MovedFromRssToDirect(existingTask)
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
        preparedImport: ImportTask? = null,
        onBeforeDirectSave: ((ImportTask) -> Unit)? = null
    ): DuplicateResolution {
        val domain = DomainIconManager.extractDomain(url) ?: url
        when (result) {
            is DuplicateCheckResult.NoDuplicate -> return DuplicateResolution.NotHandled

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
                onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(result.bookmark.id, url)))
                return DuplicateResolution.Handled()
            }

            is DuplicateCheckResult.MovedFromRssToDirect -> {
                if (!result.task.articlePrepared && preparedImport == null) return DuplicateResolution.NotHandled
                val target = preparedImport?.copy(id = result.task.id, createdAt = result.task.createdAt)
                    ?: result.task.copy(rssFeedId = null).toImportTask()
                onBeforeDirectSave?.invoke(target)
                val bookmark = skippedBookmark(target)
                val saved = persistDirectBookmarkInTransaction(bookmark)
                taskDao.deleteById(target.id)
                notificationDao?.insert(ImportNotificationEntity(type = NotificationType.INFO.name, domain = domain,
                    url = url, message = "Promoted from RSS to Library", existingBookmarkId = saved.id))
                importingId?.let { id -> _importingTasks.update { list -> list.filter { it.id != id } } }
                onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(saved.id, saved.url)))
                return DuplicateResolution.Handled(postCommitTask = target.copy(id = saved.id, url = saved.url))
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
                return DuplicateResolution.Handled()
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
                return DuplicateResolution.Handled()
            }
        }
    }

    fun importUrl(
        rawUrl: String,
        title: String? = null,
        onImported: ((Result<ImportOutcome>) -> Unit)? = null
    ): Result<Int> {
        fun reportOutcome(result: Result<ImportOutcome>) {
            result.exceptionOrNull()?.let(reportImportError)
            onImported?.invoke(result)
        }
        val trimmed = rawUrl.trim()
        val normalized = WebPageParser.normalizeUrl(trimmed)
        if (normalized == null || !isValidHttpUrl(normalized)) {
            val ex = IllegalArgumentException("Invalid HTTP/HTTPS URL")
            reportOutcome(Result.failure(ex))
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
            reportOutcome(Result.failure(ex))
            return Result.failure(ex)
        }

        val estimatedCount = _importingTasks.value.size

        repositoryScope.launch {
            var duplicateOutcome: Result<ImportOutcome>? = null
            val recordDuplicateOutcome: (Result<ImportOutcome>) -> Unit = { duplicateOutcome = it }
            fun reportCommittedDuplicate() {
                duplicateOutcome?.let { reportOutcome(it) }
                duplicateOutcome = null
            }
            var pendingSaveTarget: ImportTask? = null
            try {
                // Pre-Fetch Check (Primary Gate) - atomic duplicate check & mutation in database transaction
                val earlyResolution = runInDbOrLock(normalized) {
                    val preCheck = checkDuplicate(normalized)
                    handleDuplicateOrConflict(
                        url = normalized,
                        result = preCheck,
                        importingId = importing.id,
                        onImported = recordDuplicateOutcome,
                        onBeforeDirectSave = { pendingSaveTarget = it }
                    )
                }
                pendingSaveTarget = null
                reportCommittedDuplicate()
                if (earlyResolution is DuplicateResolution.Handled) {
                    earlyResolution.postCommitTask?.let { promoted ->
                        runPostCommitMediaAndPolicy(promoted, parsed = null)
                    }
                    return@launch
                }

                // 1. Fetch & parse HTML, extract metadata, OGP, and Readability body snippet
                val parsed = articleAcquirer(normalized)
                val prepared = ArticlePreparation.prepare(parsed)
                val classification = classifyStandardMetadata(parsed)

                val realUrl = WebPageParser.normalizeUrl(prepared.url) ?: prepared.url
                // Post-Redirect Check (handles redirects) - atomic duplicate check & mutation in database transaction
                if (realUrl != normalized) {
                    val redirectResolution = runInDbOrLock(realUrl) {
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
                        handleDuplicateOrConflict(
                            url = realUrl,
                            result = postCheck,
                            importingId = importing.id,
                            onImported = recordDuplicateOutcome,
                            onBeforeDirectSave = { pendingSaveTarget = it }
                        )
                    }
                    pendingSaveTarget = null
                    reportCommittedDuplicate()
                    if (redirectResolution is DuplicateResolution.Handled) {
                        redirectResolution.postCommitTask?.let { promoted ->
                            runPostCommitMediaAndPolicy(promoted, parsed = parsed)
                        }
                        return@launch
                    }
                }

                val hasCustomTitle = !title.isNullOrBlank() && !isRawUrlOrHost(title, normalized)
                val realTitle = if (hasCustomTitle) title.trim() else prepared.title
                val domain = DomainIconManager.extractDomain(realUrl)
                val siteName = prepared.siteName
                val isNsfw = parsed.isNsfwSignal || domainPolicyAdapter.isNsfwDomain(domain)

                val description = prepared.description
                val bodySnippet = prepared.bodySnippet
                val ogImageUrl = prepared.ogImageUrl

                // 2. Create fully pre-parsed ImportTask and save atomically in one flat transaction
                val newItem = ImportTask(
                    url = realUrl,
                    title = realTitle,
                    status = TaskStatus.QUEUED,
                    statusText = "",
                    isNsfw = isNsfw,
                    siteName = siteName,
                    description = description,
                    bodySnippet = bodySnippet,
                    ogImageUrl = ogImageUrl,
                    articlePrepared = true,
                    standardCategory = classification.category?.id,
                    standardSubcategory = classification.subcategory?.id,
                    standardClassificationEvaluated = true
                )

                val commitResult = runInDbOrLock(realUrl) {
                    val finalRecheck = checkDuplicate(realUrl)
                    if (finalRecheck !is DuplicateCheckResult.NoDuplicate) {
                        val resolution = handleDuplicateOrConflict(
                            url = realUrl,
                            result = finalRecheck,
                            importingId = importing.id,
                            onImported = recordDuplicateOutcome,
                            preparedImport = newItem,
                            onBeforeDirectSave = { pendingSaveTarget = it }
                        )
                        if (resolution is DuplicateResolution.Handled) {
                            FinalSaveResult.HandledDuplicate(resolution.postCommitTask)
                        } else {
                            FinalSaveResult.NotHandled
                        }
                    } else {
                        pendingSaveTarget = newItem
                        val saved = persistDirectBookmarkInTransaction(skippedBookmark(newItem))
                        FinalSaveResult.Inserted(newItem.copy(id = saved.id, url = saved.url))
                    }
                }
                pendingSaveTarget = null
                reportCommittedDuplicate()
                when (commitResult) {
                    is FinalSaveResult.NotHandled -> return@launch
                    is FinalSaveResult.HandledDuplicate -> {
                        commitResult.postCommitTask?.let { promoted ->
                            runPostCommitMediaAndPolicy(promoted, parsed = parsed)
                        }
                        return@launch
                    }
                    is FinalSaveResult.Inserted -> {
                        val savedTask = commitResult.savedTask
                        runPostCommitMediaAndPolicy(savedTask, parsed = parsed)
                        reportOutcome(Result.success(ImportOutcome.BookmarkSaved(savedTask.id, savedTask.url)))
                    }
                }
            } catch (e: DuplicateUrlException) {
                // Handled in handleDuplicateOrConflict; ignore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                duplicateOutcome = null
                val failedSaveTarget = pendingSaveTarget
                if (failedSaveTarget != null) {
                    // A failed flat Room transaction rolls back cleanly. Recover only after rollback.
                    runInDbOrLock(failedSaveTarget.url) {
                        val existingTask = taskDao.getTaskByUrl(failedSaveTarget.url)
                        if (bookmarkDao?.getBookmarkByUrl(failedSaveTarget.url) == null &&
                            (existingTask == null || existingTask.id == failedSaveTarget.id)) {
                            retainDirectSaveError(failedSaveTarget, e)
                        }
                    }
                    reportOutcome(Result.failure(e))
                    return@launch
                }
                // Fallback: If network/parsing throws, create fallback task with basic details
                val domain = DomainIconManager.extractDomain(normalized)
                val isDomainNsfw = domainPolicyAdapter.isNsfwDomain(domain)
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
                    if (fallbackCheck is DuplicateCheckResult.MovedFromRssToDirect) {
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
                reportOutcome(Result.failure(e))
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

    fun retryErrorTask(taskId: String) = repositoryScope.launch { retryErrorTaskAndAwait(taskId) }

    suspend fun retryErrorTaskAndAwait(taskId: String) = withContext(Dispatchers.IO) {
        val targetEntity = taskDao.getTaskById(taskId) ?: return@withContext
        if (targetEntity.status != "ERROR") return@withContext
        val target = targetEntity.toImportTask()
        if (target.rssFeedId != null && !target.articlePrepared) {
            rssAdapter.retryAcquisition(taskId)
            return@withContext
        }
        if (target.rssFeedId == null && target.errorMessage == me.bubblespark.tidilo.parser.ArticleContentUnavailable.MESSAGE) {
            // Explicit retry may recover text that was unavailable when the page was first acquired.
            taskDao.update(targetEntity.copy(articlePrepared = false))
        }
        val retried = target.copy(
            status = TaskStatus.QUEUED,
            statusText = "Waiting to save",
            errorMessage = null
        )
        taskDao.updateStatus(retried.id, retried.status.name, retried.statusText, retried.errorMessage)
        resumeDirectImports()
    }

    suspend fun deleteErrorTaskAndAwait(taskId: String) = withContext(Dispatchers.IO) {
        runInDbOrLock {
            if (taskDao.getTaskById(taskId)?.status == "ERROR") taskDao.deleteById(taskId)
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
        val entity = ImportTaskEntity.fromImportTask(task)
        if (task.rssFeedId != null) {
            rssAdapter.restoreTask(entity)
        } else {
            taskDao.insertOrUpdate(entity)
        }
    }

    fun onAppBecameActive() {
        resumeDirectImports()
    }

    private fun resumeDirectImports() {
        repositoryScope.launch {
            try {
                directExecutionMutex.withLock {
                    val candidates = taskDao.getTasksByStatuses(listOf("QUEUED", "PROCESSING"))
                    for (task in candidates) {
                        if (task.rssFeedId != null && !task.articlePrepared) continue
                        try {
                            processDirectTask(task.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("ImportRepository", "Could not recover import ${task.id}", error)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ImportRepository", "Could not read the direct import queue", error)
            }
            try {
                notificationAdapter.publishCompletedRssRuns()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("ImportRepository", "Could not publish recovered RSS notifications", error)
            }
        }
    }

    private suspend fun resolveSkippedTag(target: ImportTask): Tag {
        val dao = bookmarkDao ?: return Tag.createEmpty()
        return try {
            val candidateKeys = candidateExtractor.extractCandidateKeys(target.title, target.description)
            if (candidateKeys.isEmpty()) return Tag.createEmpty()
            val matched = dao.findCanonicalEntitySpellings(candidateKeys)
            if (matched.isEmpty()) return Tag.createEmpty()
            val entities = EntityTagPolicy.normalizeEntities(candidateKeys.mapNotNull { matched[it] })
            Tag.createEmpty().copy(entities = entities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w("ImportRepository", "Entity matching failed; saving without entities", e) }
            Tag.createEmpty()
        }
    }

    private suspend fun skippedBookmark(target: ImportTask): BookmarkItem = BookmarkItem(
        id = target.id,
        title = target.title,
        url = target.url,
        description = target.description,
        tag = (if (target.rssFeedId == null) resolveSkippedTag(target)
            else Tag.createEmpty().copy(entities = EntityTagPolicy.normalizeEntities(target.assignedTags))).copy(
            category = target.standardCategory.takeIf { target.standardClassificationEvaluated },
            subcategory = target.standardSubcategory.takeIf { target.standardClassificationEvaluated }
        ),
        isNsfw = target.isNsfw,
        ogImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() },
        createdAt = if (target.rssFeedId != null) target.createdAt else System.currentTimeMillis(),
        rssFeedId = target.rssFeedId,
        rssPublishedAt = target.rssPublishedAt
    )

    private suspend fun runPostCommitMediaAndPolicy(task: ImportTask, parsed: ParsedWebPage?) {
        val domain = DomainIconManager.extractDomain(task.url)
        domainPolicyAdapter.registerDomain(domain, false, task.siteName)
        if (domain != null && !mediaCacheAdapter.hasDomainIcon(domain)) {
            try {
                mediaCacheAdapter.fetchDomainIcon(
                    url = task.url,
                    appleTouchIconUrl = parsed?.appleTouchIconUrl,
                    faviconUrl = parsed?.faviconUrl,
                    knownCandidates = parsed?.iconCandidates
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Optional cache recovery remains independent of the durable save. */
            }
        }
        cacheDirectOgImage(task)
    }

    private fun cacheDirectOgImage(target: ImportTask) {
        val ogImage = target.ogImageUrl?.takeIf { it.isNotBlank() } ?: return
        repositoryScope.launch {
            try {
                mediaCacheAdapter.cacheOgImage(target.url, ogImage, target.rssFeedId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* Optional cache recovery remains independent of the durable save. */
            }
        }
    }

    private suspend fun persistDirectBookmarkInTransaction(item: BookmarkItem): BookmarkItem {
        val domain = DomainIconManager.extractDomain(item.url)
        val resolvedItem = if (domainPolicyAdapter.isNsfwDomain(domain) && !item.isNsfw) {
            item.copy(isNsfw = true)
        } else {
            item
        }
        val existing = bookmarkDao?.getBookmarkByUrl(resolvedItem.url)
            ?: bookmarkDao?.getBookmarkById(resolvedItem.id)
        val finalItem = if (existing != null) {
            val existingTag = existing.logicalTag()
            resolvedItem.copy(
                id = existing.id,
                tag = Tag(
                    category = resolvedItem.tag.category ?: existingTag.category,
                    subcategory = resolvedItem.tag.subcategory ?: existingTag.subcategory,
                    entities = resolvedItem.tag.entities.ifEmpty { existingTag.entities }
                ),
                note = if (resolvedItem.note.isNotBlank()) resolvedItem.note else existing.note,
                createdAt = existing.createdAt,
                isLiked = existing.isLiked,
                browserOpenCount = existing.browserOpenCount,
                detailViewCount = existing.detailViewCount,
                summaryPeekCount = existing.summaryPeekCount,
                lastInteractedAt = existing.lastInteractedAt
            )
        } else {
            resolvedItem
        }
        if (finalItem.rssFeedId != null) {
            rssAdapter.validateRssWrite(finalItem.url, finalItem.id, finalItem.rssFeedId, finalItem.isNsfw)
        }
        bookmarkDao?.insertReplacingUrl(BookmarkEntity.fromBookmarkItem(finalItem))
        return finalItem
    }

    private suspend fun retainDirectSaveError(target: ImportTask, error: Throwable) {
        taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(target.copy(status = TaskStatus.ERROR,
            statusText = "Save failed", errorMessage = "Bookmark persistence failed: ${error.message.orEmpty()}")))
    }

    private suspend fun processDirectTask(taskId: String) {
        var target = taskDao.getTaskById(taskId)?.toImportTask() ?: return
        if (target.status !in listOf(TaskStatus.QUEUED, TaskStatus.PROCESSING)) return
        if (taskDao.claimForDirectSave(target.id, "Saving bookmark...") != 1) {
            taskDao.rejectUnavailablePreparedRss(target.id)
            return
        }
        if (!target.articlePrepared && target.rssFeedId == null) {
            val alreadySaved = runInDbOrLock(target.url) {
                val existing = bookmarkDao?.getBookmarkByUrl(target.url) ?: return@runInDbOrLock false
                if (existing.rssFeedId != null) bookmarkDao.clearRssFeedId(existing.id)
                taskDao.deleteById(target.id)
                true
            }
            if (alreadySaved) return
        }
        var acquiredParsed: ParsedWebPage? = null
        try {
            if (!target.articlePrepared) {
                val parsed = articleAcquirer(target.url)
                acquiredParsed = parsed
                val article = ArticlePreparation.prepare(parsed)
                val classification = classifyStandardMetadata(parsed)
                target = target.copy(url = WebPageParser.normalizeUrl(article.url) ?: article.url,
                    title = if (isRawUrlOrHost(target.title, target.url)) article.title else target.title,
                    siteName = article.siteName, description = article.description, bodySnippet = article.bodySnippet,
                    ogImageUrl = article.ogImageUrl, isNsfw = article.isNsfw || domainPolicyAdapter.isNsfwDomain(DomainIconManager.extractDomain(article.url)),
                    articlePrepared = true, status = TaskStatus.PROCESSING, errorMessage = null,
                    standardCategory = classification.category?.id,
                    standardSubcategory = classification.subcategory?.id,
                    standardClassificationEvaluated = true)
                val current = taskDao.getTaskById(target.id) ?: return
                if (current.status != "PROCESSING") return
                taskDao.update(ImportTaskEntity.fromImportTask(target))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            taskDao.updateStatus(target.id, "ERROR", "Article acquisition failed", e.message)
            return
        }
        var savedBookmark: BookmarkItem? = null
        try {
            runInDbOrLock(target.url) {
                if (taskDao.getTaskById(target.id)?.status != "PROCESSING") return@runInDbOrLock
                val existing = bookmarkDao?.getBookmarkByUrl(target.url)
                if (existing != null) {
                    if (target.rssFeedId == null && existing.rssFeedId != null) bookmarkDao.clearRssFeedId(existing.id)
                } else {
                    val saved = persistDirectBookmarkInTransaction(skippedBookmark(target))
                    savedBookmark = saved
                    if (target.rssFeedId != null) {
                        rssAdapter.recordRecoveredDirectSave(target.id)
                    }
                }
                taskDao.deleteById(target.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: me.bubblespark.tidilo.data.rss.RssImportSkipped) {
            taskDao.deleteById(target.id)
            return
        } catch (e: Exception) {
            savedBookmark = null
            runInDbOrLock(target.url) {
                if (taskDao.getTaskById(target.id)?.status == "PROCESSING") {
                    retainDirectSaveError(target, e)
                }
            }
            return
        }
        val committedSaved = savedBookmark
        if (committedSaved != null) {
            val domain = DomainIconManager.extractDomain(committedSaved.url)
            domainPolicyAdapter.registerDomain(domain, false, target.siteName)
            if (domain != null && !mediaCacheAdapter.hasDomainIcon(domain)) {
                repositoryScope.launch {
                    try {
                        mediaCacheAdapter.fetchDomainIcon(
                            url = committedSaved.url,
                            appleTouchIconUrl = acquiredParsed?.appleTouchIconUrl,
                            faviconUrl = acquiredParsed?.faviconUrl,
                            knownCandidates = acquiredParsed?.iconCandidates
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        /* Optional enrichment. */
                    }
                }
            }
            cacheDirectOgImage(target.copy(id = committedSaved.id, url = committedSaved.url))
        } else if (taskDao.getTaskById(target.id) == null) {
            if (target.rssFeedId == null || bookmarkDao?.getBookmarkById(target.id) != null) {
                cacheDirectOgImage(target)
            }
        }
    }

    private suspend fun migrateFromLegacyPrefs() {
        val legacyPrefs = legacyQueuePrefsProvider() ?: return
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
                val statusStr = obj.optString("status", TaskStatus.QUEUED.name)
                val status = if (statusStr == "ERROR") TaskStatus.ERROR else TaskStatus.QUEUED

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
                        statusText = if (status == TaskStatus.ERROR) obj.optString("statusText", "") else "",
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
        internal fun needsArticlePreparse(task: ImportTask): Boolean =
            if (task.rssFeedId != null) !task.articlePrepared else !task.articlePrepared && (isRawUrlOrHost(task.title, task.url) || task.bodySnippet.isBlank())
        @Volatile
        private var INSTANCE: ImportRepository? = null

        fun getInstance(context: Context = TidiloApplication.instance): ImportRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createProductionInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun createProductionInstance(appContext: Context): ImportRepository {
            val db = AppDatabase.getInstance(appContext)
            val isNsfwBookmarkable: () -> Boolean = {
                me.bubblespark.tidilo.config.AppConfig.isNsfwContentBookmarkable.value
            }
            return ImportRepository(
                context = appContext,
                taskDao = db.importTaskDao(),
                bookmarkDao = db.bookmarkDao(),
                notificationDao = db.importNotificationDao(),
                database = db,
                domainPolicyAdapter = object : ImportDomainPolicyAdapter {
                    override fun isNsfwDomain(domain: String?): Boolean =
                        DomainPolicyStore.getInstance(appContext).isNsfwDomain(domain)

                    override fun registerDomain(domain: String?, isNsfw: Boolean, displayName: String) {
                        DomainPolicyStore.getInstance(appContext).registerDomain(domain, isNsfw, displayName)
                    }
                },
                mediaCacheAdapter = object : ImportMediaCacheAdapter {
                    override fun hasDomainIcon(domain: String): Boolean =
                        DomainIconManager.hasIcon(appContext, domain)

                    override suspend fun fetchDomainIcon(
                        url: String,
                        appleTouchIconUrl: String?,
                        faviconUrl: String?,
                        knownCandidates: List<DomainIconCandidate>?
                    ) {
                        DomainIconManager.fetchAndSaveDomainIcon(
                            context = appContext,
                            url = url,
                            appleTouchIconUrl = appleTouchIconUrl,
                            faviconUrl = faviconUrl,
                            knownCandidates = knownCandidates
                        )
                    }

                    override suspend fun cacheOgImage(url: String, ogImageUrl: String, rssFeedId: String?) {
                        OgImageManager.processAndSaveOgImage(
                            context = appContext,
                            pageUrl = url,
                            ogImageUrl = ogImageUrl,
                            network = if (rssFeedId != null) {
                                me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).articleNetwork()
                            } else {
                                null
                            }
                        )
                    }
                },
                isNsfwContentBookmarkable = isNsfwBookmarkable,
                rssAdapter = defaultRssAdapter(
                    database = db,
                    taskDao = db.importTaskDao(),
                    isNsfwContentBookmarkable = isNsfwBookmarkable,
                    onResumeAcquisitions = {
                        me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).resumeAcquisitions()
                    },
                    onRetryAcquisition = { taskId ->
                        me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).retryAcquisition(taskId)
                    },
                    onRestoreTask = { entity ->
                        me.bubblespark.tidilo.data.rss.RssIngestionStore(db).restoreTask(entity)
                    }
                ),
                notificationAdapter = object : ImportNotificationAdapter {
                    override fun importError(error: Throwable) {
                        me.bubblespark.tidilo.data.notifications.NotificationCoordinator.getInstance(appContext)
                            .importError(error)
                    }

                    override suspend fun publishCompletedRssRuns() {
                        me.bubblespark.tidilo.data.notifications.NotificationCoordinator.getInstance(appContext)
                            .publishCompletedRssRuns()
                    }
                },
                legacyQueuePrefsProvider = {
                    appContext.getSharedPreferences("tidilo_queue_prefs", Context.MODE_PRIVATE)
                }
            )
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

    }
}

class DuplicateUrlException(message: String) : Exception(message)

sealed class DuplicateCheckResult {
    data object NoDuplicate : DuplicateCheckResult()
    data class PromotedFromRssToLibrary(val bookmark: BookmarkEntity) : DuplicateCheckResult()
    data class MovedFromRssToDirect(val task: ImportTaskEntity) : DuplicateCheckResult()
    data class DuplicateInLibrary(val bookmark: BookmarkEntity) : DuplicateCheckResult()
    data class DuplicateInQueue(val task: ImportTaskEntity) : DuplicateCheckResult()
}
