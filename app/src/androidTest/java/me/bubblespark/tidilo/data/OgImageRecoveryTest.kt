package me.bubblespark.tidilo.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class OgImageRecoveryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(target.cacheDir, "og-test-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir(): File = root.apply { mkdirs() }
    }

    @Before
    fun reset() { runBlocking { OgImageManager.clearImageCache(context) } }

    @After
    fun cleanup() = runBlocking {
        OgImageManager.imageDownloader = null
        OgImageManager.clearImageCache(context)
        root.deleteRecursively()
        Unit
    }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    @Test
    fun concurrentImportAndCardsShareOneCompressedImage() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val bytes = imageBytes()
        OgImageManager.imageDownloader = {
            calls.incrementAndGet(); entered.complete(Unit); release.await(); bytes
        }
        val page = "https://example.com/article"
        val image = "https://cdn.example.com/cover.png"
        val importing = async { OgImageManager.processAndSaveOgImage(context, page, image) }
        entered.await()
        val cards = (0 until 5).map { async {
            OgImageManager.loadOrFetchOgImage(context, page, "example.com", image)
        } }
        release.complete(Unit)
        assertTrue(withTimeout(5000) { importing.await() })
        cards.awaitAll().forEach { assertNotNull(it); assertEquals(40, it!!.width) }
        assertEquals(1, calls.get())
        val file = OgImageManager.getOgImageFile(context, OgImageManager.computeImageKey(image))
        assertTrue(file.length() in 1 until 50 * 1024)
        OgImageManager.clearMemoryCache()
        assertNotNull(OgImageManager.loadOgImage(context, "example.com", file.name))
        assertNull(OgImageManager.getCachedOgImageBitmap("example.com", "different.webp"))
        assertNull(OgImageManager.loadOgImage(context, "example.com", "different.webp"))
    }

    @Test
    fun corruptDiskImageIsFetchedAgainAndBlankUrlNeverUsesLatest() = runBlocking {
        val image = "https://example.com/cover.png"
        val file = OgImageManager.getOgImageFile(context, OgImageManager.computeImageKey(image))
        file.writeText("corrupt")
        val calls = AtomicInteger()
        val bytes = imageBytes()
        OgImageManager.imageDownloader = { calls.incrementAndGet(); bytes }
        assertNotNull(OgImageManager.loadOrFetchOgImage(context, "https://example.com/a", "example.com", image))
        assertEquals(1, calls.get())
        assertNull(OgImageManager.loadOrFetchOgImage(context, "https://example.com/b", "example.com", "  "))
    }

    @Test
    fun invalidImageIsSuppressedUntilCacheClear() = runBlocking {
        val calls = AtomicInteger()
        OgImageManager.imageDownloader = { calls.incrementAndGet(); byteArrayOf(1, 2, 3) }
        repeat(2) {
            assertNull(OgImageManager.loadOrFetchOgImage(context, "https://example.com/a", "example.com", "/broken"))
        }
        assertEquals(1, calls.get())
        OgImageManager.clearImageCache(context)
        val bytes = imageBytes()
        OgImageManager.imageDownloader = { calls.incrementAndGet(); bytes }
        assertNotNull(OgImageManager.loadOrFetchOgImage(context, "https://example.com/a", "example.com", "/broken"))
        assertEquals(2, calls.get())
    }

    @Test
    fun clearWhileDownloadingPreventsOldFilesAndAllowsRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bytes = imageBytes()
        OgImageManager.imageDownloader = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            bytes
        }
        val old = async { OgImageManager.loadOrFetchOgImage(context, "https://example.com/a", "example.com", "/cover") }
        entered.await()
        OgImageManager.clearImageCache(context)
        release.complete(Unit)
        old.join()
        assertNull(OgImageManager.getCachedOgImageBitmap("example.com", OgImageManager.computeImageKey("https://example.com/cover")))
        assertFalse(OgImageManager.getOgImageFile(context, OgImageManager.computeImageKey("https://example.com/cover")).exists())
        OgImageManager.imageDownloader = { bytes }
        assertNotNull(OgImageManager.loadOrFetchOgImage(context, "https://example.com/a", "example.com", "/cover"))
    }

    @Test
    fun specificMissNeverUsesAnotherBookmarksLatestImage() = runBlocking {
        OgImageManager.clearImageCache(context)
        try {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            OgImageManager.getDomainLatestImageFile(context, "example.com").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            assertNull(OgImageManager.loadOgImage(context, "example.com", "missing.webp"))
        } finally {
            OgImageManager.clearImageCache(context)
            root.deleteRecursively()
        }
    }
}
