package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.settings.*
import org.junit.Assert.*
import org.junit.Test

class SettingsHeaderTest {
    @Test fun sharedSettingsHeaderKeepsDebugSubtitleAndResetDialog(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme { SettingsDebugScreen(onNavigateBack = {}) } } }
            host.await(activity) { host.text(it, host.context.getString(R.string.settings_debug_subtitle)) }
            host.main { activity.setContent { MaterialTheme { GenerateContentRequestScreen(onNavigateBack = {}) } } }
            val reset = host.await(activity) { host.description(it, host.context.getString(R.string.gcr_reset_default_content_desc)) }
            host.main {
                var node = reset
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val label = host.context.getString(R.string.gcr_reset_dialog_title)
            fun containsTitle(node: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                node.text?.toString() == label || (0 until node.childCount).any { node.getChild(it)?.let(::containsTitle) == true }
            withTimeout(5000) {
                while (true) {
                    host.instrumentation.uiAutomation.clearCache()
                    if (host.instrumentation.uiAutomation.rootInActiveWindow?.let(::containsTitle) == true) break
                    delay(30)
                }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun settingsHeaderStaysFixed() = verify { back -> SettingsScreen(onNavigateBack = back, onNavigateToAiAnalysis = {}) }
    @Test fun aiAnalysisHeaderStaysFixed() = verify { back -> AiAnalysisScreen(onNavigateBack = back, onNavigateToGenerateContentRequest = {}, onNavigateToNsfwContents = {}, checkAiCoreAvailable = { true }) }
    @Test fun nsfwHeaderStaysFixed() = verify { back -> NsfwSettingsScreen(onNavigateBack = back) }
    @Test fun debugHeaderStaysFixed() = verify { back -> SettingsDebugScreen(onNavigateBack = back) }
    @Test fun generationHeaderStaysFixed() = verify { back -> GenerateContentRequestScreen(onNavigateBack = back) }
    @Test fun storageHeaderStaysFixed() = verify { back -> StorageScreen(onNavigateBack = back) }
    @Test fun rssHeaderStaysFixed() = verify { back -> RssSettingsScreen(onNavigateBack = back) }

    private fun verify(content: @Composable (() -> Unit) -> Unit): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var returned = false
        val backLabel = host.context.getString(R.string.common_back)
        try {
            host.main {
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalBarsVisible provides false) {
                            Box(Modifier.size(360.dp, 350.dp)) { content { returned = true } }
                        }
                    }
                }
            }
            val before = host.await(activity) { host.description(it, backLabel) }.boundsInRoot
            delay(300)
            host.scroll(activity, 2400f)
            delay(450)
            val after = host.await(activity) { host.description(it, backLabel) }
            assertEquals("Header remains fixed when body scrolls", before.top, after.boundsInRoot.top, 1f)
            host.capture("settings-fixed")
            host.main {
                var node = after
                while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue("Existing Back callback is preserved", returned)
        } finally { host.main { activity.finish() } }
    }
}
