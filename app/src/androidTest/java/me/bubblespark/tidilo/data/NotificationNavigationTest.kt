package me.bubblespark.tidilo.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.notifications.NotificationCategory
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /**
     * A notification tap is sent by System UI, which may start activities from the background. The test process
     * has no visible window on a cold start, so it borrows that privilege for the send only.
     */
    private fun PendingIntent.sendLikeSystemUi() {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND")
        try {
            send(context, 0, null, null, null, null, options)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun pendingIntentOpensNotificationsOnColdStartAndRssAndLibraryOnWarmStart() = runBlocking {
        val mode = AppConfig.aiAnalysisMode.value
        val blur = AppConfig.overlayBlurEnabled.value
        AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
        AppConfig.setOverlayBlurEnabled(false)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: ComponentActivity? = null
        try {
            val coordinator = NotificationCoordinator.getInstance(context)
            coordinator.buildNotification(NotificationCategory.IMPORT_ERROR, "Import error").contentIntent.sendLikeSystemUi()
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 8000) as ComponentActivity?
            assertNotNull(activity)
            awaitText(context.getString(R.string.settings_notification_title))
            coordinator.buildNotification(NotificationCategory.RSS_ERROR, "Disabled").contentIntent.sendLikeSystemUi()
            awaitText(context.getString(R.string.rss_schedule))
            coordinator.buildNotification(NotificationCategory.RSS_UPDATES, "Completed").contentIntent.sendLikeSystemUi()
            withTimeout(8000) {
                while (find { it.contentDescription?.toString() == context.getString(R.string.navigation_open) } == null) delay(100)
            }
            withTimeout(5000) {
                while (find { it.text?.toString() == context.getString(R.string.rss_schedule) } != null) delay(100)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            AppConfig.setAiAnalysisMode(mode)
            AppConfig.setOverlayBlurEnabled(blur)
        }
    }

    @Test fun notificationCardDirectlyLaunchesAppSystemSettings() = runBlocking {
        val blur = AppConfig.overlayBlurEnabled.value
        AppConfig.setOverlayBlurEnabled(false)
        var activity: ComponentActivity? = null
        try {
            val launched = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity = launched
            click { it.contentDescription?.toString() == launched.getString(R.string.navigation_open) }
            click { it.text?.toString() == launched.getString(R.string.title_settings) }
            awaitText("Notification")
            repeat(2) {
                android.util.Log.i("NotificationSettingsTest", "Opening OS settings iteration=$it")
                click { it.text?.toString() == "Notification" }
                withTimeout(8000) {
                    while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings") delay(100)
                    android.util.Log.i("NotificationSettingsTest", "OS settings visible")
                    while (find { it.text?.toString()?.contains(context.getString(R.string.app_name)) == true } == null) delay(100)
                }
                assertNotNull(find { it.text?.toString()?.contains(context.getString(R.string.app_name)) == true })
                assertFalse(launched.isFinishing)
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                android.util.Log.i("NotificationSettingsTest", "Returning to app")
                awaitText("Notification")
            }
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            AppConfig.setOverlayBlurEnabled(blur)
        }
    }

    private suspend fun click(predicate: (AccessibilityNodeInfo) -> Boolean) = withTimeout(5000) {
        while (true) {
            var target = find(predicate)
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
            delay(100)
        }
    }

    private suspend fun awaitText(text: String) = withTimeout(8000) {
        while (find { it.text?.toString() == text } == null) delay(100)
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
}
