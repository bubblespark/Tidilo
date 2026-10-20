package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.GridViewSymbolIcon
import me.bubblespark.tidilo.ui.components.ListSymbolIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModeTest {

    @Test
    fun testLibraryViewModeEntries() {
        val modes = LibraryViewMode.entries
        assertEquals(2, modes.size)
        assertTrue(modes.contains(LibraryViewMode.LIST))
        assertTrue(modes.contains(LibraryViewMode.GRID))
    }

    @Test
    fun testLibraryViewModeResourceIds() {
        assertEquals(R.string.library_view_mode_list, LibraryViewMode.LIST.labelRes)
        assertEquals(R.string.library_view_mode_grid, LibraryViewMode.GRID.labelRes)
    }

    @Test
    fun testLibraryViewModeIcons() {
        assertNotNull(LibraryViewMode.LIST.icon)
        assertEquals(ListSymbolIcon, LibraryViewMode.LIST.icon)
        assertNotNull(LibraryViewMode.GRID.icon)
        assertEquals(GridViewSymbolIcon, LibraryViewMode.GRID.icon)
    }
}
