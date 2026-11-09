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
            delay(200)
            host.main { detail = true }
            delay(1200)
            host.main { assertTrue("Navigate must render both destinations during the slide", overlap); overlap = false; detail = false }
            delay(1200)
            host.main { assertTrue("Pop must render both destinations during the slide", overlap) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun benchmarkTapDeliversPressReleaseAndClick() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var presses = 0
        var releases = 0
        var clicks = 0
        try {
            host.main { activity.setContent {
                val interaction = remember { MutableInteractionSource() }
                LaunchedEffect(interaction) { interaction.interactions.collect {
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
            delay(400)
            host.chromeTap(node)
            delay(200)
            host.main { assertEquals("clicks", 1, clicks); assertEquals("presses", 1, presses); assertEquals("releases", 1, releases) }
        } finally { host.main { activity.finish() } }
    }
}
