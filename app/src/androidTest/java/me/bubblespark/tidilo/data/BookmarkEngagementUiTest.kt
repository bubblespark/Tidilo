package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
import me.bubblespark.tidilo.ui.screens.library.Tag

class BookmarkEngagementUiTest {
    @Test fun cardHeartTapsDoNotOpenDetail(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val clicks = AtomicInteger()
        try {
            val item = BookmarkItem(id = "heart-test", title = "Card", url = "https://example.com",
                description = "Summary", tag = Tag.createEmpty())
            for (grid in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    host.setContent { MaterialTheme {
                        if (grid) BookmarkGridCard(item, onClick = { clicks.incrementAndGet() })
                        else BookmarkItemCard(item, onClick = { clicks.incrementAndGet() })
                    } }
                }
                instrumentation.waitForIdleSync()
                delay(300)
                val node = withTimeout(10000) {
                    var found: AccessibilityNodeInfo? = null
                    while (found == null) {
                        instrumentation.uiAutomation.clearCache()
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                            if (n.contentDescription?.toString() == context.getString(me.bubblespark.tidilo.R.string.bookmark_open_in_browser)) return n
                            repeat(n.childCount) { i -> n.getChild(i)?.let { find(it)?.let { x -> return x } } }
                            return null
                        }
                        found = root?.let(::find)
                        if (found == null) {
                            if (root?.packageName?.toString() == "com.google.android.permissioncontroller") {
                                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            }
                            delay(30)
                        }
                    }
                    found
                }
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                val now = android.os.SystemClock.uptimeMillis()
                val y = bounds.bottom + 10 * context.resources.displayMetrics.density
                instrumentation.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now, now,
                    android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), y, 0), true)
                instrumentation.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now, now + 20,
                    android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), y, 0), true)
                delay(300)
                assertTrue("A heart tap must not open its card", clicks.get() == 0)
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    @Test fun toolbarLikeChangesImmediatelyAndCollectionDoesNothing(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        var activity: ComponentActivity? = null
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Toolbar test", "https://example.com/$id", "Summary", createdAt = 1, updatedAt = 1))
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity = host
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(host)[LibraryViewModel::class.java]
                host.setContent { MaterialTheme { BookmarkDetailScreen(id, vm, {}) } }
            }
            fun find(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
                if (node.contentDescription?.toString() == description) return node
                repeat(node.childCount) { i -> node.getChild(i)?.let { find(it, description)?.let { result -> return result } } }
                return null
            }
            suspend fun node(description: String): AccessibilityNodeInfo = withTimeout(10000) {
                var result: AccessibilityNodeInfo? = null
                while (result == null) {
                    instrumentation.uiAutomation.clearCache()
                    result = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, description) }
                    if (result == null) {
                        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ==
                            "com.google.android.permissioncontroller") {
                            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        delay(300)
                    }
                }
                result
            }
            assertFalse(db.bookmarkDao().getBookmarkById(id)!!.isLiked)
            fun click(n: AccessibilityNodeInfo): Boolean {
                var current: AccessibilityNodeInfo? = n
                while (current != null) {
                    if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    current = current.parent
                }
                return false
            }
            click(node("Collection"))
            assertFalse(db.bookmarkDao().getBookmarkById(id)!!.isLiked)
            assertTrue(click(node("Like")))
            withTimeout(10000) { while (!db.bookmarkDao().getBookmarkById(id)!!.isLiked) delay(30) }
            node("Unlike")
            withTimeout(10000) { while (db.bookmarkDao().getBookmarkById(id)!!.detailViewCount == 0) delay(30) }
            assertTrue(db.bookmarkDao().getBookmarkById(id)!!.detailViewCount == 1)
            instrumentation.runOnMainSync { host.moveTaskToBack(true) }
            delay(1000)
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            node("Unlike")
            delay(1000)
            assertTrue("Returning to the same detail entry must not count a second view",
                db.bookmarkDao().getBookmarkById(id)!!.detailViewCount == 1)
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            db.bookmarkDao().deleteById(id)
        }
    }
}
