package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Test

class DomainIconManagerTest {

    @Test
    fun removeIconNotifiesOnlyItsDomainWithoutGlobalInvalidation() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_notification_test").toFile()
        val received = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED
        ) {
            DomainIconManager.iconUpdatedDomain.collect { received.add(it) }
        }
        try {
            val context = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }
            val initialVersion = DomainIconManager.iconUpdateVersion.value
            DomainIconManager.removeIcon(context, " GitHub.COM ")
            DomainIconManager.removeIcon(context, "github.com")
            kotlinx.coroutines.withTimeout(5_000) {
                while (received.size < 2) kotlinx.coroutines.yield()
            }
            assertEquals(listOf("github.com", "github.com"), received)
            assertEquals(initialVersion, DomainIconManager.iconUpdateVersion.value)
            DomainIconManager.clearCache(context)
            assertTrue(DomainIconManager.iconUpdateVersion.value > initialVersion)
            assertEquals(2, received.size)
        } finally {
            collector.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun extractDomain_removesWwwAndLowerCase() {
        assertEquals("github.com", DomainIconManager.extractDomain("https://www.github.com/torvalds/linux"))
        assertEquals("github.com", DomainIconManager.extractDomain("https://github.com/torvalds/linux"))
        assertEquals("developer.android.com", DomainIconManager.extractDomain("https://developer.android.com/reference/kotlin/"))
        assertEquals("zenn.dev", DomainIconManager.extractDomain("http://zenn.dev/articles/12345"))
        assertEquals("qiita.com", DomainIconManager.extractDomain("qiita.com/items/abc"))
        assertEquals("news.yahoo.co.jp", DomainIconManager.extractDomain("https://news.yahoo.co.jp/articles/123"))
    }

    @Test
    fun extractDomain_handlesPortsAndSubdomains() {
        assertEquals("localhost", DomainIconManager.extractDomain("http://localhost:8080/test"))
        assertEquals("sub.domain.co.jp", DomainIconManager.extractDomain("https://www.sub.domain.co.jp:443/path?query=1#hash"))
    }

    @Test
    fun extractDomain_returnsNullForInvalidUrl() {
        assertNull(DomainIconManager.extractDomain("   "))
    }

    @Test
    fun constants_verifyResolutionAndTimeoutSpecifications() {
        assertEquals(96, DomainIconManager.MIN_ICON_SIZE)
        assertEquals(384, DomainIconManager.MAX_ICON_SIZE)
        assertEquals(10_000L, DomainIconManager.DOMAIN_TIMEOUT_MS)
    }

    @Test
    fun generateDomainAccentColor_returnsConsistentColorForDomain() {
        val color1 = DomainIconManager.generateDomainAccentColor("github.com")
        val color2 = DomainIconManager.generateDomainAccentColor("github.com")
        assertEquals(color1, color2)

        val zennColor = DomainIconManager.generateDomainAccentColor("zenn.dev")
        assertNotNull(zennColor)
    }

    @Test
    fun testClearCacheIncrementsVersion() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_icon_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }
            val initialVersion = DomainIconManager.iconUpdateVersion.value
            val initialGen = DomainIconManager.getCurrentGeneration()
            DomainIconManager.clearCache(fakeContext)
            assertTrue(DomainIconManager.iconUpdateVersion.value > initialVersion)
            assertTrue(DomainIconManager.getCurrentGeneration() > initialGen)
        } finally {
            tempDir.deleteRecursively()
        }
    }


    @Test
    fun extractDominantColor_whiteMonochrome_returnsPlatinumWhite() {
        val pixels = IntArray(24 * 24) { 0xFFFFFFFF.toInt() }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "github.com")
        assertEquals(DomainIconManager.PLATINUM_WHITE, color)
    }

    @Test
    fun extractDominantColor_blackMonochrome_returnsSlateGray() {
        val pixels = IntArray(24 * 24) { 0xFF000000.toInt() }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "x.com")
        assertEquals(DomainIconManager.SLATE_GRAY, color)
    }

    @Test
    fun extractDominantColor_whiteSymbolOnTransparent_returnsPlatinumWhite() {
        // Transparent background with white symbol
        val pixels = IntArray(24 * 24) { index ->
            if (index % 2 == 0) 0x00000000 else 0xFFFFFFFF.toInt()
        }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "github.com")
        assertEquals(DomainIconManager.PLATINUM_WHITE, color)
    }

    @Test
    fun extractDominantColor_blackSymbolOnTransparent_returnsSlateGray() {
        // Transparent background with black symbol
        val pixels = IntArray(24 * 24) { index ->
            if (index % 2 == 0) 0x00000000 else 0xFF000000.toInt()
        }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "notion.so")
        assertEquals(DomainIconManager.SLATE_GRAY, color)
    }

    @Test
    fun extractDominantColor_chromaticRedIcon_returnsLegibleRed() {
        val pixels = IntArray(24 * 24) { 0xFFFF0000.toInt() }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "youtube.com")
        assertNotEquals(DomainIconManager.PLATINUM_WHITE, color)
        assertNotEquals(DomainIconManager.SLATE_GRAY, color)
        assertTrue(color.red > color.green && color.red > color.blue)
    }

    @Test
    fun extractDominantColor_chromaticGreenIcon_returnsLegibleGreen() {
        val pixels = IntArray(24 * 24) { 0xFF1DB954.toInt() } // Spotify green
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "spotify.com")
        assertNotEquals(DomainIconManager.PLATINUM_WHITE, color)
        assertNotEquals(DomainIconManager.SLATE_GRAY, color)
        assertTrue(color.green > color.red)
    }

    @Test
    fun extractDominantColor_semiTransparentWhiteIcon_returnsPlatinumWhite() {
        val alpha50 = 50 shl 24
        val pixels = IntArray(24 * 24) { alpha50 or 0x00FFFFFF }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "github.com")
        assertEquals(DomainIconManager.PLATINUM_WHITE, color)
    }

    @Test
    fun extractDominantColor_semiTransparentBlackIcon_returnsSlateGray() {
        val alpha50 = 50 shl 24
        val pixels = IntArray(24 * 24) { alpha50 or 0x00000000 }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "x.com")
        assertEquals(DomainIconManager.SLATE_GRAY, color)
    }

    @Test
    fun extractDominantColor_lowChromaDarkNeutralIcon_returnsSlateGray() {
        // RGB (128, 108, 108): HSV value is ~0.502 (>= 0.5), but average RGB brightness is ~0.450 (< 0.5).
        // It fails chromatic scoring and reaches neutral path, selecting SLATE_GRAY.
        val lowChromaPixel = (0xFF shl 24) or (128 shl 16) or (108 shl 8) or 108
        val pixels = IntArray(24 * 24) { lowChromaPixel }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "neutral.org")
        assertEquals(DomainIconManager.SLATE_GRAY, color)
    }

    @Test
    fun extractDominantColor_semiTransparentRedIcon_returnsLegibleRed() {
        // Uniform alpha=50 red icon (bestScore ~ 0.137) should scale threshold by opacity and retain brand red
        val alpha50 = 50 shl 24
        val pixels = IntArray(24 * 24) { alpha50 or 0x00FF0000 }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "youtube.com")
        assertNotEquals(DomainIconManager.PLATINUM_WHITE, color)
        assertNotEquals(DomainIconManager.SLATE_GRAY, color)
        assertTrue(color.red > color.green)
    }

    @Test
    fun extractDominantColor_transparentIcon_fallsBackToHashAccent() {
        val pixels = IntArray(24 * 24) { 0x00000000 }
        val color = DomainIconManager.extractDominantColorFromPixels(pixels, "example.com")
        val expected = DomainIconManager.generateDomainAccentColor("example.com")
        assertEquals(expected, color)
    }

    @Test
    fun colorToHsv_computesCorrectHsv() {
        val hsv = FloatArray(3)

        // Pure red
        DomainIconManager.colorToHsv(0xFFFF0000.toInt(), hsv)
        assertEquals(0f, hsv[0], 0.01f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        // Pure white
        DomainIconManager.colorToHsv(0xFFFFFFFF.toInt(), hsv)
        assertEquals(0f, hsv[1], 0.01f) // Saturation 0
        assertEquals(1f, hsv[2], 0.01f) // Value 1

        // Pure black
        DomainIconManager.colorToHsv(0xFF000000.toInt(), hsv)
        assertEquals(0f, hsv[1], 0.01f) // Saturation 0
        assertEquals(0f, hsv[2], 0.01f) // Value 0
    }

    @Test
    fun iconUpdateVersion_initialValueIsNonNegative() {
        assertTrue(DomainIconManager.iconUpdateVersion.value >= 0L)
    }

    @Test
    fun constants_verifyNegativeCacheTtl() {
        assertEquals(14L * 24 * 60 * 60 * 1000L, DomainIconManager.NEGATIVE_CACHE_TTL_MS)
    }

    @Test
    fun shouldSkipFetch_lifecycleAndTtlVerification() {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_negative_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val domain = "example.com"

            // 1. Initially no marker: shouldSkipFetch returns false
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, domain))

            // 2. Write negative marker: shouldSkipFetch returns true
            DomainIconManager.writeNegativeMarker(fakeContext, domain)
            assertTrue(DomainIconManager.shouldSkipFetch(fakeContext, domain))

            // 3. Remove marker: shouldSkipFetch returns false
            DomainIconManager.removeNegativeMarker(fakeContext, domain)
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, domain))

            // 4. Write marker and expire TTL: shouldSkipFetch returns false and deletes marker
            DomainIconManager.writeNegativeMarker(fakeContext, domain)
            val markerFile = java.io.File(fakeContext.cacheDir, "domain_icons/$domain.none")
            assertTrue(markerFile.exists())

            // Expire marker to 15 days ago
            val expiredTime = System.currentTimeMillis() - DomainIconManager.NEGATIVE_CACHE_TTL_MS - 86_400_000L
            markerFile.setLastModified(expiredTime)

            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, domain))
            assertFalse(markerFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun clearAllNegativeMarkers_removesAllNoneFiles() {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_clear_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            DomainIconManager.writeNegativeMarker(fakeContext, "domain1.com")
            DomainIconManager.writeNegativeMarker(fakeContext, "domain2.com")
            assertTrue(DomainIconManager.shouldSkipFetch(fakeContext, "domain1.com"))
            assertTrue(DomainIconManager.shouldSkipFetch(fakeContext, "domain2.com"))

            DomainIconManager.clearAllNegativeMarkers(fakeContext)
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, "domain1.com"))
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, "domain2.com"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_networkFailure_doesNotWriteNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_offline_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "offline-example.com"
            val testUrl = "https://$testDomain/article"

            // Simulate offline network failure (no server contact)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = false) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = false) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertTrue(result.hadTransientFailure)
            // Transient network failure without server contact must NOT write a negative marker
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_serverContactedWithNoUsableIcon_writesNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_server_contact_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "no-icon-example.com"
            val testUrl = "https://$testDomain"

            // Simulate successful server contact (e.g. 404 or page without icons)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = true) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertFalse(result.hadTransientFailure)
            // Completed discovery with server contact but no usable icon MUST write a negative marker
            assertTrue(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_transientCandidateFailure_doesNotWriteNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_candidate_failure_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "candidate-fail-example.com"
            val testUrl = "https://$testDomain"

            // HTML discovery succeeds (server contact = true), but candidate download fails transiently (server contact = false)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(listOf("https://$testDomain/icon.png"), hasServerContact = true) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = false) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertTrue(result.hadTransientFailure)
            // Transient download failure must prevent writing negative marker
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_transientDiscoveryFailure_doesNotWriteNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_discovery_failure_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "discovery-fail-example.com"
            val testUrl = "https://$testDomain"

            // HTML discovery has transient failure, but candidate download responds with HTTP 404 (server contact = true)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = false, hasTransientFailure = true) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertTrue(result.hadTransientFailure)
            // Transient HTML discovery failure must prevent writing negative marker
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_recordFailureMarkerFalse_doesNotWriteMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_no_marker_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "multi-url-example.com"
            val testUrl = "https://$testDomain/page1"

            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = true) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true) }

            // Intermediate URL of a domain group with recordFailureMarker = false
            val result = DomainIconManager.fetchAndSaveDomainIcon(
                context = fakeContext,
                url = testUrl,
                recordFailureMarker = false
            )
            assertFalse(result.success)
            assertFalse(result.hadTransientFailure)
            // Must NOT write marker when recordFailureMarker is false
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun isTransientHttpStatus_correctlyClassifiesStatusCodes() {
        // Temporary / transient HTTP statuses
        assertTrue(DomainIconManager.isTransientHttpStatus(408)) // Request Timeout
        assertTrue(DomainIconManager.isTransientHttpStatus(429)) // Too Many Requests (Rate limit)
        assertTrue(DomainIconManager.isTransientHttpStatus(500)) // Internal Server Error
        assertTrue(DomainIconManager.isTransientHttpStatus(502)) // Bad Gateway
        assertTrue(DomainIconManager.isTransientHttpStatus(503)) // Service Unavailable
        assertTrue(DomainIconManager.isTransientHttpStatus(504)) // Gateway Timeout

        // Permanent client errors or successful responses
        assertFalse(DomainIconManager.isTransientHttpStatus(200))
        assertFalse(DomainIconManager.isTransientHttpStatus(301))
        assertFalse(DomainIconManager.isTransientHttpStatus(400))
        assertFalse(DomainIconManager.isTransientHttpStatus(401))
        assertFalse(DomainIconManager.isTransientHttpStatus(403))
        assertFalse(DomainIconManager.isTransientHttpStatus(404))
        assertFalse(DomainIconManager.isTransientHttpStatus(410))
    }

    @Test
    fun fetchAndSaveDomainIcon_unsupportedMimeType_writesNegativeMarkerWhenCandidatesFail() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_mime_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "pdf-domain.com"
            val testUrl = "https://$testDomain/document.pdf"

            // Target page is a PDF (non-HTML MIME type): server contacted, but discovery completed without icon (non-transient)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = true, hasTransientFailure = false) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true, hasTransientFailure = false) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertFalse(result.hadTransientFailure)
            // Permanent non-HTML failure with completed root fallback and 404 candidates MUST write negative marker
            assertTrue(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_http429TooManyRequests_doesNotWriteNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_429_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "rate-limited-domain.com"
            val testUrl = "https://$testDomain"

            // HTML discovery hit 429 Too Many Requests (server contacted = true, but transient = true)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), hasServerContact = true, hasTransientFailure = true) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true, hasTransientFailure = false) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertTrue(result.hadTransientFailure)
            // HTTP 429 is temporary; must NOT write negative marker
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun fetchAndSaveDomainIcon_candidate5xxServerError_doesNotWriteNegativeMarker() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_5xx_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }

            val testDomain = "server-error-domain.com"
            val testUrl = "https://$testDomain"

            // HTML discovery found candidate, but downloading candidate returned 503 Service Unavailable (server contacted = true, transient = true)
            DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(listOf("https://$testDomain/icon.png"), hasServerContact = true, hasTransientFailure = false) }
            DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, hasServerContact = true, hasTransientFailure = true) }

            val result = DomainIconManager.fetchAndSaveDomainIcon(fakeContext, testUrl)
            assertFalse(result.success)
            assertTrue(result.hadTransientFailure)
            // Candidate 5xx error is temporary; must NOT write negative marker
            assertFalse(DomainIconManager.shouldSkipFetch(fakeContext, testDomain))
        } finally {
            DomainIconManager.htmlDiscoverer = null
            DomainIconManager.httpDownloader = null
            tempDir.deleteRecursively()
        }
    }
}
