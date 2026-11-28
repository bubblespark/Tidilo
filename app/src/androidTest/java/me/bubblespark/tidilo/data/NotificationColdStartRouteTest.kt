package me.bubblespark.tidilo.data

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import org.junit.Rule
import org.junit.Test

/**
 * A notification destination on cold start opens its screen. The test rule runs launch effects before the
 * subcomposed `NavHost` sets its graph, which is the ordering the routing must wait out.
 */
class NotificationColdStartRouteTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun coldStartDestinationOpensNotificationWithDiscoverPreviewOff() = coldStartOpensNotification(false)

    @Test fun coldStartDestinationOpensNotificationWithDiscoverPreviewOn() = coldStartOpensNotification(true)

    private fun coldStartOpensNotification(discoverPreview: Boolean) {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val oldPreview = AppConfig.discoverPreviewEnabled.value
        val caches = OfflineVisualCaches()
        try {
            AppConfig.setOverlayBlurEnabled(false)
            AppConfig.setDiscoverPreviewEnabled(discoverPreview)
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(NotificationCoordinator.EXTRA_DESTINATION, "import")
            ActivityScenario.launch<MainActivity>(intent).use {
                composeRule.awaitNode(hasText(context.getString(R.string.settings_notification_title)))
            }
        } finally {
            AppConfig.setOverlayBlurEnabled(oldBlur)
            AppConfig.setDiscoverPreviewEnabled(oldPreview)
            caches.close()
        }
    }
}
