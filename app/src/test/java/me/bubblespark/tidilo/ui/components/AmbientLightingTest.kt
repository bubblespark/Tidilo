package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientLightingTest {

    @Test
    fun initialState_defaultsToNull() {
        val state = AmbientLightingState()
        assertNull(state.activeColor.value)
    }

    @Test
    fun initialState_acceptsInitialColor() {
        val state = AmbientLightingState(Color.Cyan)
        assertEquals(Color.Cyan, state.activeColor.value)
    }

    @Test
    fun setAmbientColor_updatesActiveColor() {
        val state = AmbientLightingState()
        state.setAmbientColor(Color.Magenta)
        assertEquals(Color.Magenta, state.activeColor.value)

        state.setAmbientColor(null)
        assertNull(state.activeColor.value)
    }

    @Test
    fun clear_resetsActiveColorToNull() {
        val state = AmbientLightingState(Color.Blue)
        assertEquals(Color.Blue, state.activeColor.value)

        state.clear()
        assertNull(state.activeColor.value)
    }
}
