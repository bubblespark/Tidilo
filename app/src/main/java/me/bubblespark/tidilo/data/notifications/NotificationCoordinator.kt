package me.bubblespark.tidilo.data.notifications

import android.Manifest
import android.app.Notification
import androidx.room.withTransaction
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase

enum class NotificationCategory(val channelId: String, val title: String, val destination: String, val id: Int) {
    IMPORT_ERROR("import_errors", "Import Errors", "notifications", 2002),
    RSS_UPDATES("rss_updates", "RSS Scheduled Updates", "library", 2003),
    RSS_ERROR("rss_errors", "RSS Errors", "settings_rss", 2004)
}

/** Owns OS notifications only; the in-app Notification history is deliberately independent. */
class NotificationCoordinator private constructor(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val publishMutex = Mutex()

    fun createChannels() {
        manager.createNotificationChannels(NotificationCategory.entries.map {
            NotificationChannel(it.channelId, it.title, NotificationManager.IMPORTANCE_DEFAULT)
        })
        manager.cancel("aicore", 2005)
        manager.deleteNotificationChannel("aicore_errors")
        manager.cancel("ready", 2001)
        manager.deleteNotificationChannel("ready_ai_analysis")
        manager.cancel(1001)
        manager.deleteNotificationChannel("tidilo_share_import")
    }

    fun start(scope: CoroutineScope) {
        scope.launch { publishCompletedRssRuns() }
    }

    suspend fun refresh() {
        publishCompletedRssRuns()
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
        // The supplied bookmark vector is shared across the OS categories.
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

    /** Stable run tags make replay after process death replace the same notices without another alert. */
    suspend fun publishCompletedRssRuns() = publishMutex.withLock {
        val database = AppDatabase.getInstance(context)
        val dao = database.rssNotificationDao()
        // Startup scheduling can cancel a crashed worker after its last XML failure disabled all feeds.
        dao.finishRunsWithoutEnabledFeeds()
        for (candidate in dao.unpublishedRuns()) {
            // Keep the outcome snapshot stable through posting and allow replay if posting is interrupted.
            database.withTransaction {
                val run = dao.unpublishedRuns(candidate.id).singleOrNull() ?: return@withTransaction
                val items = dao.items(run.id)
                if (run.showSummary) {
                    post(NotificationCategory.RSS_UPDATES, context.getString(R.string.notification_rss_complete_body,
                        items.count { it.outcome == "DIRECT" }), run.id)
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
