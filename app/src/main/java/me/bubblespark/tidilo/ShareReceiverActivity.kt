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
import me.bubblespark.tidilo.data.QueueRepository

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
                message = "Enqueue failed. Please check the contents."
            )
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val validUrl = QueueRepository.extractUrlFromText(sharedText)

        if (validUrl != null) {
            val repository = QueueRepository.getInstance(applicationContext)
            val appContext = applicationContext
            repository.enqueue(validUrl) { result ->
                result.onSuccess {
                    val count = repository.currentQueuingCount
                    showNotification(
                        context = appContext,
                        success = true,
                        message = "Enqueue was successful. There are $count items in the queuing."
                    )
                }.onFailure {
                    showNotification(
                        context = appContext,
                        success = false,
                        message = "Enqueue failed. Please check the contents."
                    )
                }
            }
        } else {
            showNotification(
                context = applicationContext,
                success = false,
                message = "Enqueue failed. Please check the contents."
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

        // Open MainActivity (Queue screen) on notification tap
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

        val notification = NotificationCompat.Builder(context, TidiloApplication.CHANNEL_ID_QUEUE)
            .setSmallIcon(iconRes)
            .setContentTitle("Tidilo")
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
