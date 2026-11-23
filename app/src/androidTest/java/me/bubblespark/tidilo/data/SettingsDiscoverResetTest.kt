package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Data Management › Reset rediscovery history (Discover preview flag only). */
class SettingsDiscoverResetTest {
    private val host = HeaderTestHost()

    private fun tag(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.TestTag) == "discover_reset_history"

    @Test fun resetEntryFollowsTheFlagAndAsksFirst(): Unit = runBlocking {
        val previous = AppConfig.discoverPreviewEnabled.value
        val resets = AtomicInteger()
        val activity = host.launch()
        try {
            AppConfig.setDiscoverPreviewEnabled(false)
            host.main { activity.setContent { TidiloTheme { SettingsScreen({}, {}, onResetDiscoverHistory = { resets.incrementAndGet() }) } } }
            host.await(activity) { host.text(it, "Data Management") }
            host.main { assertTrue(host.nodes(activity).none(::tag)) }

            AppConfig.setDiscoverPreviewEnabled(true)
            val row = host.await(activity, ::tag)
            host.main { row.config[SemanticsActions.OnClick].action?.invoke() }
            host.await(activity) { host.text(it, "Reset rediscovery history?") }
            host.capture("settings-discover-reset-dialog")
            click(activity, "Cancel")
            host.awaitAbsent(activity) { host.text(it, "Reset rediscovery history?") }
            assertEquals(0, resets.get())

            host.main { host.nodes(activity).first(::tag).config[SemanticsActions.OnClick].action?.invoke() }
            click(activity, "Reset")
            repeat(60) { if (resets.get() == 1) return@repeat; delay(50) }
            assertEquals(1, resets.get())
        } finally {
            AppConfig.setDiscoverPreviewEnabled(previous)
            activity.finish()
        }
    }

    private suspend fun click(activity: androidx.activity.ComponentActivity, label: String) {
        val button = findButton(activity, label)
        host.main { button.config[SemanticsActions.OnClick].action?.invoke() }
    }

    /** Buttons merge their child text, so search the merged tree. */
    private suspend fun findButton(activity: androidx.activity.ComponentActivity, label: String): SemanticsNode {
        repeat(160) {
            var found: SemanticsNode? = null
            host.main {
                found = host.nodes(activity, useUnmergedTree = false).firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick) != null && host.text(it, label)
                }
            }
            found?.let { return it }
            delay(50)
        }
        error("No button $label")
    }
}
