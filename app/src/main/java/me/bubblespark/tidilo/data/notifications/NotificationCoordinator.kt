package me.bubblespark.tidilo.data.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase

enum class NotificationCategory(val channelId: String, val title: String, val destination: String, val id: Int) {
    READY("ready_ai_analysis", "URLs Pending AI Analysis", "import", 2001),
    IMPORT_ERROR("import_errors", "Import Errors", "import", 2002),
    RSS_UPDATES("rss_updates", "RSS Scheduled Updates", "library", 2003),
    RSS_ERROR("rss_errors", "RSS Errors", "settings_rss", 2004),
    AICORE_ERROR("aicore_errors", "AICore Errors", "import", 2005)
}

/** Owns OS notifications only; ImportScreen's bell history is deliberately independent. */
class NotificationCoordinator private constructor(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("tidilo_notifications", Context.MODE_PRIVATE)
    private val readyPolicy = ReadyNotificationPolicy(preferences.getBoolean("ready_above_threshold", false))
    private val readyMutex = Mutex()
    private val publishMutex = Mutex()
    private var readyCount = 0

    fun createChannels() {
        manager.createNotificationChannels(NotificationCategory.entries.map {
            NotificationChannel(it.channelId, it.title, NotificationManager.IMPORTANCE_DEFAULT)
        })
        manager.cancel(1001)
        manager.deleteNotificationChannel("tidilo_share_import")
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            AppDatabase.getInstance(context).importTaskDao().observeReadyNotificationCount()
                .distinctUntilChanged().collect { count -> readyMutex.withLock {
                    readyCount = count
                    reconcileReady()
                } }
        }
        scope.launch { publishCompletedRssRuns() }
    }

    suspend fun refresh() {
        readyMutex.withLock {
            readyCount = AppDatabase.getInstance(context).importTaskDao().observeReadyNotificationCount().first()
            reconcileReady()
        }
        publishCompletedRssRuns()
    }

    private fun reconcileReady() {
        val category = NotificationCategory.READY
        if (readyCount > 10 && !canPost(category)) return
        val visible = manager.activeNotifications.any { it.id == category.id && it.tag == "ready" }
        when (readyPolicy.update(readyCount, visible)) {
            ReadyNotificationAction.POST, ReadyNotificationAction.UPDATE -> post(category,
                context.getString(R.string.notification_ready_body, readyCount), "ready")
            ReadyNotificationAction.CANCEL -> manager.cancel("ready", category.id)
            ReadyNotificationAction.NONE -> Unit
        }
        preferences.edit().putBoolean("ready_above_threshold", readyPolicy.aboveThreshold).apply()
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    internal fun buildNotification(category: NotificationCategory, body: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.notification.${category.name}"
            putExtra(EXTRA_DESTINATION, category.destination)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(context, category.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The supplied bookmark vector is shared across the five OS categories.
        return NotificationCompat.Builder(context, category.channelId)
            .setSmallIcon(R.drawable.ic_bookmark)
            .setContentTitle(category.title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(pendingIntent).build()
    }

    private fun canPost(category: NotificationCategory): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(category.channelId)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun post(category: NotificationCategory, body: String, tag: String): Boolean {
        if (!canPost(category)) return false
        return try {
            manager.notify(tag, category.id, buildNotification(category, body))
            true
        } catch (_: SecurityException) { false }
    }

    fun importError(error: Throwable) {
        post(NotificationCategory.IMPORT_ERROR, notificationErrorDetail(error), "manual_import")
    }

    fun aiCoreError(error: Throwable, code: Int?) {
        val detail = notificationErrorDetail(error)
        val body = if (code == null) detail else "AICore error $code: $detail"
        post(NotificationCategory.AICORE_ERROR, body, "aicore")
    }

    /** Stable run tags make replay after process death replace the same notices without another alert. */
    suspend fun publishCompletedRssRuns() = publishMutex.withLock {
        val dao = AppDatabase.getInstance(context).rssNotificationDao()
        // Startup scheduling can cancel a crashed worker after its last XML failure disabled all feeds.
        dao.finishRunsWithoutEnabledFeeds()
        for (run in dao.unpublishedRuns()) {
            val items = dao.items(run.id)
            if (run.showSummary) {
                post(NotificationCategory.RSS_UPDATES, context.getString(R.string.notification_rss_complete_body,
                    items.count { it.outcome == "DIRECT" }, run.pendingAi), run.id)
            }
            if (items.any { it.outcome == "RSS_ERROR" }) {
                post(NotificationCategory.RSS_ERROR, context.getString(R.string.notification_rss_disabled_body), run.id)
            }
            val errors = items.filter { it.outcome == "IMPORT_ERROR" }
            if (errors.isNotEmpty()) {
                val details = errors.map { it.detail }.distinct().take(3).joinToString("\n")
                post(NotificationCategory.IMPORT_ERROR,
                    context.getString(R.string.notification_rss_article_errors, errors.size, details), run.id)
            }
            // Blocked notifications are not queued for surprise delivery after a later permission grant.
            dao.markPublished(run.id)
        }
        val before = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        dao.pruneItems(before)
        dao.pruneRuns(before)
    }

    companion object {
        const val EXTRA_DESTINATION = "notification_destination"
        @Volatile private var instance: NotificationCoordinator? = null
        fun getInstance(context: Context): NotificationCoordinator = instance ?: synchronized(this) {
            instance ?: NotificationCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
