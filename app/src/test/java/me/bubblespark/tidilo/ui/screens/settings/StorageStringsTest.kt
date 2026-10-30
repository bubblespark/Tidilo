package me.bubblespark.tidilo.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
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

        val orphanedKeys = listOf(
            "summary_aicore",
            "summary_today",
            "summary_outcomes",
            "summary_pending_outcomes",
            "summary_debug",
            "summary_last_seven",
            "summary_recorded_since",
            "summary_quota",
            "summary_quota_day",
            "summary_calls",
            "summary_quota_note",
            "summary_day_calls",
            "summary_day_unrecorded",
            "summary_unrecorded",
            "summary_checked",
            "summary_physical_note",
            "summary_refresh",
            "summary_save_warning",
            "summary_privacy"
        )
        for (key in orphanedKeys) {
            assertFalse("Orphaned key $key should be removed from values/strings.xml", enXml.contains("name=\"$key\""))
        }
    }
}
