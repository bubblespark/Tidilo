package me.bubblespark.tidilo.ui.screens.settings

import java.io.File
import me.bubblespark.tidilo.config.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStringsTest {

    @Test
    fun themeStringsExistInEnglishResources() {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        val enXmlFile = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not find strings.xml in: $candidates")
        val enXml = enXmlFile.readText()

        assertTrue(
            "settings_theme_title should exist in English",
            enXml.contains("<string name=\"settings_theme_title\">Theme</string>")
        )
        assertTrue(
            "settings_theme_dialog_title should exist in English",
            enXml.contains("<string name=\"settings_theme_dialog_title\">Select Theme</string>")
        )
        assertTrue(
            "settings_theme_default should exist in English",
            enXml.contains("<string name=\"settings_theme_default\">Default</string>")
        )
        assertTrue(
            "settings_theme_modern should exist in English",
            enXml.contains("<string name=\"settings_theme_modern\">Modern</string>")
        )
    }

    @Test
    fun themeStringsExistInJapaneseResources() {
        val candidates = listOf(
            File("src/main/res/values-ja/strings.xml"),
            File("app/src/main/res/values-ja/strings.xml")
        )
        val jaXmlFile = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not find values-ja/strings.xml in: $candidates")
        val jaXml = jaXmlFile.readText()

        assertTrue(
            "settings_theme_title should exist in Japanese",
            jaXml.contains("<string name=\"settings_theme_title\">テーマ</string>")
        )
        assertTrue(
            "settings_theme_dialog_title should exist in Japanese",
            jaXml.contains("<string name=\"settings_theme_dialog_title\">テーマの選択</string>")
        )
        assertTrue(
            "settings_theme_default should exist in Japanese",
            jaXml.contains("<string name=\"settings_theme_default\">デフォルト</string>")
        )
        assertTrue(
            "settings_theme_modern should exist in Japanese",
            jaXml.contains("<string name=\"settings_theme_modern\">モダン</string>")
        )
    }

    @Test
    fun appThemeEntriesDefineValidProperties() {
        val defaultTheme = AppTheme.fromCode("default")
        assertEquals(AppTheme.DEFAULT, defaultTheme)
        assertEquals("default", defaultTheme.code)

        val modernTheme = AppTheme.fromCode("modern")
        assertEquals(AppTheme.MODERN, modernTheme)
        assertEquals("modern", modernTheme.code)
    }
}
