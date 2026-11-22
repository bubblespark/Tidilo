package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NewestFirstStatePaddingTest {
    // Landscape with a side cutout: the scaffold adds the inset to the 20dp screen padding.
    private val contentPadding = PaddingValues(start = 68.dp, top = 120.dp, end = 20.dp, bottom = 130.dp)

    @Test fun loadingAndErrorRowsFollowScaffoldInsets() {
        val padding = newestFirstStatePadding(contentPadding, LayoutDirection.Ltr)
        assertEquals(68.dp, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(20.dp, padding.calculateEndPadding(LayoutDirection.Ltr))
        assertEquals(120.dp, padding.calculateTopPadding())
        assertEquals(0.dp, padding.calculateBottomPadding())
    }

    @Test fun rightToLeftKeepsTheInsetOnTheSameSide() {
        val padding = newestFirstStatePadding(contentPadding, LayoutDirection.Rtl)
        assertEquals(68.dp, padding.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(20.dp, padding.calculateEndPadding(LayoutDirection.Rtl))
    }
}
