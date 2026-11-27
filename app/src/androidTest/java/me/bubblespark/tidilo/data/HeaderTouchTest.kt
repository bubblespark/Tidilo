package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real pointer dispatch proves hit testing, including the overlapping lazy-list sibling. */
class HeaderTouchTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun mount(content: @Composable () -> Unit) {
        // MainActivity draws edge to edge; the header blocks the status-bar padding above its row.
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { content() } }
    }

    private fun tap(x: Float, y: Float) {
        composeRule.onRoot().performTouchInput { click(Offset(x, y)) }
        composeRule.waitForIdle()
    }

    @Test fun titleAndSubtitleClickWithHapticsButNavigationAndActionsStayIndependent() {
        var titles = 0
        var backs = 0
        var actions = 0
        var haptics = 0
        var cards = 0
        val scroll = ScrollState(0)
        lateinit var scope: CoroutineScope
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics++ }
        }
        mount {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        Box(Modifier.fillMaxWidth().height(10000.dp).clickable { cards++ })
                    }
                    TidiloPageHeader("Touch title", { backs++ }, subtitle = { Text("Touch subtitle") },
                        scrollState = scroll,
                        onTitleClick = { titles++ }, actions = {
                            IconButton({ actions++ }) { Text("Action") }
                        })
                }
            }
        }
        for (label in listOf("Touch title", "Touch subtitle", "Action")) {
            val center = composeRule.awaitNode(hasText(label)).boundsInRoot.center
            // Auto-advancing virtual time would finish the 10 s scroll before the tap lands.
            composeRule.mainClock.autoAdvance = false
            try {
                lateinit var animation: kotlinx.coroutines.Job
                composeRule.runOnUiThread { animation = scope.launch { scroll.animateScrollTo(9000, tween(10000)) } }
                repeat(10) { if (!scroll.isScrollInProgress) composeRule.mainClock.advanceTimeByFrame() }
                assertTrue("The tap must land while the content is scrolling", scroll.isScrollInProgress)
                composeRule.onRoot().performTouchInput { click(center) }
                animation.cancel()
                composeRule.mainClock.advanceTimeByFrame()
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
        }
        val back = composeRule.awaitNode(hasContentDescription(composeRule.activity.getString(R.string.common_back))).boundsInRoot
        tap(back.center.x, back.center.y)
        assertEquals(2, titles)
        assertEquals(2, haptics)
        assertEquals(1, backs)
        assertEquals(1, actions)
        assertEquals("Title and icons must not activate content behind the header", 0, cards)
    }

    @Test fun headerBodyBlocksCardsButFadeRemainsInteractive() {
        verifyHeader { header, density, _, clicks ->
            tap(4f * density, header.center.y)
            assertEquals("Header padding must not activate the card beneath it", 0, clicks())
            tap(header.center.x, 4f * density)
            tap(header.center.x, header.center.y)
            assertEquals("Status-bar padding and inactive title must block cards", 0, clicks())
            tap(header.center.x, header.bottom - 9f * density)
            assertEquals("Dissolve spacer must still pass taps to content", 1, clicks())
        }
    }

    @Test fun verticalDragStartingOnHeaderStillScrollsList() {
        verifyHeader(titleAction = true) { header, density, state, clicks ->
            val before = state.firstVisibleItemIndex * 10000 + state.firstVisibleItemScrollOffset
            val y = header.bottom - 24f * density
            composeRule.onRoot().performTouchInput {
                down(Offset(header.center.x, y))
                repeat(12) { moveBy(Offset(0f, 12f * density)) }
                up()
            }
            composeRule.waitForIdle()
            val after = state.firstVisibleItemIndex * 10000 + state.firstVisibleItemScrollOffset
            assertTrue("Header-origin drag must reach the underlying lazy list", after < before)
            assertEquals(0, clicks())
        }
    }

    private fun verifyHeader(titleAction: Boolean = false, block: (Rect, Float, LazyListState, () -> Int) -> Unit) {
        var header = Rect.Zero
        var density = 1f
        var clicks = 0
        var titles = 0
        lateinit var state: LazyListState
        mount {
            density = LocalDensity.current.density
            state = rememberLazyListState(10)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(100) { index ->
                        Box(Modifier.fillMaxWidth().height(64.dp).clickable { clicks++ }) { Text("Card $index") }
                    }
                }
                TidiloFrostedHeader(
                    title = { Text("Header") },
                    scrollState = state,
                    onTitleClick = if (titleAction) ({ titles++ }) else null,
                    modifier = Modifier.onGloballyPositioned { header = it.boundsInRoot() }
                )
            }
        }
        composeRule.awaitNode(hasText("Header"))
        composeRule.waitForIdle()
        assertTrue(header.height > 0)
        block(header, density, state) { clicks }
        assertEquals("Dragging the title must not invoke its tap action", 0, titles)
    }
}
