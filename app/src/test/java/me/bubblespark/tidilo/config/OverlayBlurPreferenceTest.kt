package me.bubblespark.tidilo.config

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBlurPreferenceTest {
    @Test
    fun absentCorruptAndReopenedPreferencesHaveDefinedBehavior() {
        val backing = mutableMapOf<String, Any?>()
        val prefs = InMemorySharedPreferences(backing)
        prefs.edit().clear().commit()
        assertTrue(AppConfig.readOverlayBlurPreference(prefs))

        prefs.edit().putString("overlay_blur_enabled", "invalid").commit()
        assertTrue(AppConfig.readOverlayBlurPreference(prefs))

        prefs.edit().putBoolean("overlay_blur_enabled", false).commit()
        assertFalse(AppConfig.readOverlayBlurPreference(InMemorySharedPreferences(backing)))
    }

    private class InMemorySharedPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            when (val value = data[key]) {
                null -> defValue
                is String -> value
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to String")
            }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            when (val value = data[key]) {
                null -> defValues
                is Set<*> -> (value as Set<String>).toMutableSet()
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to Set")
            }

        override fun getInt(key: String?, defValue: Int): Int =
            when (val value = data[key]) {
                null -> defValue
                is Int -> value
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to Int")
            }

        override fun getLong(key: String?, defValue: Long): Long =
            when (val value = data[key]) {
                null -> defValue
                is Long -> value
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to Long")
            }

        override fun getFloat(key: String?, defValue: Float): Float =
            when (val value = data[key]) {
                null -> defValue
                is Float -> value
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to Float")
            }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            when (val value = data[key]) {
                null -> defValue
                is Boolean -> value
                else -> throw ClassCastException("${value::class.java.name} cannot be cast to Boolean")
            }

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
