package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainIconManagerTest {

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
}
