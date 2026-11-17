package me.bubblespark.tidilo.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class LibraryRadialMenuStateTest {
    @Test fun onlyActiveCancellationRequestsFocusAndRepeatedCancellationIsSilent() {
        val state = LibraryRadialMenuState()
        var callbacks = 0
        state.onCancellation = { callbacks++ }
        state.cancel()
        assertEquals(0, callbacks)
        state.beginPress(1, "window")
        state.cancel()
        state.cancel()
        assertEquals(1, callbacks)
        assertNull(state.endPress(1, LibraryShortcut.SEARCH, true, "window").action)
        state.activateTrigger()
        state.cancel()
        assertEquals(2, callbacks)
    }
    @Test fun stationaryPressOpensOnlyOnReleaseAndSemanticActivationIsIndependent() {
        val state = LibraryRadialMenuState()
        state.beginPress(1, "window")
        state.movePress(1, false, null, "window")
        assertFalse(state.isOpen)
        assertTrue(state.endPress(1, null, true, "window").opened)
        assertEquals(LibraryShortcut.SEARCH, state.activateItem(LibraryShortcut.SEARCH).action)
        assertNull(state.activateItem(LibraryShortcut.SEARCH).action)
    }

    @Test fun cancellationAndChangedGeometryInvalidateLateRelease() {
        for (reason in listOf("cancel", "geometry", "second pointer")) {
            val state = LibraryRadialMenuState()
            state.beginPress(1, "window")
            state.movePress(1, true, LibraryShortcut.DOMAIN, "window")
            when (reason) {
                "cancel" -> state.cancel()
                "geometry" -> state.movePress(1, true, LibraryShortcut.DOMAIN, "resized")
                else -> state.beginPress(2, "window")
            }
            assertNull(reason, state.endPress(1, LibraryShortcut.DOMAIN, true, "window").action)
            assertFalse(reason, state.isOpen)
        }
    }

    @Test fun gapAndCloseCancelAndEntryFeedbackDoesNotRepeat() {
        val state = LibraryRadialMenuState()
        state.beginPress(1, "window")
        assertEquals(LibraryShortcut.SEARCH, state.movePress(1, true, LibraryShortcut.SEARCH, "window").entered)
        assertNull(state.movePress(1, true, LibraryShortcut.SEARCH, "window").entered)
        assertNull(state.movePress(1, true, null, "window").entered)
        assertEquals(LibraryShortcut.SEARCH, state.movePress(1, true, LibraryShortcut.SEARCH, "window").entered)
        assertNull(state.endPress(1, null, true, "window").action)
        assertFalse(state.isOpen)
        state.beginPress(2, "window")
        state.movePress(2, true, LibraryShortcut.DOMAIN, "window")
        state.movePress(2, true, null, "window")
        assertNull(state.endPress(2, null, false, "window").action)
        assertFalse(state.isOpen)
    }
    @Test fun swipeCommitsOnlyReleaseTargetOnce() {
        val state = LibraryRadialMenuState()
        state.beginPress(1L, "window")
        assertFalse(state.isOpen)
        assertTrue(state.movePress(1L, true, LibraryShortcut.SEARCH, "window").opened)
        assertNull(state.movePress(1L, true, LibraryShortcut.DOMAIN, "window").action)
        assertEquals(LibraryShortcut.DOMAIN, state.endPress(1L, LibraryShortcut.DOMAIN, false, "window").action)
        assertNull(state.endPress(1L, LibraryShortcut.DOMAIN, false, "window").action)
        assertFalse(state.isOpen)
    }
}
