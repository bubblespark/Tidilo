package me.bubblespark.tidilo.ui.components

import androidx.compose.ui.Modifier
import org.junit.Assert.*
import org.junit.Test

class NativeOverlayModifierTest {
    private data class Element(val name: String) : Modifier.Element

    @Test fun nativeModifiersStayOutsideBackgroundInBothFoldDirections() {
        val caller = Element("caller")
        val background = Element("background")
        val anchor = Element("native-anchor")
        val scale = Element("native-scale")
        val chain = caller.withNativeOverlayBackground(background).then(anchor).then(scale)
        assertEquals(listOf(caller, anchor, scale, background), chain.foldIn(emptyList<Modifier.Element>()) { list, item -> list + item })
        assertEquals(listOf(background, scale, anchor, caller), chain.foldOut(emptyList<Modifier.Element>()) { item, list -> list + item })
        assertTrue(chain.any { it == background })
        assertTrue(chain.all { it is Element })
        assertSame(chain, chain.then(Modifier))
    }
}
