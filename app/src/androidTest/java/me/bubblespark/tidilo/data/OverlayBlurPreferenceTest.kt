package me.bubblespark.tidilo.data

import android.content.Context
import me.bubblespark.tidilo.config.AppConfig
import org.junit.Assert.*
import org.junit.Test

class OverlayBlurPreferenceTest {
    @Test fun absentCorruptAndReopenedPreferencesHaveDefinedBehavior() {
        val context = HeaderTestHost().context
        val name = "overlay_blur_test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            assertTrue(AppConfig.readOverlayBlurPreference(prefs))
            prefs.edit().putString("overlay_blur_enabled", "invalid").commit()
            assertTrue(AppConfig.readOverlayBlurPreference(prefs))
            prefs.edit().putBoolean("overlay_blur_enabled", false).commit()
            assertFalse(AppConfig.readOverlayBlurPreference(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        } finally { context.deleteSharedPreferences(name) }
    }
}
