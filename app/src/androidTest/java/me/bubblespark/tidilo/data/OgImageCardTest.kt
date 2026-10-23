package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.*
import org.junit.Test

class OgImageCardTest {
    @Test
    fun finishingOneCardDoesNotRestartItsSiblingAndFallbackRenders() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val domain = "issue74-card.example"
        val entered = Channel<String>(Channel.UNLIMITED)
        val gates = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred<Unit>())
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
        val generation = OgImageManager.getCurrentGeneration()
        // Use unique keys; do not clear the user's application-wide image cache in this UI test.
        val suffix = System.nanoTime()
        fun bookmark(id: String, image: Boolean = true) = BookmarkItem(
            id = id, title = "Preview $id", url = "https://$domain/$id",
            description = "", tag = Tag.create("Article", "Tech"),
            ogImageUrl = if (image) "https://$domain/$id.png?$suffix" else null
        )
        val a = bookmark("a")
        val b = bookmark("b")
        val fallback = mutableStateOf(false)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        try {
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
            OgImageManager.imageDownloader = { url ->
                val id = if (url.contains("/a.png")) "a" else "b"
                calls.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                entered.send(id)
                gates.getValue(id).await()
                bytes
            }
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Column {
                            if (fallback.value) BookmarkGridCard(bookmark("link", false)) else {
                                BookmarkGridCard(a)
                                BookmarkGridCard(b)
                            }
                        }
                    }
                }
            }
            assertEquals(setOf("a", "b"), withTimeout(5000) { setOf(entered.receive(), entered.receive()) })
            gates.getValue("a").complete(Unit)
            withTimeout(5000) {
                while (OgImageManager.getCachedOgImageBitmap(domain, a.ogImageFilename) == null) delay(10)
            }
            suspend fun nextFrame() {
                val frame = CompletableDeferred<Unit>()
                instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.complete(Unit) } }
                withTimeout(5000) { frame.await() }
                instrumentation.waitForIdleSync()
            }
            repeat(3) { nextFrame() }
            assertEquals(1, calls.getValue("b").get())
            gates.getValue("b").complete(Unit)
            withTimeout(5000) {
                while (OgImageManager.getCachedOgImageBitmap(domain, b.ogImageFilename) == null) delay(10)
            }
            // A notification for the same image on another domain must not erase A's displayed bitmap.
            repeat(3) { nextFrame() }
            val before = instrumentation.uiAutomation.takeScreenshot()
            val displayedColor = before.getPixel(before.width / 2, before.width / 4)
            before.recycle()
            assertTrue("The image must be visible before the notification",
                android.graphics.Color.blue(displayedColor) > 240 &&
                    android.graphics.Color.red(displayedColor) < 10 && android.graphics.Color.green(displayedColor) < 10)
            OgImageManager.clearMemoryCache()
            OgImageManager.getOgImageFile(context, a.ogImageFilename!!).delete()
            assertNotNull(OgImageManager.loadOrFetchOgImage(
                context, "https://other.example/a", "other.example", a.ogImageUrl
            ))
            repeat(3) { nextFrame() }
            val retained = instrumentation.uiAutomation.takeScreenshot()
            try {
                assertEquals("A missing domain cache entry must not erase the rendered image",
                    displayedColor, retained.getPixel(retained.width / 2, retained.width / 4))
            } finally {
                retained.recycle()
            }
            assertEquals(generation, OgImageManager.getCurrentGeneration())
            instrumentation.runOnMainSync { fallback.value = true }
            repeat(3) { nextFrame() }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "issue74-link-fallback.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            assertEquals(3, calls.values.sumOf { it.get() })
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            OgImageManager.imageDownloader = null
            gates.values.forEach { it.complete(Unit) }
            instrumentation.runOnMainSync { activity.finish() }
            for (item in listOf(a, b)) {
                OgImageManager.getOgImageFile(context, item.ogImageFilename!!).delete()
            }
            OgImageManager.getDomainLatestImageFile(context, domain).delete()
            OgImageManager.getDomainLatestImageFile(context, "other.example").delete()
        }
    }
}
