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
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test

class RssFeedLibraryScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun text(value: String) = withTimeout(5000) {
        while (find { it.text?.toString()?.contains(value) == true } == null) delay(50)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.cacheDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private suspend fun click(action: Int = AccessibilityNodeInfo.ACTION_CLICK, predicate: (AccessibilityNodeInfo) -> Boolean) = withTimeout(5000) {
        while (true) {
            var node = find(predicate)
            while (node != null && if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) !node.isLongClickable else !node.isClickable) node = node.parent
            if (node?.performAction(action) == true) return@withTimeout
            delay(50)
        }
    }

    @Test fun feedDeckOpensOnlyItsArticlesSupportsBothViewsAndReturnsWithHeaderAndSystemBack(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val a = RssFeedEntity("$id-a", "https://issue99.example/a", "0000 Alpha feed", deleted = true,
            iconUrl = "https://images99.example/a.png", siteUrl = "https://publisher99.example/")
        val b = RssFeedEntity("$id-b", "https://issue99.example/b", "0001 Beta feed", enabled = false,
            iconUrl = "https://images99.example/b.png", siteUrl = "https://publisher99.example/")
        db.rssDao().putFeed(a); db.rssDao().putFeed(b)
        db.bookmarkDao().insertAll(listOf(
            BookmarkEntity("$id-one", "Alpha article", "https://article99.example/a", "Alpha summary", createdAt = 1, updatedAt = 1, rssFeedId = a.id),
            BookmarkEntity("$id-two", "Beta article", "https://article99.example/b", "Beta summary", createdAt = 2, updatedAt = 2, rssFeedId = b.id)))
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        val icon = android.graphics.Bitmap.createBitmap(180, 180, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(icon).drawCircle(90f, 90f, 65f,
            android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        val iconBytes = java.io.ByteArrayOutputStream().use {
            icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); icon.recycle(); it.toByteArray()
        }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(iconBytes, true, false) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var vm: LibraryViewModel
        var oldFilter = LibraryFilterMode.NEWEST_FIRST
        var oldView = LibraryViewMode.LIST
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldFilter = vm.filterMode.value; oldView = vm.viewMode.value
                vm.setFilterMode(LibraryFilterMode.RSS)
                vm.setViewMode(LibraryViewMode.LIST)
                activity.setContent { MaterialTheme { LibraryScreen(vm) } }
            }
            text("0000 Alpha feed"); text("0001 Beta feed")
            val left = android.graphics.Rect()
            val right = android.graphics.Rect()
            find { it.text?.toString()?.contains("0000 Alpha feed") == true }!!.getBoundsInScreen(left)
            find { it.text?.toString()?.contains("0001 Beta feed") == true }!!.getBoundsInScreen(right)
            assertTrue("Feed decks must be side-by-side in title order", left.left < right.left && left.top == right.top)
            screenshot("issue99-feed-grid.png")
            assertNull(find { it.text?.toString() == "Alpha article" })
            click { it.text?.toString()?.contains("0000 Alpha feed") == true }
            text("Alpha article")
            screenshot("issue99-feed-detail.png")
            assertNull(find { it.text?.toString() == "Beta article" })
            instrumentation.runOnMainSync { vm.setViewMode(LibraryViewMode.GRID) }
            text("Alpha article")
            click(AccessibilityNodeInfo.ACTION_LONG_CLICK) { it.text?.toString()?.contains("Alpha article") == true }
            text("Alpha summary")
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            text("Alpha article")
            click { it.contentDescription?.toString() == "Back to feeds" }
            text("0001 Beta feed")
            click { it.text?.toString()?.contains("0001 Beta feed") == true }
            text("Beta article")
            assertNull(find { it.text?.toString() == "Alpha article" })
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            text("0000 Alpha feed")
        } finally {
            instrumentation.runOnMainSync {
                runCatching { vm.setFilterMode(oldFilter); vm.setViewMode(oldView) }
                activity.finish()
            }
            db.bookmarkDao().deleteById("$id-one"); db.bookmarkDao().deleteById("$id-two")
            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id IN (?,?)", arrayOf(a.id, b.id))
            DomainIconManager.htmlDiscoverer = null; DomainIconManager.httpDownloader = null
        }
    }
}
