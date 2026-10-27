package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.rss.RssSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssDailySettingsTest {
    @Test fun dailyFetchDefaultsToEnabledAndCanBeDisabled() {
        val original = RssSettings()
        assertTrue(original.dailyEnabled)
        assertFalse(original.copy(dailyEnabled = false).dailyEnabled)
    }
}
