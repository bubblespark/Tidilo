package me.bubblespark.tidilo.ui.screens.importscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.ImportTask
import me.bubblespark.tidilo.data.ImportingTask

class ImportViewModel(
    private val repository: ImportRepository = ImportRepository.getInstance(TidiloApplication.instance)
) : ViewModel() {
    val notificationFeeds by lazy {
        AppDatabase.getInstance(TidiloApplication.instance).rssDao().observeFeeds().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
    private val _notificationBusyKeys = MutableStateFlow<Set<String>>(emptySet())
    val notificationBusyKeys = _notificationBusyKeys.asStateFlow()
    private val _notificationActionError = MutableStateFlow<String?>(null)
    val notificationActionError = _notificationActionError.asStateFlow()

    fun dismissNotificationActionError() { _notificationActionError.value = null }

    fun retryNotification(item: NotificationListItem) = notificationAction(item.key) {
        when (item) {
            is NotificationListItem.Failure -> repository.retryErrorTaskAndAwait(item.task.id)
            is NotificationListItem.RssXmlFailure -> RssRepository.getInstance(TidiloApplication.instance).retryXmlError(item.feed.id)
            is NotificationListItem.History -> Unit
        }
    }

    fun deleteNotification(item: NotificationListItem) = notificationAction(item.key) {
        when (item) {
            is NotificationListItem.Failure -> repository.deleteErrorTaskAndAwait(item.task.id)
            is NotificationListItem.RssXmlFailure -> RssRepository.getInstance(TidiloApplication.instance)
                .dismissXmlError(item.feed.id, item.feed.revision, item.createdAt)
            is NotificationListItem.History -> Unit
        }
    }

    private fun notificationAction(key: String, action: suspend () -> Unit) {
        if (key in _notificationBusyKeys.value) return
        _notificationBusyKeys.update { it + key }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _notificationActionError.value = error.message ?: TidiloApplication.instance.getString(me.bubblespark.tidilo.R.string.notification_action_failed) }
            finally { _notificationBusyKeys.update { it - key } }
        }
    }

    // Processing group: AI analysis in progress or awaiting execution
    val processingTasks: StateFlow<List<ImportTask>> = repository.processingTasks

    // Tasks currently being fetched and parsed
    val importingTasks: StateFlow<List<ImportingTask>> = repository.importingTasks

    // Error group: Tasks that failed due to rate limits, HTTP errors, etc.
    val errorTasks: StateFlow<List<ImportTask>> = repository.errorTasks

    val notifications: StateFlow<List<ImportNotificationEntity>> = repository.notifications.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val unreadNotificationCount: StateFlow<Int> = repository.unreadNotificationCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 0
    )

    fun markAllNotificationsAsRead() {
        repository.markAllNotificationsAsRead()
    }

    fun clearAllNotifications() {
        repository.clearAllNotifications()
    }

    fun findBookmarkIdByUrl(url: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val id = repository.findBookmarkIdByUrl(url)
            onResult(id)
        }
    }

    fun importUrl(url: String, title: String? = null) {
        repository.importUrl(url, title)
    }

    /**
     * Retries a task in the Error group.
     */
    fun retryError(taskId: String) {
        repository.retryErrorTask(taskId)
    }

    /**
     * Deletes an Error task (returns previous state for Undo).
     */
    fun deleteErrorTask(taskId: String): Pair<ImportTask, Int>? {
        return repository.removeErrorTask(taskId)
    }

    /**
     * Restores a deleted Error task.
     */
    fun restoreErrorTask(task: ImportTask, index: Int = 0) {
        repository.restoreErrorTask(task, index)
    }

    companion object {
        /**
         * Validates whether the given URL is a valid http/https URL.
         */
        fun isValidHttpUrl(url: String?): Boolean {
            return ImportRepository.isValidHttpUrl(url)
        }

        fun extractHostOrTitle(url: String): String {
            return ImportRepository.extractHostOrTitle(url)
        }
    }
}
