package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OgImageCardTest {
    @get:Rule val composeRule = createComposeRule()

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
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Column {
                        if (fallback.value) BookmarkGridCardFixture(bookmark("link", false)) else {
                            BookmarkGridCardFixture(a)
                            BookmarkGridCardFixture(b)
                        }
                    }
                }
            }
            assertEquals(setOf("a", "b"), withTimeout(5000) { setOf(entered.receive(), entered.receive()) })
            gates.getValue("a").complete(Unit)
            composeRule.waitUntil(5_000) { OgImageManager.getCachedOgImageBitmap(domain, a.ogImageFilename) != null }
            composeRule.waitForIdle()
            assertEquals(1, calls.getValue("b").get())
            gates.getValue("b").complete(Unit)
            composeRule.waitUntil(5_000) { OgImageManager.getCachedOgImageBitmap(domain, b.ogImageFilename) != null }
            // A notification for the same image on another domain must not erase A's displayed bitmap.
            composeRule.waitForIdle()
            val before = instrumentation.uiAutomation.takeScreenshot()
            val displayedColor = try { before.getPixel(before.width / 2, before.width / 4) } finally { before.recycle() }
            assertTrue("The image must be visible before the notification",
                android.graphics.Color.blue(displayedColor) > 240 &&
                    android.graphics.Color.red(displayedColor) < 10 && android.graphics.Color.green(displayedColor) < 10)
            OgImageManager.clearMemoryCache()
            OgImageManager.getOgImageFile(context, a.ogImageFilename!!).delete()
            assertNotNull(OgImageManager.loadOrFetchOgImage(
                context, "https://other.example/a", "other.example", a.ogImageUrl
            ))
            composeRule.waitForIdle()
            val retained = instrumentation.uiAutomation.takeScreenshot()
            try {
                assertEquals("A missing domain cache entry must not erase the rendered image",
                    displayedColor, retained.getPixel(retained.width / 2, retained.width / 4))
            } finally {
                retained.recycle()
            }
            assertEquals(generation, OgImageManager.getCurrentGeneration())
            composeRule.runOnUiThread { fallback.value = true }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Preview link").assertExists()
            assertEquals(3, calls.values.sumOf { it.get() })
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            OgImageManager.imageDownloader = null
            gates.values.forEach { it.complete(Unit) }
            for (item in listOf(a, b)) {
                OgImageManager.getOgImageFile(context, item.ogImageFilename!!).delete()
            }
            OgImageManager.getDomainLatestImageFile(context, domain).delete()
            OgImageManager.getDomainLatestImageFile(context, "other.example").delete()
        }
    }
}
