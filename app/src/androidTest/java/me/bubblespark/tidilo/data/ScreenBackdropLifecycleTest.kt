package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.components.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class ScreenBackdropLifecycleTest {
    @Test fun concurrentScreensKeepOwnersAcrossTogglesAndDisposeIndependently(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var enabled by mutableStateOf(true)
        var second by mutableStateOf(true)
        val owners = arrayOfNulls<ScreenBackdropState>(2)
        val inputs = booleanArrayOf(false, false)
        var removed = 0
        var focused = false
        var draft by mutableStateOf("Retained draft")
        var scroll: androidx.compose.foundation.ScrollState? = null
        val focus = FocusRequester()
        try {
            host.main { activity.setContent { TidiloTheme(blurEnabled = enabled) {
                @Composable fun Screen(index: Int) {
                    TidiloScreenScaffold(header = {
                        val owner = LocalScreenBackdropState.current
                        val input = rememberScreenBackdropInput()
                        SideEffect { owners[index] = owner; inputs[index] = input != null }
                        DisposableEffect(Unit) { onDispose { removed++ } }
                        FrostedHeaderContainer { Box(Modifier.size(20.dp)) }
                    }) {
                        if (index == 0) {
                            val state = rememberScrollState()
                            SideEffect { scroll = state }
                            Column(Modifier.height(200.dp).verticalScroll(state)) {
                                BasicTextField(draft, { draft = it }, Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused })
                                Box(Modifier.height(1000.dp))
                            }
                        } else Box(Modifier.size(20.dp))
                    }
                }
                Screen(0)
                if (second) Screen(1)
            } } }
            delay(400)
            val first = requireNotNull(owners[0])
            val other = requireNotNull(owners[1])
            assertNotSame(first, other)
            assertTrue(inputs.all { it })
            // Test-only inspection of pinned Haze demand; no production reflection is added.
            fun demand(owner: ScreenBackdropState): Boolean {
                val areas = owner.haze.javaClass.methods.single { it.name.startsWith("getAreas") }.invoke(owner.haze) as List<*>
                val area = areas.filterNotNull().firstOrNull { candidate ->
                    candidate.javaClass.methods.single { it.name.startsWith("getKey") }.invoke(candidate) === owner.sourceKey
                } ?: return false
                return area.javaClass.methods.single { it.name.startsWith("getHasCaptureDemand") }.invoke(area) as Boolean
            }
            suspend fun awaitDemand(expected: Boolean) = withTimeout(5000) {
                var matches = false
                while (!matches) {
                    host.main { matches = demand(first) == expected && demand(other) == expected }
                    if (!matches) delay(30)
                }
            }
            awaitDemand(true)
            host.main { focus.requestFocus() }
            requireNotNull(scroll).scrollTo(80)
            assertTrue(focused)
            repeat(5) {
                host.main { enabled = false }
                awaitDemand(false)
                assertFalse(inputs.any { it })
                host.main { enabled = true }
                awaitDemand(true)
                assertSame(first, owners[0]); assertSame(other, owners[1])
                assertEquals("Retained draft", draft)
                assertEquals(80, scroll?.value)
                assertTrue(focused)
            }
            host.main { second = false }
            delay(200)
            assertEquals(1, removed)
            assertSame(first, owners[0])
            host.main { assertFalse(demand(other)) }
        } finally { host.main { activity.finish() } }
    }
}
