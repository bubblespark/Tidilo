package me.bubblespark.tidilo.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.rss.RssPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssPreferencesPersistenceTest {
    @Test fun dailySettingDefaultsOnAndSurvivesRecreation() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "rss-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val initial = RssPreferences(isolated)
            initial.initialize()
            assertTrue(initial.state.value.dailyEnabled)
            initial.save(initial.state.value.copy(dailyEnabled = false))
            val restored = RssPreferences(isolated)
            restored.initialize()
            assertFalse(restored.state.value.dailyEnabled)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
