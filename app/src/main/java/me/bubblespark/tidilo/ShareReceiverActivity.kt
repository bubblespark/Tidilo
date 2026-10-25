package me.bubblespark.tidilo

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.ImportOutcome

class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        disableActivityTransitions()
        super.onCreate(savedInstanceState)

        handleShareIntent(intent)

        finish()
        disableActivityTransitions()
    }

    private fun disableActivityTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND || intent.type != "text/plain") {
            showNotification(
                context = applicationContext,
                success = false,
                message = applicationContext.getString(R.string.notification_import_failed)
            )
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val validUrl = ImportRepository.extractUrlFromText(sharedText)

        if (validUrl != null) {
            val repository = ImportRepository.getInstance(applicationContext)
            val appContext = applicationContext
            repository.importUrl(validUrl) { result ->
                result.onSuccess { outcome ->
                    val count = repository.readyCount
                    showNotification(
                        context = appContext,
                        success = true,
                        message = if (outcome is ImportOutcome.BookmarkSaved) appContext.getString(R.string.notification_bookmark_added)
                            else appContext.getString(R.string.notification_import_success, count)
                    )
                }.onFailure {
                    showNotification(
                        context = appContext,
                        success = false,
                        message = appContext.getString(R.string.notification_import_failed)
                    )
                }
            }
        } else {
            showNotification(
                context = applicationContext,
                success = false,
                message = applicationContext.getString(R.string.notification_import_failed)
            )
        }
    }

    private fun showNotification(context: Context, success: Boolean, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                // Cannot post notification without permission
                return
            }
        }

        // Open MainActivity (Import screen) on notification tap
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = if (success) {
            android.R.drawable.stat_notify_more
        } else {
            android.R.drawable.stat_notify_error
        }

        val notification = NotificationCompat.Builder(context, TidiloApplication.CHANNEL_ID_IMPORT)
            .setSmallIcon(iconRes)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
