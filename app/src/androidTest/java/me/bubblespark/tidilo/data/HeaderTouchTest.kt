package me.bubblespark.tidilo.data

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.components.TidiloFrostedHeader
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import org.junit.Assert.*
import org.junit.Test

/** Real pointer dispatch proves hit testing, including the overlapping lazy-list sibling. */
class HeaderTouchTest {
    @Test fun titleAndSubtitleClickWithHapticsButNavigationAndActionsStayIndependent(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
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
        try {
            host.main { activity.setContent { MaterialTheme {
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
            } } }
            for (label in listOf("Touch title", "Touch subtitle", "Action")) {
                val node = host.await(activity) { host.text(it, label) }
                val animation = scope.launch { scroll.animateScrollTo(9000, tween(10000)) }
                withTimeout(3000) { while (!scroll.isScrollInProgress) delay(10) }
                tap(host, activity, node.boundsInRoot.center.x, node.boundsInRoot.center.y)
                animation.cancel()
            }
            val back = host.await(activity) { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.common_back)) }
            tap(host, activity, back.boundsInRoot.center.x, back.boundsInRoot.center.y)
            assertEquals(2, titles)
            assertEquals(2, haptics)
            assertEquals(1, backs)
            assertEquals(1, actions)
            assertEquals("Title and icons must not activate content behind the header", 0, cards)
        } finally { scope.cancel(); host.main { activity.finish() } }
    }

    @Test fun headerBodyBlocksCardsButFadeRemainsInteractive(): Unit = runBlocking {
        verifyHeader { host, activity, header, density, _, clicks ->
            tap(host, activity, 4f * density, header.center.y)
            assertEquals("Header padding must not activate the card beneath it", 0, clicks())
            tap(host, activity, header.center.x, 4f * density)
            tap(host, activity, header.center.x, header.center.y)
            assertEquals("Status-bar padding and inactive title must block cards", 0, clicks())
            tap(host, activity, header.center.x, header.bottom - 9f * density)
            assertEquals("Dissolve spacer must still pass taps to content", 1, clicks())
        }
    }

    @Test fun verticalDragStartingOnHeaderStillScrollsList(): Unit = runBlocking {
        verifyHeader(titleAction = true) { host, activity, header, density, state, clicks ->
            val before = state.firstVisibleItemIndex * 10000 + state.firstVisibleItemScrollOffset
            val start = SystemClock.uptimeMillis()
            val y = header.bottom - 24f * density
            event(host, activity, start, MotionEvent.ACTION_DOWN, header.center.x, y)
            repeat(12) { step ->
                SystemClock.sleep(16)
                event(host, activity, start, MotionEvent.ACTION_MOVE, header.center.x, y + (step + 1) * 12f * density)
            }
            event(host, activity, start, MotionEvent.ACTION_UP, header.center.x, y + 144f * density)
            host.instrumentation.waitForIdleSync()
            val after = state.firstVisibleItemIndex * 10000 + state.firstVisibleItemScrollOffset
            assertTrue("Header-origin drag must reach the underlying lazy list", after < before)
            assertEquals(0, clicks())
        }
    }

    private suspend fun verifyHeader(titleAction: Boolean = false, block: (HeaderTestHost, androidx.activity.ComponentActivity, Rect, Float, LazyListState, () -> Int) -> Unit) {
        val host = HeaderTestHost()
        val activity = host.launch()
        var header = Rect.Zero
        var density = 1f
        var clicks = 0
        var titles = 0
        lateinit var state: LazyListState
        try {
            host.main {
                activity.setContent { MaterialTheme {
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
                            modifier = Modifier.onGloballyPositioned { header = it.boundsInWindow() }
                        )
                    }
                } }
            }
            host.await(activity) { host.text(it, "Header") }
            host.instrumentation.waitForIdleSync()
            assertTrue(header.height > 0)
            block(host, activity, header, density, state) { clicks }
            assertEquals("Dragging the title must not invoke its tap action", 0, titles)
        } finally { host.main { activity.finish() } }
    }

    private fun tap(host: HeaderTestHost, activity: androidx.activity.ComponentActivity, x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        event(host, activity, start, MotionEvent.ACTION_DOWN, x, y)
        event(host, activity, start, MotionEvent.ACTION_UP, x, y)
        host.instrumentation.waitForIdleSync()
    }

    private fun event(host: HeaderTestHost, activity: androidx.activity.ComponentActivity, start: Long, action: Int, x: Float, y: Float) {
        host.main {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
