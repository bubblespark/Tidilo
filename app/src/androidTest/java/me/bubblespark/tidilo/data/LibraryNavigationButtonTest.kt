package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.library.LibraryNavigationButton
import org.junit.Assert.*
import org.junit.Test

class LibraryNavigationButtonTest {
    @Test fun crossfadeKeepsOneCurrentActionAndFixedGeometry() = verifyButton(false)
    @Test fun rtlLargeFontAndDarkAppearanceKeepNavigationStable() = verifyButton(true)

    @Test fun disabledMotionSettlesImmediately() = verifyButton(false, 0f)

    private fun verifyButton(rtl: Boolean, durationScale: Float = 1f): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val drillDown = mutableStateOf(false)
        val clock = BroadcastFrameClock()
        val job = SupervisorJob()
        val scale = object : MotionDurationScale { override val scaleFactor = durationScale }
        val scope = CoroutineScope(job + Dispatchers.Main.immediate + clock + scale)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val menuLabel = host.context.getString(R.string.navigation_open)
        val backLabel = host.context.getString(R.string.library_back_to_domains)
        var menuClicks = 0
        var upClicks = 0
        var time = 0L
        suspend fun frame(delta: Long = 16) {
            time += delta * 1_000_000
            host.main { clock.sendFrame(time) }
            host.instrumentation.waitForIdleSync()
            delay(20)
        }
        fun assertAction(up: Boolean) {
            val nodes = host.nodes(activity)
            val actions = nodes.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals("Only one navigation action may exist, including during overlap", 1, actions.size)
            assertTrue(host.description(actions.single(), if (up) backLabel else menuLabel))
            assertEquals(1, nodes.count { host.description(it, if (up) backLabel else menuLabel) })
            assertFalse(nodes.any { host.description(it, if (up) menuLabel else backLabel) })
            assertTrue(checkNotNull(actions.single().config.getOrNull(SemanticsActions.OnClick)?.action) {
                "The current navigation button must expose an invokable Click action"
            }.invoke())
        }
        fun glyphPixels(): IntArray {
            val button = host.nodes(activity).single { it.config.getOrNull(SemanticsActions.OnClick) != null }
            val bounds = button.boundsInWindow
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            return try {
                view.draw(Canvas(bitmap))
                val width = bounds.width.toInt()
                val height = bounds.height.toInt()
                IntArray(width * height).also {
                    bitmap.getPixels(it, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height)
                }
            } finally { bitmap.recycle() }
        }
        try {
            host.main {
                activity.setContent(parent = recomposer) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(density, if (rtl) 2f else 1f)
                    ) {
                        MaterialTheme(colorScheme = if (rtl) darkColorScheme() else lightColorScheme()) {
                            Surface { Row(Modifier.statusBarsPadding().size(320.dp, 80.dp)) {
                                LibraryNavigationButton(drillDown.value, backLabel, { menuClicks++ }, { upClicks++ },
                                    modifier = Modifier.testTag("library-navigation-button"))
                                Text("Navigation title", Modifier.testTag("navigation-title"))
                            } }
                        }
                    }
                }
            }
            repeat(3) { frame() }
            val initial = host.await(activity) {
                host.description(it, menuLabel) &&
                    it.config.getOrNull(SemanticsProperties.TestTag) == "library-navigation-button"
            }
            val title = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "navigation-title" }
            val initialBounds = initial.boundsInRoot
            val initialTitleX = title.boundsInRoot.left
            val density = activity.resources.displayMetrics.density
            assertEquals(48f * density, initialBounds.width, 1f)
            assertEquals(48f * density, initialBounds.height, 1f)
            lateinit var menuPixels: IntArray
            host.main { assertAction(false); menuPixels = glyphPixels(); drillDown.value = true }
            frame()
            frame()
            frame(110)
            lateinit var middlePixels: IntArray
            host.main { assertAction(true); middlePixels = glyphPixels() }
            frame(240)
            lateinit var backPixels: IntArray
            host.main {
                backPixels = glyphPixels()
                val button = host.nodes(activity).single { it.config.getOrNull(SemanticsActions.OnClick) != null }
                val currentTitle = host.nodes(activity).single { it.config.getOrNull(SemanticsProperties.TestTag) == "navigation-title" }
                assertEquals(initialBounds.left, button.boundsInRoot.left, 1f)
                assertEquals(initialBounds.width, button.boundsInRoot.width, 1f)
                assertEquals(initialTitleX, currentTitle.boundsInRoot.left, 1f)
                assertFalse("The midpoint must paint an intermediate glyph", middlePixels.contentEquals(menuPixels))
                if (durationScale == 0f) {
                    assertTrue("Disabled motion must settle immediately", middlePixels.contentEquals(backPixels))
                } else {
                    assertFalse("The midpoint must not snap straight to Up", middlePixels.contentEquals(backPixels))
                }
                assertFalse(menuPixels.contentEquals(backPixels))
                drillDown.value = false
            }
            frame()
            frame()
            frame(110)
            host.main { assertAction(false); drillDown.value = true }
            frame()
            frame()
            host.main { assertAction(true); drillDown.value = false }
            frame()
            frame()
            host.main { assertAction(false) }
            frame(300)
            host.main {
                assertTrue("Reversal must settle back to the original Menu", menuPixels.contentEquals(glyphPixels()))
                assertEquals(3, menuClicks)
                assertEquals(2, upClicks)
            }
            host.capture(if (rtl) "navigation-rtl-large-dark" else "navigation-ltr-light")
        } finally {
            host.main { activity.finish() }
            recomposer.cancel()
            job.cancelAndJoin()
        }
    }
}
