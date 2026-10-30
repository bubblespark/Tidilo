package me.bubblespark.tidilo.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageStringsTest {
    @Test
    fun storageStringsExistInEnglishResources() {
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml")
        )
        val enXmlFile = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not find strings.xml in: $candidates")
        val enXml = enXmlFile.readText()

        val requiredKeys = listOf(
            "summary_title",
            "summary_subtitle",
            "summary_total_bookmarks",
            "summary_local_storage_note",
            "summary_clear_cache_button",
            "summary_clear_cache_dialog_title",
            "summary_clear_cache_dialog_message",
            "summary_storage_privacy",
            "summary_icons",
            "summary_images",
            "summary_temp_data"
        )

        for (key in requiredKeys) {
            assertTrue("Key $key missing from values/strings.xml", enXml.contains("name=\"$key\""))
        }

        assertTrue("summary_title should be Storage in English", enXml.contains("<string name=\"summary_title\">Storage</string>"))
    }
}
