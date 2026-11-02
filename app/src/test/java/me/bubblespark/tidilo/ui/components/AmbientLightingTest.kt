package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientLightingTest {

    @Test
    fun initialState_defaultsToNull() {
        val state = AmbientLightingState()
        assertNull(state.activeColor)
    }

    @Test
    fun initialState_acceptsInitialColor() {
        val state = AmbientLightingState(Color.Cyan)
        assertEquals(Color.Cyan, state.activeColor)
    }

    @Test
    fun setAmbientColor_withOwner_updatesActiveColor() {
        val state = AmbientLightingState()
        state.setAmbientColor("screenA", Color.Magenta)
        assertEquals(Color.Magenta, state.activeColor)

        state.setAmbientColor("screenA", null)
        assertNull(state.activeColor)
    }

    @Test
    fun setAmbientColor_multipleOwners_lifoStackAndRestoration() {
        val state = AmbientLightingState()

        // Screen A registers purple
        state.setAmbientColor("screenA", Color.Magenta)
        assertEquals(Color.Magenta, state.activeColor)

        // Screen B registers yellow on top of Screen A
        state.setAmbientColor("screenB", Color.Yellow)
        assertEquals(Color.Yellow, state.activeColor)

        // Screen B is popped/disposed: Screen A's color is automatically restored!
        state.removeAmbientColor("screenB")
        assertEquals(Color.Magenta, state.activeColor)

        // Screen A is disposed
        state.removeAmbientColor("screenA")
        assertNull(state.activeColor)
    }

    @Test
    fun removeAmbientColor_outgoingScreenDisposalDoesNotClobberTopOwner() {
        val state = AmbientLightingState()

        // Screen A registers purple
        state.setAmbientColor("screenA", Color.Magenta)

        // Screen B registers yellow on top
        state.setAmbientColor("screenB", Color.Yellow)
        assertEquals(Color.Yellow, state.activeColor)

        // Screen A's exit animation finishes and it is disposed:
        // Removing screen A MUST NOT affect screen B!
        state.removeAmbientColor("screenA")
        assertEquals(Color.Yellow, state.activeColor)
    }

    @Test
    fun setAmbientColor_transparentColorRemovesOwner() {
        val state = AmbientLightingState()
        state.setAmbientColor("screenA", Color.Magenta)
        assertEquals(Color.Magenta, state.activeColor)

        state.setAmbientColor("screenA", Color.Transparent)
        assertNull(state.activeColor)
    }

    @Test
    fun isEnabled_gateSuppressesActiveColorWhenFalse() {
        val state = AmbientLightingState()
        state.setAmbientColor("screenA", Color.Magenta)
        assertEquals(Color.Magenta, state.activeColor)

        state.isDestinationAllowed = false
        assertNull(state.effectiveActiveColor)

        state.isDestinationAllowed = true
        assertEquals(Color.Magenta, state.effectiveActiveColor)
    }

    @Test
    fun clear_resetsAllRequests() {
        val state = AmbientLightingState()
        state.setAmbientColor("screenA", Color.Magenta)
        state.setAmbientColor("screenB", Color.Cyan)

        state.clear()
        assertNull(state.activeColor)
    }

    @Test
    fun removeAmbientColor_detailExitRestoresDrillDownColorImmediately() {
        val state = AmbientLightingState()
        // Library drilldown registers purple
        state.setAmbientColor("library_drilldown", Color(0xFF8800FF))
        assertEquals(Color(0xFF8800FF), state.effectiveActiveColor)

        // Detail screen registers gold on top
        state.setAmbientColor("bookmark_detail", Color(0xFFFFCC00))
        assertEquals(Color(0xFFFFCC00), state.effectiveActiveColor)

        // On exit, detail owner is removed immediately
        state.removeAmbientColor("bookmark_detail")
        assertEquals(Color(0xFF8800FF), state.effectiveActiveColor)
    }
}
