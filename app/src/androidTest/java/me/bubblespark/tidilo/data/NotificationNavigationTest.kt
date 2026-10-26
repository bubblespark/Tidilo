package me.bubblespark.tidilo.data

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun pendingIntentOpensImportOnColdStartAndRssAndLibraryOnWarmStart() = runBlocking {
        val mode = AppConfig.aiAnalysisMode.value
        val auto = AppConfig.isAutoRunAiOnActive.value
        AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
        AppConfig.setAutoRunAiOnActive(false)
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: ComponentActivity? = null
        try {
            val coordinator = NotificationCoordinator.getInstance(context)
            coordinator.buildNotification(NotificationCategory.READY, "Ready").contentIntent.send()
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 8000) as ComponentActivity?
            assertNotNull(activity)
            awaitText(context.getString(R.string.import_header_subtitle))
            coordinator.buildNotification(NotificationCategory.RSS_ERROR, "Disabled").contentIntent.send()
            awaitText(context.getString(R.string.rss_schedule))
            coordinator.buildNotification(NotificationCategory.RSS_UPDATES, "Completed").contentIntent.send()
            awaitText(context.getString(R.string.library_header_title))
            withTimeout(5000) {
                while (find { it.text?.toString() == context.getString(R.string.rss_schedule) } != null) delay(50)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            AppConfig.setAiAnalysisMode(mode)
            AppConfig.setAutoRunAiOnActive(auto)
        }
    }

    @Test fun notificationCardDirectlyLaunchesAppSystemSettings() = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_APP_NOTIFICATION_SETTINGS),
            Instrumentation.ActivityResult(0, null), true)
        try {
            instrumentation.runOnMainSync { activity.setContent { MaterialTheme {
                SettingsScreen(onNavigateBack = {}, onNavigateToGenerateContentRequest = {}, checkAiCoreAvailable = { false })
            } } }
            awaitText("Notification")
            withTimeout(5000) {
                while (monitor.hits == 0) {
                    var target = find { it.text?.toString() == "Notification" }
                    while (target != null && !target.isClickable) target = target.parent
                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(100)
                }
            }
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun awaitText(text: String) = withTimeout(8000) {
        while (find { it.text?.toString() == text } == null) delay(50)
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
