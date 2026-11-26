package me.bubblespark.tidilo.data

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.library.EntityLibraryState
import me.bubblespark.tidilo.ui.screens.library.rememberEntityLibraryState
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EntityLibraryLifetimeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun lookupCollectsOnlyWhileNeededAndReopensLoadingWithFreshSnapshot(): Unit = runBlocking {
        val needed = mutableStateOf(false)
        val retry = mutableStateOf(0)
        val input = mutableStateOf("")
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val snapshots = Channel<EntityLibraryState>(Channel.UNLIMITED)
        val rendered = AtomicReference<Pair<Boolean, EntityLibraryState>>()
        try {
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    val source = remember(retry.value) {
                        flow {
                            starts.incrementAndGet()
                            try {
                                emit(snapshots.receive())
                                awaitCancellation()
                            } finally { stops.incrementAndGet() }
                        }
                    }
                    val state = rememberEntityLibraryState(needed.value, source)
                    val visible = needed.value
                    Text(input.value)
                    SideEffect { rendered.set(visible to state) }
                }
            }
            await { rendered.get()?.first == false }
            composeRule.waitForIdle()
            assertEquals("Detail and parent editor do not need the aggregate", 0, starts.get())

            composeRule.runOnUiThread { needed.value = true }
            await { starts.get() == 1 && rendered.get()?.first == true }
            assertTrue(rendered.get().second.loading)
            snapshots.send(EntityLibraryState(mapOf("tag" to "Old tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Old tag" }
            composeRule.runOnUiThread { input.value = "Typing does not restart lookup" }
            composeRule.waitForIdle()
            assertEquals(1, starts.get())

            composeRule.runOnUiThread { needed.value = false }
            await { rendered.get()?.first == false && stops.get() == 1 }
            composeRule.runOnUiThread { needed.value = true }
            await { starts.get() == 2 && rendered.get()?.first == true }
            assertTrue("Reopen must gate confirmation until a fresh snapshot", rendered.get().second.loading)
            assertTrue(rendered.get().second.spellings.isEmpty())
            snapshots.send(EntityLibraryState(mapOf("tag" to "Fresh tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Fresh tag" }

            composeRule.runOnUiThread { retry.value++ }
            await { starts.get() == 3 && stops.get() == 2 }
            assertTrue("Retry must discard the previous ready snapshot", rendered.get().second.loading)
            assertTrue(rendered.get().second.spellings.isEmpty())
            snapshots.send(EntityLibraryState(loading = false, error = IllegalStateException("Fixture")))
            await { rendered.get()?.second?.error != null }
            composeRule.runOnUiThread { retry.value++ }
            await { starts.get() == 4 && stops.get() == 3 }
            assertTrue(rendered.get().second.loading)
            assertNull(rendered.get().second.error)
            snapshots.send(EntityLibraryState(mapOf("tag" to "Recovered tag"), loading = false))
            await { rendered.get()?.second?.spellings?.get("tag") == "Recovered tag" }
            composeRule.runOnUiThread { needed.value = false }
            await { stops.get() == 4 }
        } finally {
            // The rule disposes the composition, which cancels any remaining lookup.
            snapshots.close()
        }
    }

    private fun await(predicate: () -> Boolean) = composeRule.waitUntil(8_000, predicate)
}
