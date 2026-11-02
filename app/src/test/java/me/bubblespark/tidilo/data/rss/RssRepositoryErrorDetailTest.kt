package me.bubblespark.tidilo.data.rss

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssRepositoryErrorDetailTest {

    @Test
    fun rssErrorDetailFormatsHttpFailuresCorrectly() {
        assertEquals("HTTP 404", rssErrorDetail(RssHttpFailure(404)))
        assertEquals("HTTP 500", rssErrorDetail(RssHttpFailure(500)))
        assertEquals("HTTP 403", rssErrorDetail(RssHttpFailure(403)))
    }

    @Test
    fun rssErrorDetailFormatsSocketTimeoutExceptionCorrectly() {
        assertEquals("Connection timed out", rssErrorDetail(SocketTimeoutException()))
        assertEquals("Connection timed out", rssErrorDetail(SocketTimeoutException("")))
        assertEquals("Read timed out", rssErrorDetail(SocketTimeoutException("Read timed out")))
    }

    @Test
    fun rssErrorDetailFormatsConnectExceptionCorrectly() {
        assertEquals("Connection failed", rssErrorDetail(ConnectException()))
        assertEquals("Connection failed", rssErrorDetail(ConnectException("")))
        assertEquals("Connection refused", rssErrorDetail(ConnectException("Connection refused")))
        assertEquals("Connection failed", rssErrorDetail(IOException("Wrapped", ConnectException())))
    }

    @Test
    fun rssErrorDetailFormatsUnknownHostExceptionCorrectly() {
        assertEquals("Unable to resolve host: example.com", rssErrorDetail(UnknownHostException("example.com")))
        assertEquals("Unable to resolve host", rssErrorDetail(UnknownHostException()))
        assertEquals("Unable to resolve host", rssErrorDetail(UnknownHostException("")))
    }

    @Test
    fun rssErrorDetailFormatsGenericExceptionsAndTruncates() {
        assertEquals("Custom message", rssErrorDetail(IOException("Custom message")))
        assertEquals("IllegalStateException", rssErrorDetail(IllegalStateException()))
        val longMessage = "E".repeat(300)
        val detail = rssErrorDetail(Exception(longMessage))
        assertEquals(200, detail.length)
        assertEquals("E".repeat(200), detail)
    }

    @Test
    fun isTransientRssErrorIdentifiesTransientErrors() {
        assertTrue(isTransientRssError(SocketTimeoutException()))
        assertTrue(isTransientRssError(ConnectException()))
        assertTrue(isTransientRssError(UnknownHostException("feed.example.com")))
        assertTrue(isTransientRssError(RssHttpFailure(408)))
        assertTrue(isTransientRssError(RssHttpFailure(429)))
        assertTrue(isTransientRssError(RssHttpFailure(500)))
        assertTrue(isTransientRssError(RssHttpFailure(502)))
        assertTrue(isTransientRssError(RssHttpFailure(503)))
        assertTrue(isTransientRssError(IOException("Network error", SocketTimeoutException())))
    }

    @Test
    fun isTransientRssErrorIdentifiesFatalErrors() {
        assertFalse(isTransientRssError(RssHttpFailure(400)))
        assertFalse(isTransientRssError(RssHttpFailure(401)))
        assertFalse(isTransientRssError(RssHttpFailure(403)))
        assertFalse(isTransientRssError(RssHttpFailure(404)))
        assertFalse(isTransientRssError(RssHttpFailure(410)))
        assertFalse(isTransientRssError(IllegalArgumentException("Incomplete feed XML")))
        assertFalse(isTransientRssError(RssAcquisitionYield()))
        assertFalse(isTransientRssError(CancellationException("Cancelled")))
    }

    @Test
    fun transientErrorPreservesFeedEnabledStatus() {
        val feed = RssFeedEntity("feed-1", "https://example.com/rss", "Sample Feed", revision = 2, enabled = true)
        val now = 1000L
        val updated = applyRssFetchFailure(feed, SocketTimeoutException(), now)
        assertTrue(updated.enabled)
        assertEquals("Connection timed out", updated.error)
        assertEquals(now, updated.lastAttemptAt)
        assertEquals(2L, updated.revision)
        assertFalse(updated.acquisitionXmlDone)
    }

    @Test
    fun fatalErrorDisablesFeedAndRecordsExactError() {
        val feed = RssFeedEntity("feed-1", "https://example.com/rss", "Sample Feed", revision = 2, enabled = true)
        val now = 2000L
        val updated = applyRssFetchFailure(feed, RssHttpFailure(404), now)
        assertFalse(updated.enabled)
        assertEquals("HTTP 404", updated.error)
        assertEquals(now, updated.lastAttemptAt)
        assertEquals(3L, updated.revision)
        assertFalse(updated.acquisitionXmlDone)
    }

    @Test
    fun fatalXmlParseErrorDisablesFeedAndRecordsErrorMessage() {
        val feed = RssFeedEntity("feed-1", "https://example.com/rss", "Sample Feed", revision = 5, enabled = true)
        val now = 3000L
        val updated = applyRssFetchFailure(feed, IllegalArgumentException("Expected RSS, Atom, or RDF XML"), now)
        assertFalse(updated.enabled)
        assertEquals("Expected RSS, Atom, or RDF XML", updated.error)
        assertEquals(6L, updated.revision)
    }
}
