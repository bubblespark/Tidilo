package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.service.GeminiTaggingService
import org.junit.Assert.assertEquals
import org.junit.Test

class RssSummaryFallbackTest {
    @Test fun missingBlankAndMalformedSummariesKeepFeedDescription() {
        for (output in listOf("{}", "{\"summary\":\"   \"}", "not json")) {
            assertEquals("Feed description", GeminiTaggingService.parseAiResponse(output, "Title", null, "Feed description").summary)
            assertEquals("Title", GeminiTaggingService.parseAiResponse(output, "Title", null, "   ").summary)
        }
        assertEquals("AI summary", GeminiTaggingService.parseAiResponse("{\"summary\":\"AI summary\"}", "Title", null, "Feed description").summary)
    }
}
