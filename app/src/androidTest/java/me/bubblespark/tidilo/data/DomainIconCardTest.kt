package me.bubblespark.tidilo.data

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test

class DomainIconCardTest {
    @Test fun feedCardRecoversDeclaredIconAndColorAgainWhileVisibleAfterClear() = runBlocking {
        val calls = AtomicInteger()
        val data = bytes()
        val feed = me.bubblespark.tidilo.data.db.RssFeedEntity("ui-feed", "https://feed-ui.example/xml", "UI Feed",
            iconUrl = "https://images-ui.example/logo.png", siteUrl = "https://publisher-ui.example/")
        DomainIconManager.htmlDiscoverer = { error("Feed card must not fetch HTML") }
        DomainIconManager.httpDownloader = { url ->
            assertEquals("https://images-ui.example/logo.png", url)
            calls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false)
        }
        screen({ RssFeedCard(me.bubblespark.tidilo.data.db.RssFeedGroup(feed, 3), {}) }) {
            val key = DomainIconManager.feedIconKey(feed)
            withTimeout(5000) { while (DomainIconManager.getCachedIconBitmap(key) == null) delay(10) }
            frames()
            assertEquals(1, calls.get())
            DomainIconManager.clearCache(context)
            withTimeout(5000) { while (calls.get() < 2 || DomainIconManager.getCachedIconBitmap(key) == null) delay(10) }
            frames()
            assertEquals(2, calls.get())
            assertNotEquals(DomainIconManager.generateDomainAccentColor(key), DomainIconManager.getCachedDominantColor(key))
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val root = File(target.cacheDir, "icon-ui-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    }
    private val domain = "icon-ui.example"
    private fun item(image: Boolean = false) = BookmarkItem("icon-test", "Icon test", "https://$domain/a", "",
        Tag.create("Article", "Tech"), ogImageUrl = if (image) "https://$domain/og.png" else null)
    private fun bytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawCircle(90f, 90f, 70f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray()
        }
    }
    private suspend fun frames() {
        repeat(3) {
            val frame = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.complete(Unit) } }
            withTimeout(5000) { frame.await() }
            instrumentation.waitForIdleSync()
        }
    }
    private suspend fun screen(content: @Composable () -> Unit, check: suspend () -> Unit) {
        DomainIconManager.clearCache(context)
        OgImageManager.clearImageCache(context)
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme { content() } } }
            }
            check()
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            DomainIconManager.httpDownloader = null
            DomainIconManager.htmlDiscoverer = null
            OgImageManager.imageDownloader = null
            DomainIconManager.clearCache(context)
            OgImageManager.clearImageCache(context)
            root.deleteRecursively()
        }
    }

    @Test fun gridWaitsForOgSuccessWithoutIconNetwork() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val iconCalls = AtomicInteger()
        val data = bytes()
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { iconCalls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false) }
        OgImageManager.imageDownloader = { started.complete(Unit); release.await(); data }
        val bookmark = item(true)
        screen({ BookmarkGridCard(bookmark) }) {
            try {
                withTimeout(5000) { started.await() }
                frames()
                assertEquals(0, iconCalls.get())
                release.complete(Unit)
                withTimeout(5000) {
                    while (OgImageManager.getCachedOgImageBitmap(domain, bookmark.ogImageFilename) == null) delay(10)
                }
                frames()
                assertEquals(0, iconCalls.get())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun gridRecoversIconOnlyAfterOgFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val iconCalls = AtomicInteger()
        val data = bytes()
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { iconCalls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false) }
        OgImageManager.imageDownloader = { started.complete(Unit); release.await(); null }
        screen({ BookmarkGridCard(item(true)) }) {
            try {
                withTimeout(5000) { started.await() }
                frames()
                assertEquals(0, iconCalls.get())
                release.complete(Unit)
                withTimeout(5000) { while (DomainIconManager.getCachedIconBitmap(domain) == null) delay(10) }
                frames()
                assertEquals(1, iconCalls.get())
                assertNotEquals(DomainIconManager.generateDomainAccentColor(domain), DomainIconManager.getCachedDominantColor(domain))
            } finally { release.complete(Unit) }
        }
    }

    @Test fun listAndDomainCardsShareRecoveryAndReconnectAfterClear() = runBlocking {
        val calls = AtomicInteger()
        val data = bytes()
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { calls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false) }
        screen({ Column { BookmarkItemCard(item()); DomainCard(DomainGroupItem(domain, "Test domain", 1), {}) } }) {
            withTimeout(5000) { while (DomainIconManager.getCachedIconBitmap(domain) == null) delay(10) }
            frames()
            assertEquals(1, calls.get())
            DomainIconManager.clearCache(context)
            withTimeout(5000) { while (calls.get() < 2 || DomainIconManager.getCachedIconBitmap(domain) == null) delay(10) }
            frames()
            assertEquals(2, calls.get())
            assertNotEquals(DomainIconManager.generateDomainAccentColor(domain), DomainIconManager.getCachedDominantColor(domain))
        }
    }
}
