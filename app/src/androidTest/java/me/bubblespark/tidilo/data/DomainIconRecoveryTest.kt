package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import me.bubblespark.tidilo.data.cache.DomainIconStore
import me.bubblespark.tidilo.data.cache.IconDownloadResult
import me.bubblespark.tidilo.data.cache.IconHtmlDiscoveryResult
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

class DomainIconRecoveryTest {
    private fun feed(id: String = "feed", icon: String? = "https://images.example/logo.png") =
        me.bubblespark.tidilo.data.db.RssFeedEntity(id, "https://feed.example/xml", "Feed",
            iconUrl = icon, siteUrl = "https://publisher.example/")

    @Test fun feedIconRecoversDirectlyAfterClearWithoutXmlOrHtmlAndKeepsSeparateIdentity() = runBlocking {
        val requests = mutableListOf<String>()
        val data = bytes(512)
        htmlDiscoverer = { error("Declared feed image must not discover HTML") }
        httpDownloader = { requests.add(it); IconDownloadResult(data, true, false) }
        val first = feed()
        val key = MediaCacheLayout.feedIconKey(first)
        assertNotNull(icons.loadOrFetchFeedIcon(first))
        assertEquals(384, icons.getCachedIconBitmap(key)!!.width)
        assertNotNull(icons.getCachedDominantColor(key))
        assertTrue(store.layout.domainIconFile(key).exists())
        assertNotNull(icons.loadOrFetchFeedIcon(first))
        assertEquals(1, requests.size)
        assertNotNull(icons.loadOrFetchFeedIcon(feed("other", "https://images.example/other.png")))
        assertNotEquals(key, MediaCacheLayout.feedIconKey(feed("other")))
        icons.clearCache()
        assertNull(icons.getCachedIconBitmap(key))
        assertNull(icons.getCachedDominantColor(key))
        assertNotNull(icons.loadOrFetchFeedIcon(first))
        assertEquals(listOf("https://images.example/logo.png", "https://images.example/other.png", "https://images.example/logo.png"), requests)
    }

    @Test fun failedFeedImageFallsBackToPublisherAndDoesNotPoisonOtherFeeds() = runBlocking {
        val requests = mutableListOf<String>()
        val data = bytes()
        htmlDiscoverer = { page ->
            assertEquals("https://publisher.example", page)
            IconHtmlDiscoveryResult(listOf("https://publisher.example/icon.png"), true, false)
        }
        httpDownloader = {
            requests.add(it)
            IconDownloadResult(if (it == "https://images.example/logo.png") null else data, true, false)
        }
        assertNotNull(icons.loadOrFetchFeedIcon(feed()))
        assertNotNull(icons.getCachedIconBitmap("publisher.example"))
        assertNotNull(icons.loadOrFetchFeedIcon(feed("other", "https://images.example/other.png")))
        assertEquals(1, requests.count { it == "https://images.example/logo.png" })
        assertEquals(1, requests.count { it == "https://images.example/other.png" })
    }

