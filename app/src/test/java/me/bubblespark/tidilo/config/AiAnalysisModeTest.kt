package me.bubblespark.tidilo.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AiAnalysisModeTest {
    @Test fun unknownOrMissingPreferenceDefaultsToAiCore() {
        assertEquals(AiAnalysisMode.AICORE, AiAnalysisMode.fromCode(null))
        assertEquals(AiAnalysisMode.AICORE, AiAnalysisMode.fromCode("unknown"))
    }

    @Test fun persistedCodesRoundTrip() {
        for (mode in AiAnalysisMode.entries) assertEquals(mode, AiAnalysisMode.fromCode(mode.code))
    }
}
