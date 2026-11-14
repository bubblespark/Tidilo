package me.bubblespark.tidilo.data.cache

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Sampled edge and accent palette for an `og:image` asset.
 */
@Immutable
data class DetailHeroPalette(
    val topEdgeColor: Color,
    val bottomEdgeColor: Color,
    val accentColor: Color
) {
    companion object {
        val Transparent = DetailHeroPalette(
            topEdgeColor = Color.Transparent,
            bottomEdgeColor = Color.Transparent,
            accentColor = Color.Transparent
        )
    }
}

/**
 * Pure pixel-level color quantization, HSV conversion, dark-theme legibility calibration,
 * and hero edge/accent palette extraction helpers.
 */
object MediaColorPalette {
    /**
     * Adaptive neutral colors for monochrome icons calibrated for dark-mode visibility.
     */
    val PLATINUM_WHITE = Color(0xFFE2E8F0) // Tailored for light monochrome icons (Tailwind Slate-200)
    val SLATE_GRAY = Color(0xFF94A3B8)     // Tailored for dark monochrome icons (Tailwind Slate-400)

    /**
     * Pure-Kotlin RGB to HSV converter for portable, testable color analysis without Android framework stubs.
     */
    fun colorToHsv(colorInt: Int, hsv: FloatArray) {
        val r = (colorInt ushr 16) and 0xFF
        val g = (colorInt ushr 8) and 0xFF
        val b = colorInt and 0xFF
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        val v = max
        val s = if (max == 0f) 0f else delta / max
        val h = when {
            delta == 0f -> 0f
            max == rf -> ((gf - bf) / delta) % 6f * 60f
            max == gf -> ((bf - rf) / delta + 2f) * 60f
            else -> ((rf - gf) / delta + 4f) * 60f
        }.let { if (it < 0f) it + 360f else it }

        hsv[0] = h
        hsv[1] = s
        hsv[2] = v
    }

    /**
     * Analyzes pixel array to extract a vibrant brand color or an adaptive monochrome neutral.
     */
    fun extractDominantColorFromPixels(pixels: IntArray, domain: String): Color {
        var bestColor = 0
        var bestScore = -1f
        val hsv = FloatArray(3)

        var visiblePixelCount = 0
        var totalVisibleWeight = 0f
        var maxVisibleWeight = 0f
        var totalVisibleBrightness = 0f
        var chromaticWeight = 0f

        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) continue

            val alphaWeight = alpha / 255f
            visiblePixelCount++
            totalVisibleWeight += alphaWeight
            maxVisibleWeight = max(maxVisibleWeight, alphaWeight)

            val rgbBrightness = (
                (((pixel ushr 16) and 0xFF) / 255f) +
                    (((pixel ushr 8) and 0xFF) / 255f) +
                    ((pixel and 0xFF) / 255f)
                ) / 3f
            totalVisibleBrightness += rgbBrightness * alphaWeight

            colorToHsv(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]

            if (saturation > 0.15f) {
                chromaticWeight += alphaWeight
            }

            // Avoid washed-out white or dark near-black for chromatic brand selection
            if (value < 0.15f || (saturation < 0.1f && value > 0.9f)) continue