    @Test fun clearRejectsInflightFeedImageAndItsFailureMarkers() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        val source = feed()
        httpDownloader = {
            started.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            IconDownloadResult(data, true, false)
        }
        val request = async { icons.loadOrFetchFeedIcon(source) }
        withTimeout(5000) { started.await() }
        icons.clearCache()
        release.countDown()
        assertNull(withTimeout(5000) { request.await() })
        assertNull(icons.getCachedIconBitmap(MediaCacheLayout.feedIconKey(source)))
        assertTrue(store.layout.domainIconsDir(createIfMissing = false).listFiles().orEmpty().isEmpty())
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
            assertFalse(icons.fetchAndSaveDomainIcon("https://$domain/article",
                knownCandidates = emptyList(), network = network).success)
            assertFalse(store.layout.negativeMarkerFile(domain).exists())
            val completedRequests = requests
            assertFalse(icons.fetchAndSaveDomainIcon("https://$domain/article",
                knownCandidates = emptyList(), network = network).success)
            assertEquals(completedRequests, requests)
        }
    }

    private val root = Files.createTempDirectory(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(),
        "icon-test-"
    ).toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpDownloader: (String) -> IconDownloadResult = { error("Unexpected icon download: $it") }
    private var htmlDiscoverer: (String) -> IconHtmlDiscoveryResult = { error("Unexpected HTML discovery: $it") }
    private val store = MediaCacheStore(
        cacheRootDir = root,
        iconHttpDownloader = { httpDownloader(it) },
        iconHtmlDiscoverer = { htmlDiscoverer(it) },
        emissionScope = scope
    )
    private val icons = store.domainIcons

    @After fun cleanup() {
        try {
            runBlocking { icons.clearCache() }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
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
        htmlDiscoverer = { IconHtmlDiscoveryResult(emptyList(), true, false) }
    }

    @Test fun corruptDiskFileRecoversAndPublishesColor() = runBlocking {
        val file = store.layout.domainIconFile("example.com").apply { writeText("broken") }
        emptyHtml()
        val data = bytes(512)
        val calls = AtomicInteger()
        httpDownloader = { calls.incrementAndGet(); IconDownloadResult(data, true, false) }
        val image = icons.loadOrFetchIcon(" EXAMPLE.COM ", "https://example.com/a")
        assertNotNull(image)
        assertEquals(384, image!!.width)
        assertEquals(1, calls.get())
        assertNotNull(icons.getCachedDominantColor("example.com"))
        assertNotNull(BitmapFactory.decodeFile(file.absolutePath))
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun coldDiskLoadPublishesAccentRevisionWithoutNetwork() = runBlocking {
        val file = store.layout.domainIconFile("example.com").apply { writeBytes(bytes()) }
        val before = store.revisionBus.getIconDomainRevision("example.com")
        htmlDiscoverer = { error("Disk hit must not discover") }
        httpDownloader = { error("Disk hit must not download") }
        assertNotNull(icons.loadOrFetchIcon("example.com"))
        assertTrue("Disk-loaded color must invalidate visible card accents", store.revisionBus.getIconDomainRevision("example.com") > before)
        assertTrue(file.exists())
        val color = icons.getCachedDominantColor("example.com")
        assertNotNull(color)
        assertNotEquals(MediaColorPalette.generateDomainAccentColor("example.com"), color)
    }

    @Test fun preferredExplicitIconSkipsDiscoveryAndOtherDownloads() = runBlocking {
        val calls = mutableListOf<String>()
        val data = bytes()
        htmlDiscoverer = { error("Unexpected HTML discovery") }
        httpDownloader = { calls.add(it); IconDownloadResult(data, true, false) }
        assertTrue(icons.fetchAndSaveDomainIcon("https://example.com/a", "/apple").success)
        assertEquals(listOf("https://example.com/apple"), calls)
    }

    @Test fun importAndCardsShareWork() = runBlocking {
        emptyHtml()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        val calls = AtomicInteger()
        httpDownloader = {
            calls.incrementAndGet(); started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            IconDownloadResult(data, true, false)
        }
        val importing = async { icons.fetchAndSaveDomainIcon("https://example.com/a") }
        withTimeout(5000) { started.await() }
        val card = async(start = CoroutineStart.UNDISPATCHED) { icons.loadOrFetchIcon("example.com") }
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
        httpDownloader = {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }; entered.trySend(Unit)
            try { check(release.await(5, TimeUnit.SECONDS)); IconDownloadResult(data, true, false) }
            finally { active.decrementAndGet() }
        }
        val requests = (0 until 10).map { async { icons.loadOrFetchIcon("domain$it.example") } }
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
        httpDownloader = { calls.incrementAndGet(); IconDownloadResult(null, true, false) }
        assertNull(icons.loadOrFetchIcon("example.com"))
        assertTrue(icons.shouldSkipFetch("example.com"))
        val attempts = calls.get()
        assertNull(icons.loadOrFetchIcon("example.com"))
        assertEquals(attempts, calls.get())
        val marker = store.layout.negativeMarkerFile("example.com")
        assertTrue("Permanent marker must exist before aging", marker.isFile)
        assertTrue(
            "Permanent marker timestamp must be updated",
            marker.setLastModified(System.currentTimeMillis() - DomainIconStore.NEGATIVE_CACHE_TTL_MS - 1000)
        )
        val data = bytes()
        httpDownloader = { calls.incrementAndGet(); IconDownloadResult(data, true, false) }
        assertNotNull(icons.loadOrFetchIcon("example.com"))
        assertFalse(icons.shouldSkipFetch("example.com"))
    }

    @Test fun transientFailureBacksOffAndClearAllowsRetry() = runBlocking {
        emptyHtml()
        val calls = AtomicInteger()
        httpDownloader = { calls.incrementAndGet(); IconDownloadResult(null, false, true) }
        assertNull(icons.loadOrFetchIcon("example.com"))
        val attempts = calls.get()
        assertTrue(attempts > 0)
        assertNull(icons.loadOrFetchIcon("example.com"))
        assertEquals(attempts, calls.get())
        assertFalse(icons.shouldSkipFetch("example.com"))
        icons.clearCache()
        val data = bytes()
        httpDownloader = { IconDownloadResult(data, true, false) }
        assertNotNull(icons.loadOrFetchIcon("example.com"))
    }

    @Test fun clearRejectsOldSuccessAndReturnsMissToActiveCaller() = runBlocking {
        emptyHtml()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val data = bytes()
        httpDownloader = {
            started.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
            IconDownloadResult(data, true, false)
        }
        val request = async { icons.loadOrFetchIcon("example.com") }
        withTimeout(5000) { started.await() }
        icons.clearCache()
        release.countDown()
        assertNull(withTimeout(5000) { request.await() })
        assertNull(icons.getCachedIconBitmap("example.com"))
        assertFalse(store.layout.domainIconFile("example.com").exists())
        assertFalse(icons.shouldSkipFetch("example.com"))
    }
}
