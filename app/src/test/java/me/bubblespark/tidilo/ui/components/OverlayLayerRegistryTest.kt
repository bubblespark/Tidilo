package me.bubblespark.tidilo.ui.components

import org.junit.Assert.*
import org.junit.Test

class OverlayLayerRegistryTest {
    @Test fun selectsOnlyLowerLayersAndReleasesIdempotently() {
        val registry = OverlayLayerRegistry()
        val a = registry.register()
        val b = registry.register()
        val c = registry.register()
        assertTrue(a < b && b < c)
        assertEquals(listOf(a), registry.lowerLayers(b))
        registry.unregister(a)
        registry.unregister(a)
        assertEquals(2, registry.activeCount)
        assertEquals(emptyList<Long>(), registry.lowerLayers(b))
        registry.unregister(b)
        val d = registry.register()
        assertEquals(listOf(c), registry.lowerLayers(d))
        registry.unregister(c)
        registry.unregister(d)
        assertEquals(0, registry.activeCount)
    }

    @Test fun repeatedOpenCloseDoesNotRetainLayers() {
        val registry = OverlayLayerRegistry()
        repeat(100) { registry.unregister(registry.register()) }
        assertEquals(0, registry.activeCount)
    }
}
