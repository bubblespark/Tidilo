package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChromePerformanceNavigationTest {
    @Test fun navigationMeasuresOverlappingOutgoingAndIncomingDestinations() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var detail by mutableStateOf(false)
        val active = mutableSetOf<String>()
        var overlap = false
        try {
            host.main { activity.setContent {
                @Composable fun Destination(name: String) {
                    DisposableEffect(name) {
                        active.add(name)
                        overlap = overlap || active.size == 2
                        onDispose { active.remove(name) }
                    }
                    Box(Modifier.size(200.dp))
                }
                ChromePerformanceNavigation(detail, { Destination("library") }, { Destination("detail") })
            } }
            host.awaitMain("Library must compose") { active == setOf("library") }
            host.main { detail = true }
            host.awaitMain("Navigate must overlap and settle") { overlap && active == setOf("detail") }
            host.main { overlap = false; detail = false }
            host.awaitMain("Pop must overlap and settle") { overlap && active == setOf("library") }
        } finally { host.main { activity.finish() } }
    }

    @Test fun benchmarkTapDeliversPressReleaseAndClick() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var presses = 0
        var releases = 0
        var clicks = 0
        var listening = false
        try {
            host.main { activity.setContent {
                val interaction = remember { MutableInteractionSource() }
                LaunchedEffect(interaction) { listening = true; interaction.interactions.collect {
                    if (it is PressInteraction.Press) presses++
                    if (it is PressInteraction.Release) releases++
                } }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(100.dp).testTag("benchmark-tap").clickable(interaction, indication = null) { clicks++ })
                }
            } }
            val node = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "benchmark-tap" }
            // Semantics can exist before the replacement content reaches the input window.
            host.instrumentation.waitForIdleSync()
            host.awaitMain("Input window and interaction collector must be ready") {
                listening && activity.hasWindowFocus() && node.layoutInfo.coordinates.isAttached
            }
            host.chromeTap(node)
            host.awaitMain("Tap must deliver press, release and click") { clicks == 1 && presses == 1 && releases == 1 }
        } finally { host.main { activity.finish() } }
    }
}

private suspend fun HeaderTestHost.awaitMain(message: String, condition: () -> Boolean) {
    try {
        withTimeout(5000) {
            var ready = false
            while (!ready) {
                main { ready = condition() }
                if (!ready) delay(30)
            }
        }
    } catch (failure: TimeoutCancellationException) { throw AssertionError(message, failure) }
}
