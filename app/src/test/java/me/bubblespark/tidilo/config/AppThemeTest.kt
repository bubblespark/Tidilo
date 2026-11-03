package me.bubblespark.tidilo.config

import me.bubblespark.tidilo.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {

    @Test
    fun fromCode_returnsDefault_forValidCode() {
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode("default"))
    }

    @Test
    fun fromCode_returnsModern_forValidCode() {
        assertEquals(AppTheme.MODERN, AppTheme.fromCode("modern"))
    }

    @Test
    fun fromCode_isCaseInsensitive() {
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode("DEFAULT"))
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode("Default"))
        assertEquals(AppTheme.MODERN, AppTheme.fromCode("MODERN"))
        assertEquals(AppTheme.MODERN, AppTheme.fromCode("Modern"))
    }

    @Test
    fun fromCode_fallsBackToDefault_forUnknownOrNullCode() {
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode(null))
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode("unknown"))
        assertEquals(AppTheme.DEFAULT, AppTheme.fromCode(""))
    }

    @Test
    fun defaultTheme_properties() {
        assertEquals("default", AppTheme.DEFAULT.code)
        assertEquals(R.string.settings_theme_default, AppTheme.DEFAULT.labelRes)
    }

    @Test
    fun modernTheme_properties() {
        assertEquals("modern", AppTheme.MODERN.code)
        assertEquals(R.string.settings_theme_modern, AppTheme.MODERN.labelRes)
    }
}
