package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGeometryTest {
    @Test fun sideInsetsCannotPushDetailActionsOutsideSafeWidth() {
        val safeWidth = 368.dp - 40.dp
        val offset = HeaderDefaults.detailToolbarOffset(368.dp, 40.dp)
        val actionWidth = HeaderDefaults.DetailActionSize * 5 + 10.dp
        assertTrue(safeWidth / 2 + actionWidth / 2 + offset <= safeWidth)
    }
}
