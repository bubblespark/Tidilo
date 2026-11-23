package me.bubblespark.tidilo.ui.screens.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverMotionTest {
    @Test fun centerItemIsTheOneNearestTheViewportCenter() {
        assertNull(centerItemKey(0, 1000, emptyList()))
        assertEquals("only", centerItemKey(0, 1000, listOf(VisibleItem("only", -400, 500))))
        val items = listOf(VisibleItem("hero", -300, 700), VisibleItem("deck", 440, 400), VisibleItem("gems", 880, 600))
        assertEquals("deck", centerItemKey(0, 1000, items))
        // Content padding moves the viewport; the center follows it.
        assertEquals("gems", centerItemKey(600, 1600, items))
    }
}
