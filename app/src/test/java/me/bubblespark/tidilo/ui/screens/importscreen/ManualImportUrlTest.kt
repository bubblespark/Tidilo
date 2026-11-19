package me.bubblespark.tidilo.ui.screens.importscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualImportUrlTest {
    @Test fun acceptsBareDomainsWithPortsAndPreservesExplicitHttpUrls() {
        assertEquals("https://example.com", ImportViewModel.resolveManualImportUrl("example.com"))
        assertEquals("https://example.com:8443/path?q=1#top",
            ImportViewModel.resolveManualImportUrl(" example.com:8443/path?q=1#top "))
        assertEquals("http://localhost:8080/path", ImportViewModel.resolveManualImportUrl("http://localhost:8080/path"))
    }

    @Test fun rejectsUnsupportedSchemesAndInvalidBareDomains() {
        for (input in listOf("", "example", "hello world", "ftp://example.com", "mailto:user@example.com",
            "user@example.com", "example.com:bad-port", "example.com://path")) {
            assertNull(input, ImportViewModel.resolveManualImportUrl(input))
        }
    }
}
