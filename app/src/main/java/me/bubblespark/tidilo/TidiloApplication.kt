package me.bubblespark.tidilo

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import me.bubblespark.tidilo.data.QueueRepository

class TidiloApplication : Application() {

    var isAppInForeground: Boolean = false
        private set

    companion object {
        lateinit var instance: TidiloApplication
            private set

        const val CHANNEL_ID_QUEUE = "tidilo_share_queue"
        const val CHANNEL_NAME_QUEUE = "Queue Notifications"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        registerLifecycleCallbacks()
    }

    private fun registerLifecycleCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var activityReferences = 0
            private var isActivityChangingConfigurations = false

            override fun onActivityStarted(activity: Activity) {
                if (++activityReferences == 1 && !isActivityChangingConfigurations) {
                    isAppInForeground = true
                    onAppBecameActive()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                isActivityChangingConfigurations = activity.isChangingConfigurations
                if (--activityReferences == 0 && !isActivityChangingConfigurations) {
                    isAppInForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {
                isAppInForeground = true
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun onAppBecameActive() {
        runCatching {
            QueueRepository.getInstance(this).onAppBecameActive()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_QUEUE,
                CHANNEL_NAME_QUEUE,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for URL enqueue operations"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