            // Score by saturation, medium-high luminance, and alpha weight
            val score = saturation * (1f - abs(value - 0.7f)) * alphaWeight
            if (score > bestScore) {
                bestScore = score
                bestColor = pixel
            }
        }

        if (visiblePixelCount == 0 || totalVisibleWeight == 0f) {
            // Completely transparent image; fallback to hash-based accent
            return generateDomainAccentColor(domain)
        }

        val chromaticRatio = chromaticWeight / totalVisibleWeight
        return if (bestScore > 0.15f * maxVisibleWeight && chromaticRatio > 0.03f) {
            // Vibrant chromatic brand icon (e.g. YouTube, Spotify)
            val r = (bestColor ushr 16) and 0xFF
            val g = (bestColor ushr 8) and 0xFF
            val b = bestColor and 0xFF
            ensureDarkThemeLegibility(Color(r, g, b))
        } else {
            // Monochrome icon (e.g. GitHub, X, Notion)
            val averageBrightness = totalVisibleBrightness / totalVisibleWeight
            if (averageBrightness >= 0.5f) {
                PLATINUM_WHITE
            } else {
                SLATE_GRAY
            }
        }
    }

    /**
     * Calibrates accent color to ensure strong contrast, vibrant ambient glow, and legible text on dark backgrounds.
     * Prevents deep navy, dark blue, and low-luminance tones from dissolving into the dark theme surface.
     */
    fun ensureDarkThemeLegibility(color: Color): Color {
        val hsv = FloatArray(3)
        val argb = ((color.alpha * 255).toInt() shl 24) or
            ((color.red * 255).toInt() shl 16) or
            ((color.green * 255).toInt() shl 8) or
            (color.blue * 255).toInt()
        colorToHsv(argb, hsv)
        val hue = hsv[0]
        var saturation = hsv[1]
        var value = hsv[2]

        // Human eyes have lower perceived sensitivity to blue and violet (hue 190° - 275°)
        val isBlueOrViolet = hue in 190f..275f

        val minValue = if (isBlueOrViolet) 0.88f else 0.72f
        if (value < minValue) {
            value = minValue
        }

        // For deep dark blues, moderate saturation while boosting brightness to yield a luminous brand blue
        if (isBlueOrViolet && saturation > 0.65f) {
            saturation = 0.58f
            value = 0.92f
        } else if (saturation > 0.85f && value < 0.80f) {
            saturation = 0.75f
            value = 0.85f
        }

        return Color.hsv(hue, saturation, value)
    }

    /**
     * Generates a deterministic, aesthetically pleasing accent color based on domain name hash.
     */
    fun generateDomainAccentColor(domain: String): Color {
        val hash = domain.hashCode() and 0x7FFFFFFF
        val hue = (hash % 360).toFloat()
        val isBlue = hue in 190f..275f
        val sat = if (isBlue) 0.48f else 0.55f
        val v = if (isBlue) 0.92f else 0.85f
        return Color.hsv(hue, sat, v)
    }

    /**
     * Pure pixel-array extractor for top ~15% edge band, bottom ~15% edge band, and dominant accent color.
     */
    fun extractDetailHeroPaletteFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        domain: String
    ): DetailHeroPalette {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            val fallback = generateDomainAccentColor(domain)
            return DetailHeroPalette(fallback, fallback, fallback)
        }
        val bandRows = maxOf(1, (height * 0.15f).toInt())
        val topEdge = sampleBandAverageColor(pixels, width, 0, bandRows)
        val bottomEdge = sampleBandAverageColor(pixels, width, height - bandRows, height)
        val accent = extractDominantColorFromPixels(pixels, domain)
        return DetailHeroPalette(
            topEdgeColor = topEdge ?: accent,
            bottomEdgeColor = bottomEdge ?: accent,
            accentColor = accent
        )
    }

    private fun sampleBandAverageColor(
        pixels: IntArray,
        width: Int,
        startRow: Int,
        endRowExclusive: Int
    ): Color? {
        var rSum = 0f
        var gSum = 0f
        var bSum = 0f
        var weightSum = 0f
        for (y in startRow until endRowExclusive) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val pixel = pixels[rowOffset + x]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha == 0) continue
                val w = alpha / 255f
                rSum += ((pixel ushr 16) and 0xFF) / 255f * w
                gSum += ((pixel ushr 8) and 0xFF) / 255f * w
                bSum += (pixel and 0xFF) / 255f * w
                weightSum += w
            }
        }
        if (weightSum <= 0f) return null
        return Color(
            red = (rSum / weightSum).coerceIn(0f, 1f),
            green = (gSum / weightSum).coerceIn(0f, 1f),
            blue = (bSum / weightSum).coerceIn(0f, 1f),
            alpha = 1f
        )
    }
}
