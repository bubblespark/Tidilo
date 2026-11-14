package me.bubblespark.tidilo.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigPreferenceMigrationTest {

    @Test
    fun readBooleanPreferenceWithLegacyFallback_migratesLegacyKeyAndRemovesOldKey() {
        val prefs = InMemorySharedPreferences(
            mutableMapOf(
                "nsfw_content_visible" to false,
                "nsfw_content_bookmarkable" to false
            )
        )

        val visible = AppConfig.readBooleanPreferenceWithLegacyFallback(
            prefs,
            key = "private_content_visible",
            legacyKey = "nsfw_content_visible",
            defaultValue = true
        )
        val bookmarkable = AppConfig.readBooleanPreferenceWithLegacyFallback(
            prefs,
            key = "private_content_bookmarkable",
            legacyKey = "nsfw_content_bookmarkable",
            defaultValue = true
        )

        assertFalse(visible)
        assertFalse(bookmarkable)
        assertTrue(prefs.contains("private_content_visible"))
        assertEquals(false, prefs.getBoolean("private_content_visible", true))
        assertFalse(prefs.contains("nsfw_content_visible"))
        assertTrue(prefs.contains("private_content_bookmarkable"))
        assertEquals(false, prefs.getBoolean("private_content_bookmarkable", true))
        assertFalse(prefs.contains("nsfw_content_bookmarkable"))
    }

    @Test
    fun readBooleanPreferenceWithLegacyFallback_prefersNewKeyOverLegacyKey() {
        val prefs = InMemorySharedPreferences(
            mutableMapOf(
                "private_content_visible" to true,
                "nsfw_content_visible" to false
            )
        )

        val visible = AppConfig.readBooleanPreferenceWithLegacyFallback(
            prefs,
            key = "private_content_visible",
            legacyKey = "nsfw_content_visible",
            defaultValue = false
        )

        assertTrue(visible)
        assertTrue(prefs.getBoolean("private_content_visible", false))
    }

    @Test
    fun readBooleanPreferenceWithLegacyFallback_returnsDefaultWhenNeitherKeyPresent() {
        val prefs = InMemorySharedPreferences()

        val value = AppConfig.readBooleanPreferenceWithLegacyFallback(
            prefs,
            key = "private_content_visible",
            legacyKey = "nsfw_content_visible",
            defaultValue = true
        )

        assertTrue(value)
        assertFalse(prefs.contains("private_content_visible"))
    }

    private class InMemorySharedPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) staged[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) staged[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) staged[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) staged[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) staged[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) staged[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removed.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removed.forEach { data.remove(it) }
                data.putAll(staged)
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
