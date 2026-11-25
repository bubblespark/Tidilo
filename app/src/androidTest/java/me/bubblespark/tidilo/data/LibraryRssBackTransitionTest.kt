package me.bubblespark.tidilo.data

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving an RSS feed in the running app never draws the empty state, even while the feed updates.
 * Every real frame is observed through a draw listener, so no Compose test clock drives these animations.
 */
class LibraryRssBackTransitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test fun rssHeaderBackInListNeverRendersAnEmptyFrame() = verifyRssBack(LibraryViewMode.LIST, gesture = false)

    @Test fun rssEdgeSwipeBackInGridNeverRendersAnEmptyFrame() = verifyRssBack(LibraryViewMode.GRID, gesture = true)

    /** Texts of the latest drawn frame, plus the frames recorded while [recording] is set. */
    private class FrameRecorder(private val emptyTitle: String) {
        @Volatile var lastTexts: List<String> = emptyList()
        val recording = AtomicBoolean(false)
        val frames = AtomicInteger()
        val observedArticle = AtomicBoolean(false)
        val emptyFrames = CopyOnWriteArrayList<List<String>>()

        val draws = AtomicInteger()

        fun onFrame(texts: List<String>) {
            lastTexts = texts
            draws.incrementAndGet()
            if (!recording.get()) return
            frames.incrementAndGet()
            if (LAST_ARTICLE in texts) observedArticle.set(true)
            if (emptyTitle in texts) emptyFrames += texts
        }
    }

    private fun verifyRssBack(mode: LibraryViewMode, gesture: Boolean): Unit = runBlocking {
        val prefix = "rssback-${UUID.randomUUID()}-"
        val db = AppDatabase.getInstance(context)
        val feed = RssFeedEntity("${prefix}feed", "https://rss120-back.example/$prefix", "000 RSS back regression", enabled = false)
        val articles = (1..20).map { index ->
            BookmarkEntity("$prefix$index", "RSS back article $index", "https://rss120-back.example/$prefix/$index", "Summary",
                createdAt = index.toLong(), updatedAt = index.toLong(), rssFeedId = feed.id)
        }
        val prefs = context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
        val savedPrefs = PREF_KEYS.associateWith { prefs.getString(it, null) }
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val oldPreview = AppConfig.discoverPreviewEnabled.value
        val caches = OfflineVisualCaches()
        val recorder = FrameRecorder(context.getString(R.string.library_empty_title))
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            AppConfig.setOverlayBlurEnabled(false)
            AppConfig.setDiscoverPreviewEnabled(false)
            withContext(Dispatchers.IO) {
                db.rssDao().putFeed(feed)
                db.bookmarkDao().insertAll(articles)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm: LibraryViewModel
            lateinit var decor: View
            scenario.onActivity {
                decor = it.window.decorView
                decor.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener { recorder.onFrame(renderedTexts(decor)) })
                vm = ViewModelProvider(it)[LibraryViewModel::class.java]
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.setViewMode(mode)
            }
            awaitFrame(recorder, "feed list") { feed.title in it }
            instrumentation.runOnMainSync { vm.selectRssFeedFilter(feed) }
            awaitFrame(recorder, "feed articles") { LAST_ARTICLE in it }
            instrumentation.waitForIdleSync()

            recorder.recording.set(true)
            if (gesture) swipeBack() else clickHeaderBack(decor)
            // The feed updates while its exit runs.
            launch(Dispatchers.IO) {
                db.bookmarkDao().insertAll(listOf(articles.last().copy(description = "Updated during RSS back")))
            }.join()
            withTimeout(5_000) { vm.selectedRssFeedFilter.first { it == null } }
            awaitFrame(recorder, "feed list after back") { feed.title in it }
            awaitQuiet(recorder)
            recorder.recording.set(false)

            assertTrue("Test must sample transition frames", recorder.frames.get() >= 2)
            assertTrue("Frame observer must read the actual bookmark content", recorder.observedArticle.get())
            assertTrue("$mode gesture=$gesture RSS back rendered an empty result in ${recorder.emptyFrames.size} frames: " +
                "${recorder.emptyFrames.firstOrNull()}", recorder.emptyFrames.isEmpty())
            assertNull("Back must clear the selected feed", vm.selectedRssFeedFilter.value)
        } finally {
            scenario?.close()
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL("DELETE FROM bookmarks WHERE id LIKE ?", arrayOf("$prefix%"))
                    sql.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(feed.id))
                } finally {
                    val editor = prefs.edit()
                    savedPrefs.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                    editor.commit()
                    AppConfig.setOverlayBlurEnabled(oldBlur)
                    AppConfig.setDiscoverPreviewEnabled(oldPreview)
                    caches.close()
                }
            }
        }
    }

    /** Polls the last drawn frame (an in-memory read) until [condition] holds. */
    private suspend fun awaitFrame(recorder: FrameRecorder, stage: String, condition: (List<String>) -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition(recorder.lastTexts)) delay(100) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$stage did not render. Last frame: ${recorder.lastTexts}", timeout)
        }
    }

    /** Waits until no frame is drawn between two polls, i.e. the back transition has finished. */
    private suspend fun awaitQuiet(recorder: FrameRecorder) {
        var previous = -1
        withTimeout(10_000) {
            while (true) {
                val current = recorder.draws.get()
                if (current == previous) break
                previous = current
                delay(150)
            }
        }
    }

    /** Compose roots under [decor], found in one linear walk. */
    private fun composeRoots(decor: View): List<ViewRootForTest> {
        val roots = mutableListOf<ViewRootForTest>()
        fun collect(view: View) {
            when (view) {
                is ViewRootForTest -> roots += view
                is ViewGroup -> for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(decor)
        return roots
    }

    /** Every text drawn in this frame, from one linear walk over the window's semantics. */
    private fun renderedTexts(decor: View): List<String> {
        val texts = mutableListOf<String>()
        forEachNode(decor) { node -> node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text } }
        return texts
    }

    private fun forEachNode(decor: View, visit: (SemanticsNode) -> Unit) {
        val pending = ArrayDeque<SemanticsNode>()
        composeRoots(decor).forEach { pending.addLast(it.semanticsOwner.unmergedRootSemanticsNode) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            visit(node)
            node.children.forEach(pending::addLast)
        }
    }

    private fun clickHeaderBack(decor: View) {
        val description = context.getString(R.string.rss_back_to_feeds)
        instrumentation.runOnMainSync {
            var back: SemanticsNode? = null
            forEachNode(decor) { node ->
                if (back == null && node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true) back = node
            }
            var target = requireNotNull(back) { "Header back button missing" }
            while (target.config.getOrNull(SemanticsActions.OnClick) == null) {
                target = requireNotNull(target.parent) { "Clickable header back button missing after parent traversal" }
            }
            check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }

    /** A left-edge back swipe; event spacing sets the gesture velocity. */
    private suspend fun swipeBack() {
        val width = context.resources.displayMetrics.widthPixels
        val y = context.resources.displayMetrics.heightPixels * 0.55f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 1f)
        for (step in 1..20) {
            delay(16)
            send(MotionEvent.ACTION_MOVE, width * 0.7f * step / 20)
        }
        send(MotionEvent.ACTION_UP, width * 0.7f)
    }

    private companion object {
        const val LAST_ARTICLE = "RSS back article 20"
        val PREF_KEYS = listOf("library_filter_mode", "library_view_mode")
    }
}
