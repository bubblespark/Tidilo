package me.bubblespark.tidilo.data

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
import me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real `MainActivity` routes into and out of RSS settings, with overlay blur off. */
class RssSettingsNavigationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun noTextStartsWith(prefix: String) = composeRule.onAllNodes(
        SemanticsMatcher("text starts with $prefix") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
        },
        useUnmergedTree = true
    ).fetchSemanticsNodes().isEmpty()

    /** Launches the app with overlay blur off and offline caches, closing everything afterwards. */
    private fun withApp(block: suspend (ActivityScenario<MainActivity>) -> Unit) = runBlocking {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            AppConfig.setOverlayBlurEnabled(false)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            block(scenario)
        } finally {
            try {
                scenario?.close()
            } finally {
                caches.close()
                AppConfig.setOverlayBlurEnabled(oldBlur)
            }
        }
    }

    @Test fun settingsRssNavigationAndBack() {
        val prefs = context.getSharedPreferences(NOTIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
        val hadRequestedKey = prefs.contains(KEY_NOTIFICATION_PERMISSION_REQUESTED)
        val previousRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        assertTrue(prefs.edit().remove(KEY_NOTIFICATION_PERMISSION_REQUESTED).commit())
        try {
            withApp { scenario ->
                val openNavigation = context.getString(R.string.navigation_open)
                composeRule.awaitNode(hasContentDescription(openNavigation))
                assertFalse(
                    "Cold launch must not request or mark notification permission as requested",
                    prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                )
                composeRule.clickNode(composeRule.awaitNode(hasContentDescription(openNavigation)))
                composeRule.clickNode(composeRule.awaitNode(hasText(context.getString(R.string.title_settings))))
                val language = context.getString(R.string.settings_language_title)
                val rssEntry = context.getString(R.string.rss_subtitle)
                composeRule.awaitNode(hasText(language))
                composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasText(language)))
                    .performScrollToNode(hasText(rssEntry))
                composeRule.clickNode(composeRule.awaitNode(hasText(rssEntry)))
                composeRule.awaitNode(hasText(context.getString(R.string.rss_title)))
                for (label in listOf(context.getString(R.string.rss_schedule), "Daily fetch time", "Fetch only over Wi-Fi")) {
                    composeRule.revealRssRow(hasText(label))
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                composeRule.awaitNode(hasText(rssEntry))
            }
        } finally {
            runBlocking {
                withContext(NonCancellable + Dispatchers.IO) {
                    val restore = prefs.edit()
                    if (hadRequestedKey) restore.putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, previousRequested)
                    else restore.remove(KEY_NOTIFICATION_PERMISSION_REQUESTED)
                    assertTrue(restore.commit())
                }
            }
        }
    }

    @Test fun importShowsErrorsWithoutAiWaitingSections() = withApp { scenario ->
        val openNavigation = hasContentDescription(context.getString(R.string.navigation_open))
        composeRule.awaitNode(openNavigation)
        // Delivered to the running app: under the test clock a cold-start destination is navigated before the graph is set.
        // A copy of the launch intent keeps the identity ActivityScenario tracks across setIntent().
        scenario.onActivity {
            val intent = Intent(it.intent).putExtra(NotificationCoordinator.EXTRA_DESTINATION, "import")
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, intent)
        }
        composeRule.awaitNode(hasText(context.getString(R.string.settings_notification_title)))
        assertTrue(noTextStartsWith("Ready - RSS ("))
        assertTrue(noTextStartsWith("Ready ("))
    }
}
