package me.bubblespark.tidilo.data.cache

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaColorPaletteTest {

    @Test
    fun colorToHsv_computesPrimaryAndNeutralColors() {
        val hsv = FloatArray(3)

        MediaColorPalette.colorToHsv(0xFFFF0000.toInt(), hsv)
        assertEquals(0f, hsv[0], 0.01f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        MediaColorPalette.colorToHsv(0xFF00FF00.toInt(), hsv)
        assertEquals(120f, hsv[0], 0.01f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        MediaColorPalette.colorToHsv(0xFF0000FF.toInt(), hsv)
        assertEquals(240f, hsv[0], 0.01f)
        assertEquals(1f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        MediaColorPalette.colorToHsv(0xFFFFFFFF.toInt(), hsv)
        assertEquals(0f, hsv[1], 0.01f)
        assertEquals(1f, hsv[2], 0.01f)

        MediaColorPalette.colorToHsv(0xFF000000.toInt(), hsv)
        assertEquals(0f, hsv[1], 0.01f)
        assertEquals(0f, hsv[2], 0.01f)
    }

    @Test
    fun extractDominantColorFromPixels_classifiesMonochromeChromaticAndTransparent() {
        val whitePixels = IntArray(16) { 0xFFFFFFFF.toInt() }
        assertEquals(MediaColorPalette.PLATINUM_WHITE, MediaColorPalette.extractDominantColorFromPixels(whitePixels, "github.com"))

        val blackPixels = IntArray(16) { 0xFF000000.toInt() }
        assertEquals(MediaColorPalette.SLATE_GRAY, MediaColorPalette.extractDominantColorFromPixels(blackPixels, "x.com"))

        val semiTransparentWhite = IntArray(16) { index ->
            if (index % 2 == 0) 0x00000000 else (80 shl 24) or 0x00FFFFFF
        }
        assertEquals(MediaColorPalette.PLATINUM_WHITE, MediaColorPalette.extractDominantColorFromPixels(semiTransparentWhite, "github.com"))

        val lowChromaDark = IntArray(16) { (0xFF shl 24) or (128 shl 16) or (108 shl 8) or 108 }
        assertEquals(MediaColorPalette.SLATE_GRAY, MediaColorPalette.extractDominantColorFromPixels(lowChromaDark, "neutral.org"))

        val spotifyGreen = IntArray(16) { 0xFF1DB954.toInt() }
        val extractedGreen = MediaColorPalette.extractDominantColorFromPixels(spotifyGreen, "spotify.com")
        assertNotEquals(MediaColorPalette.PLATINUM_WHITE, extractedGreen)
        assertNotEquals(MediaColorPalette.SLATE_GRAY, extractedGreen)
        assertTrue(extractedGreen.green > extractedGreen.red)

        val transparentPixels = IntArray(16) { 0x00000000 }
        assertEquals(
            MediaColorPalette.generateDomainAccentColor("example.com"),
            MediaColorPalette.extractDominantColorFromPixels(transparentPixels, "example.com")
        )
    }

    @Test
    fun ensureDarkThemeLegibility_boostsDarkNavyAndHighSaturationColors() {
        val darkNavy = Color(0xFF0A1446)
        val legibleNavy = MediaColorPalette.ensureDarkThemeLegibility(darkNavy)
        val hsv = FloatArray(3)
        val argb = ((legibleNavy.alpha * 255).toInt() shl 24) or
            ((legibleNavy.red * 255).toInt() shl 16) or
            ((legibleNavy.green * 255).toInt() shl 8) or
            (legibleNavy.blue * 255).toInt()
        MediaColorPalette.colorToHsv(argb, hsv)
        assertTrue("Expected boosted brightness for dark navy, got ${hsv[2]}", hsv[2] >= 0.88f)
        assertTrue("Expected moderated saturation for blue/violet, got ${hsv[1]}", hsv[1] <= 0.65f)

        val darkCrimson = Color(0xFF600505)
        val legibleCrimson = MediaColorPalette.ensureDarkThemeLegibility(darkCrimson)
        assertTrue("Expected lifted red channel brightness, got ${legibleCrimson.red}", legibleCrimson.red >= 0.7f)
    }

    @Test
    fun generateDomainAccentColor_isDeterministicAndOpaque() {
        val c1 = MediaColorPalette.generateDomainAccentColor("developer.android.com")
        val c2 = MediaColorPalette.generateDomainAccentColor("developer.android.com")
        assertEquals(c1, c2)
        assertEquals(1f, c1.alpha, 0.001f)
    }

    @Test
    fun extractDetailHeroPaletteFromPixels_samplesTopBottomBandsAndFallsBackOnInvalidDimensions() {
        val width = 20
        val height = 20
        val pixels = IntArray(width * height)
        val topAmber = (0xFF shl 24) or (240 shl 16) or (165 shl 8) or 20
        val bottomBlue = (0xFF shl 24) or (20 shl 16) or (150 shl 8) or 235
        for (y in 0 until height) {
            val c = if (y < height / 2) topAmber else bottomBlue
            for (x in 0 until width) {
                pixels[y * width + x] = c
            }
        }

        val palette = MediaColorPalette.extractDetailHeroPaletteFromPixels(pixels, width, height, "example.com")
        assertTrue(palette.topEdgeColor.red > palette.topEdgeColor.blue + 0.3f)
        assertTrue(palette.bottomEdgeColor.blue > palette.bottomEdgeColor.red + 0.3f)
        assertEquals(1f, palette.accentColor.alpha, 0.001f)

        val invalid = MediaColorPalette.extractDetailHeroPaletteFromPixels(IntArray(4), 10, 10, "example.com")
        val expectedFallback = MediaColorPalette.generateDomainAccentColor("example.com")
        assertEquals(DetailHeroPalette(expectedFallback, expectedFallback, expectedFallback), invalid)
    }
}
