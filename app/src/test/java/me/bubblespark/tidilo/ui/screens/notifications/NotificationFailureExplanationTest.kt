package me.bubblespark.tidilo.ui.screens.notifications

import me.bubblespark.tidilo.parser.ArticleContentUnavailable
import me.bubblespark.tidilo.ui.screens.notifications.FailureReason.*
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationFailureExplanationTest {
    @Test fun httpCodesInEveryStoredForm() {
        val expected = mapOf(500 to SERVER_UNAVAILABLE, 502 to SERVER_UNAVAILABLE, 503 to SERVER_UNAVAILABLE,
            504 to SERVER_UNAVAILABLE, 429 to RATE_LIMITED, 401 to ACCESS_DENIED, 403 to ACCESS_DENIED,
            404 to NOT_FOUND, 410 to NOT_FOUND, 418 to HTTP_OTHER, 301 to HTTP_OTHER)
        listOf("Article HTTP %d", "HTTP %d", "HTTP error %d").forEach { form ->
            expected.forEach { (code, reason) ->
                val raw = form.format(code)
                assertEquals(raw, reason, classifyFailure(raw))
            }
        }
    }

    @Test fun numbersOutsideAnHttpTokenDoNotMatch() {
        assertEquals(UNKNOWN, classifyFailure("Response exceeds limit of 503 bytes"))
    }

    @Test fun networkAndSecureConnection() {
        assertEquals(SECURE_CONNECTION, classifyFailure("SSL connection error"))
        assertEquals(SECURE_CONNECTION, classifyFailure("SSL handshake failed"))
        assertEquals(SECURE_CONNECTION, classifyFailure("Trust anchor for certification path not found."))
        listOf("Article acquisition timed out", "RSS request exceeded 30 seconds", "Connection failed",
            "Connection closed unexpectedly", "Unable to resolve host").forEach {
            assertEquals(it, NETWORK, classifyFailure(it))
        }
    }

    @Test fun hostNamesDoNotTriggerOtherReasons() {
        assertEquals(NETWORK, classifyFailure("Unable to resolve host: ssl.example.com"))
        assertEquals(NETWORK, classifyFailure("failed to connect to timeout.example.com/203.0.113.1 (port 443)"))
    }

    @Test fun contentAndSaveFailures() {
        assertEquals(NO_CONTENT, classifyFailure(ArticleContentUnavailable.MESSAGE))
        assertEquals(SAVE_FAILED, classifyFailure("Bookmark persistence failed: disk I/O error"))
    }

    @Test fun saveFailurePrefixWinsOverUnderlyingMessage() {
        listOf("connection pool has been closed", "timeout waiting for lock", "SSL error", "HTTP 503")
            .map { "Bookmark persistence failed: $it" }
            .forEach { assertEquals(it, SAVE_FAILED, classifyFailure(it)) }
        assertEquals(NETWORK, classifyFailure("connection pool has been closed"))
    }

    @Test fun blankAndNullAreUnknown() {
        listOf(null, "", "   ", "RSS feed is unavailable", "Could not fetch article").forEach {
            assertEquals(it.toString(), UNKNOWN, classifyFailure(it))
        }
    }

    @Test fun everyReasonHasItsOwnExplanation() {
        assertEquals(FailureReason.entries.size, FailureReason.entries.map { it.explanationRes }.toSet().size)
    }
}
