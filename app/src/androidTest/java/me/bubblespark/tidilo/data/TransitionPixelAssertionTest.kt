package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Exiting AnimatedContent is excluded from accessibility, even while still drawn, so exits are checked in pixels.

/** Asserts that most dark title pixels inside [bounds] of [before] are still dark in [during]. */
internal fun assertTextStillDrawn(before: Bitmap, during: Bitmap, bounds: Rect) {
    var dark = 0
    var retained = 0
    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
        val old = before.getPixel(x, y)
        if (Color.red(old) < 80 && Color.green(old) < 80 && Color.blue(old) < 80) {
            dark++
            val next = during.getPixel(x, y)
            if (Color.red(next) < 120 && Color.green(next) < 120 && Color.blue(next) < 120) retained++
        }
    }
    assertTrue("Fixture must contain rendered title pixels", dark > 50)
    assertTrue("Outgoing title pixels must survive fade-out: $retained / $dark", retained > dark * 0.6)
}

/**
 * Asserts that the accent-colored glyphs inside [bounds] of [before] survive in [during] with their hue,
 * after locating ONE translation by glyph shape (navigation shrinks horizontally and the centered title
 * block can move vertically). [density] is pixels per dp.
 */
internal fun assertAccentStillDrawn(before: Bitmap, during: Bitmap, bounds: Rect, density: Float) {
    data class Ink(val x: Int, val y: Int, val hue: Float)
    val mask = mutableListOf<Ink>()
    val oldHsv = FloatArray(3)
    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
        Color.colorToHSV(before.getPixel(x, y), oldHsv)
        if (oldHsv[1] > 0.35f) mask += Ink(x, y, oldHsv[0])
    }
    assertTrue("Fixture must contain accent-colored subtitle pixels", mask.size > 30)

    val maxX = (52 * density).toInt()
    val maxY = (24 * density).toInt()
    val area = Rect(maxOf(0, bounds.left - maxX), maxOf(0, bounds.top - maxY),
        minOf(during.width, bounds.right + maxX), minOf(during.height, bounds.bottom + maxY))
    val width = area.width()
    val pixels = IntArray(width * area.height())
    during.getPixels(pixels, 0, width, area.left, area.top, width, area.height())
    val ink = BooleanArray(pixels.size)
    val colored = BooleanArray(pixels.size)
    val hues = FloatArray(pixels.size)
    val luminances = pixels.map { pixel ->
        (0.2126f * Color.red(pixel) + 0.7152f * Color.green(pixel) + 0.0722f * Color.blue(pixel)) / 255f
    }
    val background = luminances.sorted()[luminances.size / 2]
    val hsv = FloatArray(3)
    pixels.forEachIndexed { index, pixel ->
        Color.colorToHSV(pixel, hsv)
        colored[index] = hsv[1] > 0.15f
        ink[index] = colored[index] || abs(luminances[index] - background) > 0.15f
        hues[index] = hsv[0]
    }
    val stride = width + 1
    val sums = IntArray(stride * (area.height() + 1))
    for (y in 0 until area.height()) for (x in 0 until width) {
        val index = (y + 1) * stride + x + 1
        sums[index] = (if (ink[y * width + x]) 1 else 0) + sums[index - 1] + sums[index - stride] - sums[index - stride - 1]
    }
    var bestScore = -1f
    var bestX = 0
    var bestY = 0
    fun score(dx: Int, dy: Int) {
        val left = bounds.left + dx - area.left
        val top = bounds.top + dy - area.top
        val right = left + bounds.width()
        val bottom = top + bounds.height()
        if (left < 0 || top < 0 || right > width || bottom > area.height()) return
        val count = sums[bottom * stride + right] - sums[top * stride + right] -
            sums[bottom * stride + left] + sums[top * stride + left]
        val overlap = mask.count { ink[(it.y + dy - area.top) * width + it.x + dx - area.left] }
        val shapeScore = 2f * overlap / (mask.size + count)
        if (shapeScore > bestScore) { bestScore = shapeScore; bestX = dx; bestY = dy }
    }
    val step = maxOf(1, density.toInt())
    for (dy in -maxY..maxY step step) for (dx in -maxX..maxX step step) score(dx, dy)
    val coarseX = bestX
    val coarseY = bestY
    for (dy in coarseY - step..coarseY + step) for (dx in coarseX - step..coarseX + step) score(dx, dy)
    assertTrue("Outgoing subtitle glyph shape must survive motion: $bestScore", bestScore > 0.65f)
    val retained = mask.count {
        val index = (it.y + bestY - area.top) * width + it.x + bestX - area.left
        val distance = abs(it.hue - hues[index])
        colored[index] && minOf(distance, 360f - distance) < 20f
    }
    assertTrue("Outgoing subtitle must retain its accent at translation ($bestX,$bestY): $retained / ${mask.size}", retained > mask.size * 0.35)
}

/** Self-test of the exit-frame pixel assertions on synthetic glyphs. */
class TransitionPixelAssertionTest {
    @Test fun accentAssertionTracksOneTranslationAndRejectsWrongColor() {
        val density = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val before = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        val during = before.copy(Bitmap.Config.ARGB_8888, true)
        val wrong = before.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply { color = Color.MAGENTA; textSize = 18f; isAntiAlias = true }
        try {
            before.eraseColor(Color.WHITE)
            during.eraseColor(Color.WHITE)
            wrong.eraseColor(Color.WHITE)
            Canvas(before).drawText("1 bookmark", 8f, 24f, paint)
            Canvas(during).drawText("1 bookmark", 30f, 42f, paint)
            val bounds = Rect(4, 6, 116, 28)
            assertAccentStillDrawn(before, during, bounds, density)
            for (color in listOf(Color.BLUE, Color.GRAY, Color.WHITE)) {
                wrong.eraseColor(Color.WHITE)
                paint.color = color
                Canvas(wrong).drawText("1 bookmark", 30f, 42f, paint)
                paint.color = Color.MAGENTA
                // Wrong hue, lost saturation, and missing text must not match a nearby label.
                Canvas(wrong).drawText("99 feeds", 8f, 65f, paint)
                assertThrows(AssertionError::class.java) { assertAccentStillDrawn(before, wrong, bounds, density) }
            }
        } finally {
            before.recycle()
            during.recycle()
            wrong.recycle()
        }
    }
}
