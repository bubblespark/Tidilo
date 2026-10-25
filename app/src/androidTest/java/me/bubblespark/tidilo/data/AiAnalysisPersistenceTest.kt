package me.bubblespark.tidilo.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import org.junit.Assert.*
import org.junit.Test

/** Run the two methods in separate instrumentation processes, in the documented order. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class AiAnalysisPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistDisabledBeforeRestart() {
        val state = context.getSharedPreferences("issue125_verification", Context.MODE_PRIVATE)
        assertTrue(state.edit().putString("previous", AppConfig.aiAnalysisMode.value.code).commit())
        AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
        assertEquals("disable", context.getSharedPreferences("tidilo_app_config_prefs", Context.MODE_PRIVATE)
            .getString("ai_analysis_mode", null))
        // Flush pending apply() writes before terminating the process.
        assertTrue(context.getSharedPreferences("tidilo_app_config_prefs", Context.MODE_PRIVATE).edit().commit())
    }

    @Test fun restoreDisabledInFreshProcessAndRestoreOriginalPreference() {
        val state = context.getSharedPreferences("issue125_verification", Context.MODE_PRIVATE)
        val previous = state.getString("previous", null)
        try {
            assertNotNull("Run persistDisabledBeforeRestart first in another process", previous)
            assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
        } finally {
            AppConfig.setAiAnalysisMode(AiAnalysisMode.fromCode(previous))
            assertTrue(state.edit().clear().commit())
            context.deleteSharedPreferences("issue125_verification")
        }
    }
}
