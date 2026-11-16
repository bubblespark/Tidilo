package me.bubblespark.tidilo.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class LibraryRadialMenuGeometryTest {
    @Test fun popupAvoidsOtherTargetsAndUsesLeftSpaceInShortWindows() {
        val geometry = LibraryRadialMenuGeometry(1f)
        assertEquals(RadialPoint(200, 274), geometry.popup(RadialPoint(300, 500), LibraryShortcut.SEARCH,
            100, 40, 0, 0, 400, 800, 8))
        assertEquals(RadialPoint(244, 60), geometry.popup(RadialPoint(600, 230), LibraryShortcut.NEWEST_FIRST,
            170, 46, 30, 60, 700, 300, 8))
    }
    @Test fun densityInsetsAndPopupStayInTheSafeAreaWithoutShrinkingFan() {
        val geometry = LibraryRadialMenuGeometry(2f)
        assertEquals(112, geometry.diameter)
        val anchor = geometry.anchor(800, 1600, 30, 70, 48)
        assertEquals(RadialPoint(666, 1426), anchor)
        assertTrue(geometry.fits(anchor, 20, 50, 770, 1530))
        assertFalse(geometry.fits(RadialPoint(100, 100), 0, 0, 200, 200))
        val popup = geometry.popup(anchor, LibraryShortcut.COLLECTION, 700, 200, 20, 50, 770, 1530, 16)
        assertTrue(popup.x >= 20 && popup.x + 700 <= 770)
        assertTrue(popup.y >= 50 && popup.y + 200 <= 1530)
        assertEquals(RadialPoint(-300, 0), geometry.centers.getValue(LibraryShortcut.COLLECTION))
    }
    @Test fun compactFanUsesSharedCircularTargets() {
        val geometry = LibraryRadialMenuGeometry(1f)
        assertEquals(RadialPoint(-150, 0), geometry.centers.getValue(LibraryShortcut.COLLECTION))
        assertEquals(RadialPoint(-130, -75), geometry.centers.getValue(LibraryShortcut.RSS))
        assertEquals(RadialPoint(-75, -130), geometry.centers.getValue(LibraryShortcut.DOMAIN))
        assertEquals(RadialPoint(0, -150), geometry.centers.getValue(LibraryShortcut.NEWEST_FIRST))
        assertEquals(RadialPoint(-52, -52), geometry.centers.getValue(LibraryShortcut.SEARCH))
        assertEquals(LibraryShortcut.DOMAIN, geometry.targetAt(-75f, -130f))
        assertNull(geometry.targetAt(-27f, -27f))
        assertNull(geometry.targetAt(0f, 0f))
        assertNull(geometry.targetAt(-100f, -20f))
        val centers = geometry.centers.values.toList() + RadialPoint(0, 0)
        centers.forEachIndexed { index, point ->
            centers.drop(index + 1).forEach { other ->
                assertTrue(point.distanceTo(other) > geometry.diameter)
            }
        }
    }
}
