package me.bubblespark.tidilo.data

import android.content.Context
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
import me.bubblespark.tidilo.config.AppConfig
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

private class DirectBookmarkSaveFailure(val target: ImportTask, cause: Exception) : Exception(cause)

private fun defaultCandidateExtractor(): EntityCandidateExtractor = EntityCandidateExtractor.default()

class ImportRepository internal constructor(
    context: Context? = null,
    private val taskDao: ImportTaskDao = context?.let { AppDatabase.getInstance(it).importTaskDao() }
        ?: throw IllegalArgumentException("taskDao must be provided if context is null"),
    private val bookmarkDao: BookmarkDao? = context?.let { AppDatabase.getInstance(it).bookmarkDao() },
    private val notificationDao: ImportNotificationDao? = context?.let { AppDatabase.getInstance(it).importNotificationDao() },
    private val database: AppDatabase? = context?.let { AppDatabase.getInstance(it) },
    private val repositoryScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val articleAcquirer: suspend (String) -> ParsedWebPage = { ArticleAcquirer.acquire(it) },
    private val persistFallback: suspend (BookmarkItem) -> Unit = {
        BookmarkRepository.getInstance().addBookmarkAndAwait(it)
    },
    private val reportImportError: (Throwable) -> Unit = { error -> context?.let {
        me.bubblespark.tidilo.data.notifications.NotificationCoordinator.getInstance(it).importError(error)
    } },
    private val candidateExtractor: EntityCandidateExtractor = defaultCandidateExtractor()
) {

    private val appContext: Context? = context?.applicationContext
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
    init {
        context?.let {
            repositoryScope.launch {
                if (taskDao.getCount() == 0) {
                    migrateFromLegacyPrefs()
                }
                me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).resumeAcquisitions()
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
                onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(result.bookmark.id, url)))
                return true
            }

            is DuplicateCheckResult.MovedFromRssToDirect -> {
                if (!result.task.articlePrepared && preparedImport == null) return false
                val target = preparedImport?.copy(id = result.task.id, createdAt = result.task.createdAt)
                    ?: result.task.copy(rssFeedId = null).toImportTask()
                val bookmark = skippedBookmark(target)
                persistDirectBookmark(target, bookmark)
                taskDao.deleteById(target.id)
                notificationDao?.insert(ImportNotificationEntity(type = NotificationType.INFO.name, domain = domain,
                    url = url, message = "Promoted from RSS to Library", existingBookmarkId = bookmark.id))
                cacheDirectOgImage(target)
                importingId?.let { id -> _importingTasks.update { list -> list.filter { it.id != id } } }
                onImported?.invoke(Result.success(ImportOutcome.BookmarkSaved(bookmark.id, url)))
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
            try {
                // Pre-Fetch Check (Primary Gate) - atomic duplicate check & mutation in database transaction
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
                val classification = classifyStandardMetadata(parsed)

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

                val isInserted = runInDbOrLock(realUrl) {
                    val finalRecheck = checkDuplicate(realUrl)
                    if (finalRecheck !is DuplicateCheckResult.NoDuplicate) {
                        handleDuplicateOrConflict(realUrl, finalRecheck, importing.id, recordDuplicateOutcome, newItem)
                        false
                    } else {
                        persistDirectBookmark(newItem, skippedBookmark(newItem))
                        true
                    }
                }
                reportCommittedDuplicate()
                if (!isInserted) {
                    return@launch
                }
                cacheDirectOgImage(newItem)
                reportOutcome(Result.success(ImportOutcome.BookmarkSaved(newItem.id, newItem.url)))
            } catch (e: DuplicateUrlException) {
                // Handled in handleDuplicateOrConflict; ignore
            } catch (e: CancellationException) {
                throw e
            } catch (e: DirectBookmarkSaveFailure) {
                // A failed nested Room transaction invalidates its outer transaction. Recover only after rollback.
                runInDbOrLock(e.target.url) {
                    val existingTask = taskDao.getTaskByUrl(e.target.url)
                    if (bookmarkDao?.getBookmarkByUrl(e.target.url) == null &&
                        (existingTask == null || existingTask.id == e.target.id)) {
                        retainDirectSaveError(e.target, e.cause ?: e)
                    }
                }
                reportOutcome(Result.failure(e.cause ?: e))
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

    fun retryErrorTask(taskId: String) {
        repositoryScope.launch {
            val targetEntity = taskDao.getTaskById(taskId) ?: return@launch
            if (targetEntity.status != "ERROR") return@launch
            val target = targetEntity.toImportTask()
            if (target.rssFeedId != null && !target.articlePrepared) {
                appContext?.let { me.bubblespark.tidilo.data.rss.RssRepository.getInstance(it).retryAcquisition(taskId) }
                return@launch
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

    fun onAppBecameActive() {
        resumeDirectImports()
    }

    private fun resumeDirectImports() {
        repositoryScope.launch {
            directExecutionMutex.withLock {
                val candidates = taskDao.getTasksByStatuses(listOf("QUEUED", "PROCESSING"))
                for (task in candidates) {
                    if (task.rssFeedId != null && !task.articlePrepared) continue
                    processDirectTask(task.id)
                }
            }
            appContext?.let {
                me.bubblespark.tidilo.data.notifications.NotificationCoordinator.getInstance(it).publishCompletedRssRuns()
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
        isAiSkipped = true,
        ogImageUrl = target.ogImageUrl?.takeIf { it.isNotBlank() },
        createdAt = if (target.rssFeedId != null) target.createdAt else System.currentTimeMillis(),
        rssFeedId = target.rssFeedId,
        rssPublishedAt = target.rssPublishedAt
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

    private suspend fun persistDirectBookmark(target: ImportTask, bookmark: BookmarkItem) {
        try {
            persistFallback(bookmark)
        } catch (e: CancellationException) { throw e }
        catch (e: me.bubblespark.tidilo.data.rss.RssImportSkipped) { throw e }
        catch (e: Exception) { throw DirectBookmarkSaveFailure(target, e) }
    }

    private suspend fun retainDirectSaveError(target: ImportTask, error: Throwable) {
        taskDao.insertOrUpdate(ImportTaskEntity.fromImportTask(target.copy(status = TaskStatus.ERROR,
            statusText = "Save failed", errorMessage = "Bookmark persistence failed: ${error.message.orEmpty()}")))
    }

    private suspend fun processDirectTask(taskId: String) {
        var target = taskDao.getTaskById(taskId)?.toImportTask() ?: return
        if (target.status !in listOf(TaskStatus.QUEUED, TaskStatus.PROCESSING)) return
        if (taskDao.claimForDirectSave(target.id, "Saving bookmark...") != 1) return
        if (!target.articlePrepared && target.rssFeedId == null) {
            val alreadySaved = runInDbOrLock(target.url) {
                val existing = bookmarkDao?.getBookmarkByUrl(target.url) ?: return@runInDbOrLock false
                if (existing.rssFeedId != null) bookmarkDao.clearRssFeedId(existing.id)
                taskDao.deleteById(target.id)
                true
            }
            if (alreadySaved) return
        }
        try {
            if (!target.articlePrepared) {
                val parsed = articleAcquirer(target.url)
                val article = ArticlePreparation.prepare(parsed)
                val classification = classifyStandardMetadata(parsed)
                target = target.copy(url = WebPageParser.normalizeUrl(article.url) ?: article.url,
                    title = if (isRawUrlOrHost(target.title, target.url)) article.title else target.title,
                    siteName = article.siteName, description = article.description, bodySnippet = article.bodySnippet,
                    ogImageUrl = article.ogImageUrl, isNsfw = article.isNsfw || DomainPolicyManager.isNsfwDomain(DomainIconManager.extractDomain(article.url)),
                    articlePrepared = true, status = TaskStatus.PROCESSING, errorMessage = null,
                    standardCategory = classification.category?.id,
                    standardSubcategory = classification.subcategory?.id,
                    standardClassificationEvaluated = true)
                val current = taskDao.getTaskById(target.id) ?: return
                if (current.status != "PROCESSING") return
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
                if (taskDao.getTaskById(target.id)?.status != "PROCESSING") return@runInDbOrLock
                val existing = bookmarkDao?.getBookmarkByUrl(target.url)
                if (existing != null) {
                    if (target.rssFeedId == null && existing.rssFeedId != null) bookmarkDao.clearRssFeedId(existing.id)
                } else {
                    persistDirectBookmark(target, skippedBookmark(target))
                    val feedId = target.rssFeedId
                    if (feedId != null && database != null) {
                        me.bubblespark.tidilo.data.rss.RssIngestionStore(database)
                            .enforceRetention(feedId, includeDisabled = true)
                        database.rssNotificationDao().recordRecoveredDirectSave(target.id)
                    }
                }
                taskDao.deleteById(target.id)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: me.bubblespark.tidilo.data.rss.RssImportSkipped) {
            taskDao.deleteById(target.id)
            return
        }
        catch (e: Exception) {
            runInDbOrLock(target.url) {
                if (taskDao.getTaskById(target.id)?.status == "PROCESSING") {
                    retainDirectSaveError(target, e.cause ?: e)
                }
            }
            return
        }
        if (taskDao.getTaskById(target.id) == null) {
            cacheDirectOgImage(target)
            if (target.rssFeedId != null && appContext != null) {
                me.bubblespark.tidilo.data.rss.RssRepository.getInstance(appContext).cleanup()
            }
        }
    }

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
                INSTANCE ?: ImportRepository(context.applicationContext).also { INSTANCE = it }
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
