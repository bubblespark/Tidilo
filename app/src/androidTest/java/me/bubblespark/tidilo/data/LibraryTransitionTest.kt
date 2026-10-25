package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlinx.coroutines.isActive
import me.bubblespark.tidilo.config.AppConfig

/** Slows this composition's animations so assertions inspect the exit, rather than only its endpoint. */
class LibraryTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private suspend fun swipeBack(activity: ComponentActivity) {
        val width = activity.resources.displayMetrics.widthPixels
        val y = activity.resources.displayMetrics.heightPixels * 0.55f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1f)
        for (step in 1..20) {
            delay(16)
            send(MotionEvent.ACTION_MOVE, width * 0.7f * step / 20)
        }
        send(MotionEvent.ACTION_UP, width * 0.7f)
    }

    @Test fun rssHeaderAndEdgeSwipeBackNeverRenderAnEmptyResultInAnyFrame(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val feed = RssFeedEntity(id, "https://rss120-back.example/$id", "000 RSS back regression", enabled = false)
        val articles = (1..20).map { index -> BookmarkEntity("$id-$index", "RSS back article $index",
            "https://rss120-back.example/$id/$index", "Summary", createdAt = index.toLong(), updatedAt = index.toLong(), rssFeedId = id) }
        db.rssDao().putFeed(feed)
        db.bookmarkDao().insertAll(articles)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var vm: LibraryViewModel
        var oldMode = LibraryFilterMode.NEWEST_FIRST
        var oldView = LibraryViewMode.LIST
        var listener: ViewTreeObserver.OnDrawListener? = null
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                oldView = vm.viewMode.value
                vm.setFilterMode(LibraryFilterMode.RSS)
            }
            for ((mode, gesture) in LibraryViewMode.entries.flatMap { listOf(it to false, it to true) }) {
                instrumentation.runOnMainSync { vm.setViewMode(mode) }
                awaitText(feed.title)
                instrumentation.runOnMainSync { vm.selectRssFeedFilter(feed) }
                awaitText("RSS back article 20")
                delay(350)
                val emptyFrames = mutableListOf<List<String>>()
                var frames = 0
                var observedArticle = false
                fun texts(node: SemanticsNode): List<String> =
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
                fun roots(view: View): List<ViewRootForTest> = when (view) {
                    is ViewRootForTest -> listOf(view)
                    is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
                    else -> emptyList()
                }
                listener = ViewTreeObserver.OnDrawListener {
                    val rendered = roots(activity.window.decorView).flatMap { texts(it.semanticsOwner.unmergedRootSemanticsNode) }
                    frames++
                    observedArticle = observedArticle || rendered.any { it == "RSS back article 20" }
                    if (rendered.any { it == context.getString(me.bubblespark.tidilo.R.string.library_empty_title) }) emptyFrames += rendered
                }
                instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.addOnDrawListener(listener) }
                if (gesture) swipeBack(activity) else {
                    val back = withTimeout(5000) {
                        var found: AccessibilityNodeInfo? = null
                        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                            if (node.contentDescription?.toString() == "Back to feeds") return node
                            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { match -> return match } }
                            return null
                        }
                        while (found == null) {
                            instrumentation.uiAutomation.clearCache()
                            found = instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
                            if (found == null) delay(20)
                        }
                        found
                    }
                    var button: AccessibilityNodeInfo? = back
                    while (button != null && !button.isClickable) button = button.parent
                    val clickableButton = requireNotNull(button) { "Clickable header back button missing after parent traversal" }
                    assertTrue("Header back must be clickable", clickableButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                val update = launch {
                    delay(32)
                    db.bookmarkDao().insertAll(listOf(articles.last().copy(description = "Updated during RSS back")))
                }
                delay(650)
                update.join()
                instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
                listener = null
                assertTrue("Test must sample transition frames", frames >= 2)
                assertTrue("Frame observer must read the actual bookmark content", observedArticle)
                assertTrue("$mode gesture=$gesture RSS back rendered an empty result in ${emptyFrames.size} frames: ${emptyFrames.firstOrNull()}", emptyFrames.isEmpty())
                assertNull("Back must clear the selected feed", vm.selectedRssFeedFilter.value)
                awaitText(feed.title)
            }
        } finally {
            instrumentation.runOnMainSync {
                listener?.let { activity.window.decorView.viewTreeObserver.removeOnDrawListener(it) }
                runCatching { vm.setFilterMode(oldMode); vm.setViewMode(oldView) }
                activity.finish()
            }
            articles.forEach { db.bookmarkDao().deleteById(it.id) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(id))
        }
    }

    @Test fun selectedPagerSurvivesLibraryCompositionDisposal() {
        val store = ViewModelStore()
        var oldMode = LibraryFilterMode.NEWEST_FIRST
        lateinit var vm: LibraryViewModel
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                val visible = AppConfig.isNsfwContentVisible.value
                val first = vm.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, visible)
                first.users++
                vm.releaseLibraryPages(first)
                val returned = vm.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, visible)
                assertSame("Detail return must reuse the loaded Pager window", first, returned)
                assertTrue(first.cacheScope.isActive)
                returned.users++
                vm.setFilterMode(LibraryFilterMode.RSS)
                assertTrue("Outgoing session must remain alive until exit disposal", first.cacheScope.isActive)
                vm.releaseLibraryPages(returned)
                assertFalse("Retired windows must release their cache", first.cacheScope.isActive)

                vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                val restored = vm.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, visible)
                assertNotSame("Retired windows cannot be reused", first, restored)
                restored.users++
                vm.setFilterMode(LibraryFilterMode.DOMAIN)
                vm.selectDomainFilter("transition120.example")
                val intermediate = vm.libraryPages(LibraryFilterMode.DOMAIN, "transition120.example", null, visible)
                intermediate.users++
                vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                vm.activateLibraryPages(restored)
                vm.releaseLibraryPages(intermediate)
                assertFalse(intermediate.cacheScope.isActive)
                vm.releaseLibraryPages(restored)
                assertSame("A-to-B-to-A must retain A across later detail navigation", restored,
                    vm.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, visible))
                // Replacement while the screen is absent must not leak the retained cache.
                vm.libraryPages(LibraryFilterMode.NEWEST_FIRST, null, null, !visible)
                assertFalse(restored.cacheScope.isActive)
            }
        } finally {
            instrumentation.runOnMainSync {
                runCatching { vm.setFilterMode(oldMode) }
                store.clear()
            }
        }
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private suspend fun awaitText(text: String) = withTimeout(10000) {
        while (find(text) == null) delay(30)
    }

    private fun capture(name: String): android.graphics.Bitmap = instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
        java.io.File(context.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // Exiting AnimatedContent is excluded from accessibility, even while still drawn.
    private fun assertTextStillDrawn(before: android.graphics.Bitmap, during: android.graphics.Bitmap, bounds: android.graphics.Rect) {
        var dark = 0
        var retained = 0
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val old = before.getPixel(x, y)
            if (android.graphics.Color.red(old) < 80 && android.graphics.Color.green(old) < 80 && android.graphics.Color.blue(old) < 80) {
                dark++
                val next = during.getPixel(x, y)
                if (android.graphics.Color.red(next) < 120 && android.graphics.Color.green(next) < 120 && android.graphics.Color.blue(next) < 120) retained++
            }
        }
        assertTrue("Fixture must contain rendered title pixels", dark > 50)
        assertTrue("Outgoing title pixels must survive fade-out: $retained / $dark", retained > dark * 0.6)
    }

    private fun assertAccentStillDrawn(before: android.graphics.Bitmap, during: android.graphics.Bitmap, bounds: android.graphics.Rect) {
        var colored = 0
        var retained = 0
        val oldHsv = FloatArray(3)
        val newHsv = FloatArray(3)
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            android.graphics.Color.colorToHSV(before.getPixel(x, y), oldHsv)
            if (oldHsv[1] > 0.35f) {
                colored++
                android.graphics.Color.colorToHSV(during.getPixel(x, y), newHsv)
                val distance = kotlin.math.abs(oldHsv[0] - newHsv[0])
                if (minOf(distance, 360f - distance) < 20f && newHsv[1] > 0.15f) retained++
            }
        }
        assertTrue("Fixture must contain accent-colored subtitle pixels", colored > 30)
        assertTrue("Outgoing subtitle must retain its accent: $retained / $colored", retained > colored * 0.35)
    }

    @Test fun outgoingFeedAndDomainKeepTheirBookmarksUntilExitCompletes(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val feed = RssFeedEntity(id, "https://transition120.example/feed", "000 Transition feed", enabled = false)
        val domain = "000-transition120.example"
        val manual = BookmarkEntity("$id-manual", "Transition manual article", "https://$domain/article", "Summary", createdAt = 1, updatedAt = 1)
        val rss = BookmarkEntity("$id-rss", "Transition RSS article", "https://transition120.example/article", "Summary", createdAt = 2, updatedAt = 2, rssFeedId = id)
        db.rssDao().putFeed(feed)
        db.bookmarkDao().insertAll(listOf(manual, rss))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var vm: LibraryViewModel
        var oldMode = LibraryFilterMode.NEWEST_FIRST
        var oldView = LibraryViewMode.LIST
        lateinit var animationScope: CoroutineScope
        lateinit var recomposer: Recomposer
        try {
            instrumentation.runOnMainSync {
                animationScope = CoroutineScope(AndroidUiDispatcher.CurrentThread + SupervisorJob() +
                    object : MotionDurationScale { override val scaleFactor = 10f })
                recomposer = Recomposer(animationScope.coroutineContext)
                animationScope.launch { recomposer.runRecomposeAndApplyChanges() }
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                oldView = vm.viewMode.value
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.setViewMode(LibraryViewMode.LIST)
                activity.setContent(parent = recomposer) { MaterialTheme { LibraryScreen(vm) } }
            }
            awaitText(feed.title)
            delay(2400)
            instrumentation.runOnMainSync { vm.selectRssFeedFilter(feed) }
            awaitText(rss.title)
            delay(2400)
            val rssBounds = android.graphics.Rect().also {
                requireNotNull(find(rss.title)) { "RSS title missing when capturing exit frame" }.getBoundsInScreen(it)
            }
            val subtitleBounds = android.graphics.Rect().also {
                requireNotNull(find("1 bookmark")) { "RSS bookmark count missing when capturing exit frame" }.getBoundsInScreen(it)
            }
            val rssBefore = capture("issue120-before.png")
            instrumentation.runOnMainSync { vm.selectRssFeedFilter(null) }
            delay(450)
            val rssDuring = capture("issue120-during.png")
            assertTextStillDrawn(rssBefore, rssDuring, rssBounds)
            assertAccentStillDrawn(rssBefore, rssDuring, subtitleBounds)
            rssBefore.recycle(); rssDuring.recycle()
            assertNull("RSS back must not introduce a transient empty state", find("No bookmarks yet"))
            delay(2200)
            assertNull(find(rss.title))

            instrumentation.runOnMainSync { vm.setFilterMode(LibraryFilterMode.DOMAIN) }
            awaitText(domain)
            delay(2400)
            val gridBounds = android.graphics.Rect().also {
                requireNotNull(find(domain)) { "Domain card title missing when capturing forward frame" }.getBoundsInScreen(it)
            }
            val gridBefore = capture("issue120-domain-grid.png")
            instrumentation.runOnMainSync { vm.selectDomainFilter(domain) }
            delay(450)
            val forward = capture("issue120-domain-forward.png")
            assertTextStillDrawn(gridBefore, forward, gridBounds)
            gridBefore.recycle(); forward.recycle()
            awaitText(manual.title)
            delay(2400)
            val manualBounds = android.graphics.Rect().also {
                requireNotNull(find(manual.title)) { "Domain bookmark title missing when capturing exit frame" }.getBoundsInScreen(it)
            }
            val manualBefore = capture("issue120-domain-before.png")
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            delay(450)
            val manualDuring = capture("issue120-domain-during.png")
            assertTextStillDrawn(manualBefore, manualDuring, manualBounds)
            manualBefore.recycle(); manualDuring.recycle()
            delay(2200)
            assertNull(find(manual.title))

            instrumentation.runOnMainSync { vm.setFilterMode(LibraryFilterMode.NEWEST_FIRST); vm.setViewMode(LibraryViewMode.GRID) }
            awaitText(manual.title)
            delay(2400)
            val timelineBounds = android.graphics.Rect().also {
                requireNotNull(find(manual.title)) { "Timeline bookmark title missing when capturing mode-switch frame" }.getBoundsInScreen(it)
            }
            val timelineBefore = capture("issue120-timeline.png")
            instrumentation.runOnMainSync { vm.setFilterMode(LibraryFilterMode.RSS) }
            delay(450)
            val modeDuring = capture("issue120-mode-switch.png")
            assertTextStillDrawn(timelineBefore, modeDuring, timelineBounds)
            timelineBefore.recycle(); modeDuring.recycle()
            delay(2200)
            assertNull(find(manual.title))

            // An actually empty feed must still settle to the empty state.
            val emptyFeed = feed.copy(id = "$id-empty", url = "https://transition120.example/empty")
            instrumentation.runOnMainSync { vm.selectRssFeedFilter(emptyFeed) }
            awaitText("No bookmarks yet")
        } finally {
            instrumentation.runOnMainSync {
                runCatching { vm.setFilterMode(oldMode); vm.setViewMode(oldView) }
                activity.finish()
                recomposer.cancel()
                animationScope.cancel()
            }
            db.bookmarkDao().deleteById(manual.id)
            db.bookmarkDao().deleteById(rss.id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(id))
        }
    }
}
