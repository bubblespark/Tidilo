package me.bubblespark.tidilo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import me.bubblespark.tidilo.data.ImportRepository
import me.bubblespark.tidilo.data.AiCoreUsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.detectFreshInstallation
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator

class TidiloApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var aiAnalysisInitialization: Deferred<Unit>

    suspend fun awaitAiAnalysisInitialization() {
        aiAnalysisInitialization.await()
    }

    var isAppInForeground: Boolean = false
        private set

    companion object {
        lateinit var instance: TidiloApplication
            private set

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        aiAnalysisInitialization = applicationScope.async {
            val isFreshInstallation = detectFreshInstallation {
                val info = packageManager.getPackageInfo(packageName, 0)
                info.firstInstallTime to info.lastUpdateTime
            }
            AppConfig.initializeAiAnalysisMode(isFreshInstallation)
        }
        applicationScope.launch { AiCoreUsageRepository.getInstance(this@TidiloApplication).initialize() }
        applicationScope.launch {
            runCatching { me.bubblespark.tidilo.data.rss.RssScheduler.reschedule(this@TidiloApplication, force = false) }
        }
        NotificationCoordinator.getInstance(this).apply {
            createChannels()
            start(applicationScope)
        }
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
        applicationScope.launch {
            NotificationCoordinator.getInstance(this@TidiloApplication).refresh()
            awaitAiAnalysisInitialization()
            if (isAppInForeground) runCatching { ImportRepository.getInstance(this@TidiloApplication).onAppBecameActive() }
        }
    }

}
