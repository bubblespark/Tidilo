package me.bubblespark.tidilo.ui.components

import org.junit.Assert.assertNotNull
import org.junit.Test

class CacheGenerationLocalsTest {

    @Test
    fun compositionLocalsAreInstantiated() {
        assertNotNull(LocalDomainIconCacheGeneration)
        assertNotNull(LocalOgImageCacheGeneration)
    }
}
