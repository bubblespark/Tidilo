package me.bubblespark.tidilo.data

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.ui.components.FrostedHeaderContainer
import me.bubblespark.tidilo.ui.components.LocalScreenBackdropState
import me.bubblespark.tidilo.ui.components.ScreenBackdropState
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.rememberScreenBackdropInput
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreenBackdropLifecycleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun concurrentScreensKeepOwnersAcrossTogglesAndDisposeIndependently(): Unit = runBlocking {
        var enabled by mutableStateOf(true)
        var second by mutableStateOf(true)
        val owners = arrayOfNulls<ScreenBackdropState>(2)
        val inputs = booleanArrayOf(false, false)
        var removed = 0
        var focused = false
        var draft by mutableStateOf("Retained draft")
        var scroll: ScrollState? = null
        val focus = FocusRequester()
        composeRule.setContent {
            TidiloTheme(blurEnabled = enabled) {
                @Composable
                fun Screen(index: Int) {
                    TidiloScreenScaffold(header = {
                        val owner = LocalScreenBackdropState.current
                        val input = rememberScreenBackdropInput()
                        SideEffect {
                            owners[index] = owner
                            inputs[index] = input != null
                        }
                        DisposableEffect(Unit) { onDispose { removed++ } }
                        FrostedHeaderContainer { Box(Modifier.size(20.dp)) }
                    }) {
                        if (index == 0) {
                            val state = rememberScrollState()
                            SideEffect { scroll = state }
                            Column(Modifier.height(200.dp).verticalScroll(state)) {
                                BasicTextField(
                                    draft,
                                    { draft = it },
                                    Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
                                )
                                Box(Modifier.height(1000.dp))
                            }
                        } else {
                            Box(Modifier.size(20.dp))
                        }
                    }
                }
                Screen(0)
                if (second) Screen(1)
            }
        }
        composeRule.waitUntil(5_000) {
            var ready = false
            composeRule.runOnUiThread { ready = owners[0] != null && owners[1] != null && inputs.all { it } }
            ready
        }
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

        fun awaitDemand(expected: Boolean) = composeRule.waitUntil(5_000) {
            var matches = false
            composeRule.runOnUiThread { matches = demand(first) == expected && demand(other) == expected }
            matches
        }

        awaitDemand(true)
        composeRule.runOnUiThread { focus.requestFocus() }
        composeRule.waitUntil(5_000) { focused }
        withContext(Dispatchers.Main) { requireNotNull(scroll).scrollTo(80) }
        composeRule.waitForIdle()
        assertTrue(focused)

        repeat(5) {
            composeRule.runOnUiThread { enabled = false }
            awaitDemand(false)
            composeRule.runOnUiThread { assertFalse(inputs.any { it }) }
            composeRule.runOnUiThread { enabled = true }
            awaitDemand(true)
            composeRule.runOnUiThread {
                assertSame(first, owners[0])
                assertSame(other, owners[1])
                assertEquals("Retained draft", draft)
                assertEquals(80, scroll?.value)
                assertTrue(focused)
            }
        }

        composeRule.runOnUiThread { second = false }
        composeRule.waitUntil(5_000) {
            var disposed = false
            composeRule.runOnUiThread { disposed = removed == 1 && !demand(other) }
            disposed
        }
        composeRule.runOnUiThread {
            assertEquals(1, removed)
            assertSame(first, owners[0])
            assertFalse(demand(other))
        }
    }
}
