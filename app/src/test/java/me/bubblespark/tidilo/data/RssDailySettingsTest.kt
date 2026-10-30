package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.rss.RssSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssDailySettingsTest {
    @Test fun dailyFetchDefaultsOffAndUnsetAndCanBeConfigured() {
        val original = RssSettings()
        assertFalse(original.dailyEnabled)
        assertTrue(original.triggerTimes.isEmpty())
        assertTrue(original.wifiOnly)
        assertTrue(original.copy(dailyEnabled = true).dailyEnabled)
    }
}
