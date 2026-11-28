package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legacy `import` notification extra opens the Notification list on a cold `MainActivity` launch.
 *
 * No ComposeTestRule here: the rule's effect dispatcher would also drive `MainActivity`'s composition and
 * run the notification-route effect before the NavHost graph exists, which the real app never does.
 * The screen is read through the accessibility tree instead.
 */
class LegacyImportEntryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun findText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text) return true
        return (0 until node.childCount).any { findText(node.getChild(it), text) }
    }

    @Test fun legacyImportEntryOpensNotificationList(): Unit = runBlocking {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        try {
            AppConfig.setOverlayBlurEnabled(false)
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(NotificationCoordinator.EXTRA_DESTINATION, "import")
            ActivityScenario.launch<MainActivity>(intent).use {
                val title = context.getString(R.string.settings_notification_title)
                val found = withTimeoutOrNull(8_000) {
                    while (true) {
                        instrumentation.uiAutomation.clearCache()
                        if (findText(instrumentation.uiAutomation.rootInActiveWindow, title)) break
                        delay(100)
                    }
                    true
                }
                assertTrue("Legacy import entry must open Notification", found == true)
            }
        } finally {
            AppConfig.setOverlayBlurEnabled(oldBlur)
            caches.close()
        }
    }
}
