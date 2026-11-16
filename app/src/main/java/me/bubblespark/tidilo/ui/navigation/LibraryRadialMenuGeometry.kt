package me.bubblespark.tidilo.ui.navigation

import kotlin.math.hypot
import kotlin.math.roundToInt

enum class LibraryShortcut { SEARCH, COLLECTION, RSS, DOMAIN, NEWEST_FIRST }

data class RadialPoint(val x: Int, val y: Int) {
    fun distanceTo(other: RadialPoint): Float = hypot((x - other.x).toFloat(), (y - other.y).toFloat())
}

/** Rounded once, then shared by absolute placement and circular hit testing. */
class LibraryRadialMenuGeometry(density: Float, val diameter: Int = (56 * density).roundToInt()) {
    private val radius = diameter / 2f
    val centers: Map<LibraryShortcut, RadialPoint> = linkedMapOf(
        LibraryShortcut.SEARCH to point(-73f / kotlin.math.sqrt(2f), -73f / kotlin.math.sqrt(2f), density),
        LibraryShortcut.COLLECTION to point(-150f, 0f, density),
        LibraryShortcut.RSS to point(-129.90381f, -75f, density),
        LibraryShortcut.DOMAIN to point(-75f, -129.90381f, density),
        LibraryShortcut.NEWEST_FIRST to point(0f, -150f, density)
    )

    fun inTrigger(x: Float, y: Float): Boolean = hypot(x, y) <= radius

    fun centerAt(action: LibraryShortcut, progress: Float): RadialPoint {
        val center = centers.getValue(action)
        val fraction = progress.coerceIn(0f, 1f)
        return RadialPoint((center.x * fraction).roundToInt(), (center.y * fraction).roundToInt())
    }

    fun targetAt(x: Float, y: Float, progress: Float = 1f): LibraryShortcut? {
        if (inTrigger(x, y)) return null
        return LibraryShortcut.entries.reversed().firstOrNull { action ->
            val center = centerAt(action, progress)
            hypot(x - center.x, y - center.y) <= radius
        }
    }

    fun anchor(width: Int, height: Int, right: Int, bottom: Int, margin: Int): RadialPoint =
        RadialPoint(width - right - margin - diameter / 2, height - bottom - margin - diameter / 2)

    fun fits(anchor: RadialPoint, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        (centers.values + RadialPoint(0, 0)).all {
            anchor.x + it.x - radius >= left && anchor.x + it.x + radius <= right &&
                anchor.y + it.y - radius >= top && anchor.y + it.y + radius <= bottom
        }

    fun popup(anchor: RadialPoint, target: LibraryShortcut, width: Int, height: Int,
        left: Int, top: Int, right: Int, bottom: Int, gap: Int, progress: Float = 1f): RadialPoint {
        val centers = centers.mapValues { centerAt(it.key, progress) }
        val center = centers.getValue(target)
        val x = anchor.x + center.x - diameter / 2 - gap - width
        val y = anchor.y + center.y - diameter / 2 - gap - height
        val above = RadialPoint((anchor.x - width).coerceIn(left, maxOf(left, right - width)),
            anchor.y + centers.values.minOf { it.y } - diameter / 2 - gap - height)
        val beside = RadialPoint(anchor.x + centers.values.minOf { it.x } - diameter / 2 - gap - width,
            (anchor.y + center.y - height / 2).coerceIn(top, maxOf(top, bottom - height)))
        fun readable(point: RadialPoint): Boolean {
            if (point.x < left || point.y < top || point.x + width > right || point.y + height > bottom) return false
            return (centers.values + RadialPoint(0, 0)).all { item ->
                val cx = anchor.x + item.x
                val cy = anchor.y + item.y
                val nearestX = cx.coerceIn(point.x, point.x + width)
                val nearestY = cy.coerceIn(point.y, point.y + height)
                hypot((cx - nearestX).toFloat(), (cy - nearestY).toFloat()) >= radius + gap
            }
        }
        return listOf(RadialPoint(x, y), above, beside).firstOrNull(::readable)
            ?: RadialPoint(left, top)
    }

    private fun point(x: Float, y: Float, density: Float) =
        RadialPoint((x * density).roundToInt(), (y * density).roundToInt())
}
