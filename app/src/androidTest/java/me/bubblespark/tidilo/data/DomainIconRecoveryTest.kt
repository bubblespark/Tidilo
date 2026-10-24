package me.bubblespark.tidilo.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class DomainIconRecoveryTest {
    private fun feed(id: String = "feed", icon: String? = "https://images.example/logo.png") =
        me.bubblespark.tidilo.data.db.RssFeedEntity(id, "https://feed.example/xml", "Feed",
            iconUrl = icon, siteUrl = "https://publisher.example/")

    @Test fun feedIconRecoversDirectlyAfterClearWithoutXmlOrHtmlAndKeepsSeparateIdentity() = runBlocking {
        val requests = mutableListOf<String>()
        val data = bytes(512)
        DomainIconManager.htmlDiscoverer = { error("Declared feed image must not discover HTML") }
        DomainIconManager.httpDownloader = { requests.add(it); DomainIconManager.DownloadResult(data, true, false) }
        val first = feed()
        val key = DomainIconManager.feedIconKey(first)
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, first))
        assertEquals(384, DomainIconManager.getCachedIconBitmap(key)!!.width)
        assertNotNull(DomainIconManager.getCachedDominantColor(key))
        assertTrue(File(context.cacheDir, "domain_icons/$key.png").exists())
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, first))
        assertEquals(1, requests.size)
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, feed("other", "https://images.example/other.png")))
        assertNotEquals(key, DomainIconManager.feedIconKey(feed("other")))
        DomainIconManager.clearCache(context)
        assertNull(DomainIconManager.getCachedIconBitmap(key))
        assertNull(DomainIconManager.getCachedDominantColor(key))
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, first))
        assertEquals(listOf("https://images.example/logo.png", "https://images.example/other.png", "https://images.example/logo.png"), requests)
    }

    @Test fun failedFeedImageFallsBackToPublisherAndDoesNotPoisonOtherFeeds() = runBlocking {
        val requests = mutableListOf<String>()
        val data = bytes()
        DomainIconManager.htmlDiscoverer = { page ->
            assertEquals("https://publisher.example", page)
            DomainIconManager.HtmlDiscoveryResult(listOf("https://publisher.example/icon.png"), true, false)
        }
        DomainIconManager.httpDownloader = {
            requests.add(it)
            DomainIconManager.DownloadResult(if (it == "https://images.example/logo.png") null else data, true, false)
        }
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, feed()))
        assertNotNull(DomainIconManager.getCachedIconBitmap("publisher.example"))
        assertNotNull(DomainIconManager.loadOrFetchFeedIcon(context, feed("other", "https://images.example/other.png")))
        assertEquals(1, requests.count { it == "https://images.example/logo.png" })
        assertEquals(1, requests.count { it == "https://images.example/other.png" })
    }

    @Test fun clearRejectsInflightFeedImageAndItsFailureMarkers() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        val source = feed()
        DomainIconManager.httpDownloader = {
            started.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            DomainIconManager.DownloadResult(data, true, false)
        }
        val request = async { DomainIconManager.loadOrFetchFeedIcon(context, source) }
        withTimeout(5000) { started.await() }
        DomainIconManager.clearCache(context)
        release.countDown()
        assertNull(withTimeout(5000) { request.await() })
        assertNull(DomainIconManager.getCachedIconBitmap(DomainIconManager.feedIconKey(source)))
        assertTrue(File(context.cacheDir, "domain_icons").listFiles().orEmpty().isEmpty())
    }

    @Test fun rssDiscoveryTimeoutAndRateLimitNeverWritePermanentMarker() = runBlocking {
        for (status in listOf(408, 429)) {
            val domain = "status$status.example.com"
            var requests = 0
            val network = object : me.bubblespark.tidilo.parser.ArticleNetwork {
                override val scopeKey = "fixture:$status"
                override suspend fun fetch(url: String): me.bubblespark.tidilo.parser.ArticleResponse {
                    requests++
                    val responseStatus = if (url == "https://$domain") status else 404
                    return me.bubblespark.tidilo.parser.ArticleResponse(url, responseStatus, byteArrayOf(), "text/html")
                }
            }
            assertFalse(DomainIconManager.fetchAndSaveDomainIcon(context, "https://$domain/article",
                knownCandidates = emptyList(), network = network).success)
            assertFalse(File(context.cacheDir, "domain_icons/$domain.none").exists())
            val completedRequests = requests
            assertFalse(DomainIconManager.fetchAndSaveDomainIcon(context, "https://$domain/article",
                knownCandidates = emptyList(), network = network).success)
            assertEquals(completedRequests, requests)
        }
    }

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(target.cacheDir, "icon-test-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(target) {
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
    }
    @Before fun reset() { runBlocking { DomainIconManager.clearCache(context) } }
    @After fun cleanup() {
        DomainIconManager.httpDownloader = null
        DomainIconManager.htmlDiscoverer = null
        runBlocking { DomainIconManager.clearCache(context) }
        root.deleteRecursively()
    }
    private fun bytes(size: Int = 180): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Transparent corners preserve the blue center during background processing.
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawRect(10f, 10f, (size - 10).toFloat(), (size - 10).toFloat(), android.graphics.Paint().apply {
            color = android.graphics.Color.BLUE
        })
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray()
        }
    }
    private fun emptyHtml() {
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
    }

    @Test fun corruptDiskFileRecoversAndPublishesColor() = runBlocking {
        val file = File(context.cacheDir, "domain_icons/example.com.png").apply { parentFile!!.mkdirs(); writeText("broken") }
        emptyHtml()
        val data = bytes(512)
        val calls = AtomicInteger()
        DomainIconManager.httpDownloader = { calls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false) }
        val image = DomainIconManager.loadOrFetchIcon(context, " EXAMPLE.COM ", "https://example.com/a")
        assertNotNull(image)
        assertEquals(384, image!!.width)
        assertEquals(1, calls.get())
        assertNotNull(DomainIconManager.getCachedDominantColor("example.com"))
        assertNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun coldDiskLoadPublishesAccentRevisionWithoutNetwork() = runBlocking {
        val file = File(context.cacheDir, "domain_icons/example.com.png").apply { parentFile!!.mkdirs(); writeBytes(bytes()) }
        val before = DomainIconManager.getDomainRevision("example.com")
        DomainIconManager.htmlDiscoverer = { error("Disk hit must not discover") }
        DomainIconManager.httpDownloader = { error("Disk hit must not download") }
        assertNotNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        assertTrue("Disk-loaded color must invalidate visible card accents", DomainIconManager.getDomainRevision("example.com") > before)
        assertTrue(file.exists())
        assertNotEquals(DomainIconManager.generateDomainAccentColor("example.com"), DomainIconManager.getCachedDominantColor("example.com"))
    }

    @Test fun preferredExplicitIconSkipsDiscoveryAndOtherDownloads() = runBlocking {
        val calls = mutableListOf<String>()
        val data = bytes()
        DomainIconManager.htmlDiscoverer = { error("Unexpected HTML discovery") }
        DomainIconManager.httpDownloader = { calls.add(it); DomainIconManager.DownloadResult(data, true, false) }
        assertTrue(DomainIconManager.fetchAndSaveDomainIcon(context, "https://example.com/a", "/apple").success)
        assertEquals(listOf("https://example.com/apple"), calls)
    }

    @Test fun importAndCardsShareWork() = runBlocking {
        emptyHtml()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        val calls = AtomicInteger()
        DomainIconManager.httpDownloader = {
            calls.incrementAndGet(); started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            DomainIconManager.DownloadResult(data, true, false)
        }
        val importing = async { DomainIconManager.fetchAndSaveDomainIcon(context, "https://example.com/a") }
        withTimeout(5000) { started.await() }
        val card = async(start = CoroutineStart.UNDISPATCHED) { DomainIconManager.loadOrFetchIcon(context, "example.com") }
        release.countDown()
        assertNotNull(withTimeout(5000) { card.await() })
        assertTrue(withTimeout(5000) { importing.await() }.success)
        assertEquals(1, calls.get())
    }

    @Test fun tenDomainsHaveAtMostFourNetworkWorkers() = runBlocking {
        emptyHtml()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CountDownLatch(1)
        val data = bytes()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        DomainIconManager.httpDownloader = {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }; entered.trySend(Unit)
            try { check(release.await(5, TimeUnit.SECONDS)); DomainIconManager.DownloadResult(data, true, false) }
            finally { active.decrementAndGet() }
        }
        val requests = (0 until 10).map { async { DomainIconManager.loadOrFetchIcon(context, "domain$it.example") } }
        try {
            withTimeout(5000) { repeat(4) { entered.receive() } }
            assertEquals(4, active.get())
        } finally { release.countDown() }
        requests.awaitAll().forEach { assertNotNull(it) }
        assertEquals(4, peak.get())
    }

    @Test fun permanentMarkerExpiryIsNotExtendedBySessionFailureCache() = runBlocking {
        emptyHtml()
        val calls = AtomicInteger()
        DomainIconManager.httpDownloader = { calls.incrementAndGet(); DomainIconManager.DownloadResult(null, true, false) }
        assertNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        assertTrue(DomainIconManager.shouldSkipFetch(context, "example.com"))
        val attempts = calls.get()
        assertNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        assertEquals(attempts, calls.get())
        File(context.cacheDir, "domain_icons/example.com.none").setLastModified(
            System.currentTimeMillis() - DomainIconManager.NEGATIVE_CACHE_TTL_MS - 1000)
        val data = bytes()
        DomainIconManager.httpDownloader = { calls.incrementAndGet(); DomainIconManager.DownloadResult(data, true, false) }
        assertNotNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        assertFalse(DomainIconManager.shouldSkipFetch(context, "example.com"))
    }

    @Test fun transientFailureBacksOffAndClearAllowsRetry() = runBlocking {
        emptyHtml()
        val calls = AtomicInteger()
        DomainIconManager.httpDownloader = { calls.incrementAndGet(); DomainIconManager.DownloadResult(null, false, true) }
        assertNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        val attempts = calls.get()
        assertTrue(attempts > 0)
        assertNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
        assertEquals(attempts, calls.get())
        assertFalse(DomainIconManager.shouldSkipFetch(context, "example.com"))
        DomainIconManager.clearCache(context)
        val data = bytes()
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(data, true, false) }
        assertNotNull(DomainIconManager.loadOrFetchIcon(context, "example.com"))
    }

    @Test fun clearRejectsOldSuccessAndReturnsMissToActiveCaller() = runBlocking {
        emptyHtml()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        DomainIconManager.httpDownloader = {
            started.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            DomainIconManager.DownloadResult(data, true, false)
        }
        val request = async { DomainIconManager.loadOrFetchIcon(context, "example.com") }
        withTimeout(5000) { started.await() }
        DomainIconManager.clearCache(context)
        release.countDown()
        assertNull(withTimeout(5000) { request.await() })
        assertNull(DomainIconManager.getCachedIconBitmap("example.com"))
        assertFalse(File(context.cacheDir, "domain_icons/example.com.png").exists())
        assertFalse(DomainIconManager.shouldSkipFetch(context, "example.com"))
    }
}
