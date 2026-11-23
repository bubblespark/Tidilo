package me.bubblespark.tidilo.config

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigDiscoverPreviewTest {

    @After
    fun tearDown() {
        AppConfig.setDiscoverPreviewEnabled(false)
    }

    @Test
    fun defaultsToFalse() {
        assertFalse(AppConfig.discoverPreviewEnabled.value)
    }

    @Test
    fun setUpdatesFlow() {
        AppConfig.setDiscoverPreviewEnabled(true)
        assertTrue(AppConfig.discoverPreviewEnabled.value)

        AppConfig.setDiscoverPreviewEnabled(false)
        assertFalse(AppConfig.discoverPreviewEnabled.value)
    }
}
