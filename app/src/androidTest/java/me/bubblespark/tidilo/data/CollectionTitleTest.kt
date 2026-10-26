package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.DomainGroupItem
import me.bubblespark.tidilo.ui.screens.library.LibraryDeckCard
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** Reads real Compose text layout so an ellipsized accessibility label cannot pass as a full title. */
class CollectionTitleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun roots(view: View): List<ViewRootForTest> = when (view) {
        is ViewRootForTest -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun find(node: SemanticsNode, text: String): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) return node
        return node.children.firstNotNullOfOrNull { find(it, text) }
    }

    private suspend fun layout(activity: ComponentActivity, text: String): TextLayoutResult = withTimeout(5000) {
        var result: TextLayoutResult? = null
        while (result == null) {
            instrumentation.runOnMainSync {
                val node = roots(activity.window.decorView).firstNotNullOfOrNull {
                    find(it.semanticsOwner.unmergedRootSemanticsNode, text)
                }
                val layouts = mutableListOf<TextLayoutResult>()
                node?.config?.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                result = layouts.firstOrNull()
            }
            if (result == null) delay(30)
        }
        requireNotNull(result)
    }

    private fun titleBounds(activity: ComponentActivity, text: String): Rect {
        var bounds = Rect()
        instrumentation.runOnMainSync {
            val node = roots(activity.window.decorView).firstNotNullOfOrNull {
                find(it.semanticsOwner.unmergedRootSemanticsNode, text)
            }
            val area = requireNotNull(node).boundsInWindow
            bounds = Rect(area.left.roundToInt(), area.top.roundToInt(), area.right.roundToInt(), area.bottom.roundToInt())
        }
        return bounds
    }

    private fun screenshot(bounds: Rect): Bitmap {
        val screen = instrumentation.uiAutomation.takeScreenshot()
        return try {
            Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
        } finally {
            screen.recycle()
        }
    }

    @Test fun sharedDeckFitsLongJapaneseNamesAtAllScaleBoundaries(): Unit = runBlocking {
        val title = mutableStateOf("IGN")
        val fontScale = mutableStateOf(1f)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                        MaterialTheme {
                            LibraryDeckCard(DomainGroupItem("example.com", title.value, 3), Color.Blue, {},
                                Modifier.width(166.dp)) { }
                        }
                    }
                }
            }
            for ((length, size) in listOf(10 to 16f, 11 to 14.5f, 18 to 14.5f, 19 to 13.5f, 24 to 13.5f)) {
                val value = "あ".repeat(length)
                instrumentation.runOnMainSync { title.value = value }
                val rendered = layout(activity, value)
                assertFalse("$length characters must fit the deck", rendered.hasVisualOverflow)
                assertTrue("Deck title must use at most three lines", rendered.lineCount <= 3)
                assertEquals(size, rendered.layoutInput.style.fontSize.value, 0.01f)
            }
            val example = "ニッチなPCゲーマーの環境構築Z"
            instrumentation.runOnMainSync { title.value = example; fontScale.value = 1.3f }
            assertFalse("Issue example must fit with enlarged text", layout(activity, example).hasVisualOverflow)
            val domain = titleBounds(activity, "example.com")
            val count = titleBounds(activity, context.getString(me.bubblespark.tidilo.R.string.domain_card_item_count, 3))
            assertTrue("Enlarged title must leave room for the count badge", domain.bottom <= count.top)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun rssDrillDownScalesAtAllBoundariesWithoutChangingCollectionHeader(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var vm: LibraryViewModel
        var oldMode = LibraryFilterMode.NEWEST_FIRST
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.selectRssFeedFilter(null)
                activity.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { LibraryScreen(vm) } } }
            }
            for ((length, size) in listOf(6 to 28f, 7 to 23f, 9 to 23f, 10 to 20f, 13 to 20f, 14 to 17f, 40 to 17f)) {
                val value = "あ".repeat(length)
                instrumentation.runOnMainSync {
                    vm.selectRssFeedFilter(RssFeedEntity("title-test", "https://title.invalid/rss", value, enabled = false))
                }
                val rendered = layout(activity, value)
                assertEquals(size, rendered.layoutInput.style.fontSize.value, 0.01f)
                assertEquals(1, rendered.lineCount)
                assertFalse("Full title must be laid out for marquee", rendered.isLineEllipsized(0))
                delay(300)
            }
            instrumentation.runOnMainSync { vm.selectRssFeedFilter(null) }
            val rendered = layout(activity, context.getString(me.bubblespark.tidilo.R.string.rss_title))
            assertEquals(28f, rendered.layoutInput.style.fontSize.value, 0.01f)

            // Exercise both actual drill-down routes: the title stays still initially, then moves.
            for (rss in listOf(true, false)) {
                val value = if (rss) "A very long RSS collection title for delayed scrolling"
                    else "a-very-long-domain-name-for-scrolling.invalid"
                instrumentation.runOnMainSync {
                    vm.setFilterMode(if (rss) LibraryFilterMode.RSS else LibraryFilterMode.DOMAIN)
                    if (rss) vm.selectRssFeedFilter(RssFeedEntity("title-test", "https://title.invalid/rss", value, enabled = false))
                    else vm.selectDomainFilter(value)
                }
                assertFalse(layout(activity, value).isLineEllipsized(0))
                delay(450)
                val bounds = titleBounds(activity, value)
                val initial = screenshot(bounds)
                delay(500)
                val waiting = screenshot(bounds)
                delay(1600)
                val scrolling = screenshot(bounds)
                try {
                    assertTrue("Marquee must wait before moving (rss=$rss)", initial.sameAs(waiting))
                    assertFalse("Overflowing title must eventually scroll (rss=$rss)", waiting.sameAs(scrolling))
                } finally {
                    initial.recycle(); waiting.recycle(); scrolling.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync { vm.setFilterMode(oldMode); activity.finish() }
        }
    }
}
