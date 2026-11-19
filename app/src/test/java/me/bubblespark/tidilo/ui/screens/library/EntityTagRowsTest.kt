package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class EntityTagRowsTest {
    @Test fun keepsAllTagsThatFitExactly() {
        assertEquals(listOf(0..1, 2..3, 4..5), entityTagRows(List(6) { 45 }, 100, 10))
    }

    @Test fun countsTheTagDisplacedByTheOverflowLabel() {
        val visible = visibleEntityTagCount(List(7) { 45 }, 100, 10) { hidden ->
            if (hidden == 1) 50 else 45
        }
        assertEquals(5, visible)
    }
}
