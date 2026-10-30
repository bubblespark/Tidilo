package me.bubblespark.tidilo.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssSettings
import org.junit.Assert.*
import org.junit.Test

class RssPreferencesPersistenceTest {
    private fun isolated(test: suspend (Context, SharedPreferences) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rss-test-${UUID.randomUUID()}"
        val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = prefs
        }
        try { test(context, prefs) } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun freshInstallDefaultsOffAndUnset() = isolated { context, _ ->
        val initial = RssPreferences(context)
        initial.initialize()
        assertFalse(initial.state.value.dailyEnabled)
        assertTrue(initial.state.value.triggerTimes.isEmpty())
        assertTrue(initial.state.value.wifiOnly)
    }

    @Test fun migrationResetsOnceAndPreservesUnrelatedPreferences() = isolated { context, prefs ->
        prefs.edit().putInt("hour", 9).putInt("minute", 45).putBoolean("dailyEnabled", true)
            .putBoolean("wifi", false).putLong("manualAt", 123).commit()
        val initial = RssPreferences(context)
        initial.initialize()
        assertFalse(initial.state.value.dailyEnabled)
        assertTrue(initial.state.value.triggerTimes.isEmpty())
        assertFalse(initial.state.value.wifiOnly)
        assertEquals(123L, initial.manualRequestedAt())
        assertFalse(prefs.contains("hour"))
        assertFalse(prefs.contains("minute"))
        assertEquals(1, prefs.getInt("scheduleVersion", 0))
        initial.save(RssSettings(listOf(LocalTime.of(18, 30), LocalTime.of(8, 0)), false, true))
        repeat(2) {
            val restored = RssPreferences(context)
            restored.initialize()
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), restored.state.value.triggerTimes)
            assertTrue(restored.state.value.dailyEnabled)
            assertFalse(restored.state.value.wifiOnly)
        }
        initial.save(initial.state.value.copy(triggerTimes = emptyList()))
        val empty = RssPreferences(context)
        empty.initialize()
        assertTrue(empty.state.value.dailyEnabled)
        assertTrue(empty.state.value.triggerTimes.isEmpty())
    }

    @Test fun failedMigrationAndSaveDoNotPublishSuccess() = isolated { context, prefs ->
        var failingCommit = true
        var commits = 0
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                    override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                    override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                    override fun remove(key: String) = apply { editor.remove(key) }
                    override fun commit(): Boolean { commits++; editor.commit(); return !failingCommit }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = failing
        }
        val initial = RssPreferences(wrapped)
        try { initial.initialize(); fail("Expected migration failure") } catch (_: IOException) { }
        assertTrue(initial.state.value.triggerTimes.isEmpty())
        failingCommit = false
        initial.initialize()
        assertEquals(2, commits)
        val saved = initial.state.value
        failingCommit = true
        try { initial.save(saved.copy(triggerTimes = listOf(LocalTime.NOON))); fail("Expected save failure") }
        catch (_: IOException) { }
        assertEquals(saved, initial.state.value)
    }

    @Test fun malformedListNeverRestoresLegacyTime() = isolated { context, prefs ->
        prefs.edit().putInt("scheduleVersion", 1).putString("triggerMinutes", "bad,-1,480,1440,480,1110")
            .putInt("hour", 9).putInt("minute", 45).putBoolean("dailyEnabled", false).commit()
        val restored = RssPreferences(context)
        restored.initialize()
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), restored.state.value.triggerTimes)
        assertFalse(restored.state.value.dailyEnabled)
    }
}
