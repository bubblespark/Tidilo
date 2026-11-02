package me.bubblespark.tidilo.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigThemeTest {

    @Test
    fun currentTheme_defaultsToDefault() {
        assertEquals(AppTheme.DEFAULT, AppConfig.currentTheme.value)
    }

    @Test
    fun setTheme_updatesStateFlow() {
        AppConfig.setTheme(AppTheme.DEFAULT)
        assertEquals(AppTheme.DEFAULT, AppConfig.currentTheme.value)
    }
}
