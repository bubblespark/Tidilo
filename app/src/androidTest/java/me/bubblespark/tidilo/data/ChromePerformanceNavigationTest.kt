package me.bubblespark.tidilo.data

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ChromePerformanceNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun navigationMeasuresOverlappingOutgoingAndIncomingDestinations() {
        var detail by mutableStateOf(false)
        val active = mutableSetOf<String>()
        var overlap = false
        composeRule.setContent {
            @Composable
            fun Destination(name: String) {
                DisposableEffect(name) {
                    active.add(name)
                    overlap = overlap || active.size == 2
                    onDispose { active.remove(name) }
                }
                Box(Modifier.size(200.dp))
            }
            ChromePerformanceNavigation(detail, { Destination("library") }, { Destination("detail") })
        }
        composeRule.waitUntil(5_000) {
            var ready = false
            composeRule.runOnUiThread { ready = active == setOf("library") }
            ready
        }
        composeRule.runOnUiThread { detail = true }
        composeRule.waitUntil(5_000) {
            var settled = false
            composeRule.runOnUiThread { settled = overlap && active == setOf("detail") }
            settled
        }
        composeRule.runOnUiThread {
            overlap = false
            detail = false
        }
        composeRule.waitUntil(5_000) {
            var settled = false
            composeRule.runOnUiThread { settled = overlap && active == setOf("library") }
            settled
        }
    }

    @Test
    fun benchmarkTapDeliversPressReleaseAndClick() {
        var presses = 0
        var releases = 0
        var clicks = 0
        var listening = false
        composeRule.setContent {
            val interaction = remember { MutableInteractionSource() }
            LaunchedEffect(interaction) {
                listening = true
                interaction.interactions.collect {
                    if (it is PressInteraction.Press) presses++
                    if (it is PressInteraction.Release) releases++
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(100.dp).testTag("benchmark-tap").clickable(interaction, indication = null) { clicks++ })
            }
        }
        val node = composeRule.awaitNode(hasTestTag("benchmark-tap"))
        // Semantics can exist before the replacement content reaches the input window.
        composeRule.waitUntil(5_000) {
            var ready = false
            composeRule.runOnUiThread { ready = listening && node.layoutInfo.coordinates.isAttached }
            ready
        }
        composeRule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().chromeTap(node)
        composeRule.waitUntil(5_000) {
            var completed = false
            composeRule.runOnUiThread { completed = clicks == 1 && presses == 1 && releases == 1 }
            completed
        }
    }
}
