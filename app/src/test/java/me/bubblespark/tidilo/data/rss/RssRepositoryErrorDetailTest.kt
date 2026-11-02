package me.bubblespark.tidilo.data.rss

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssRepositoryErrorDetailTest {

    private fun handshakeException(message: String, cause: Throwable? = null): SSLHandshakeException {
        return SSLHandshakeException(message).apply { cause?.let { initCause(it) } }
    }

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
    fun rssErrorDetailFormatsSslExceptionsCorrectly() {
        assertEquals("SSL connection error", rssErrorDetail(SSLException("")))
        assertEquals("Connection closed during TLS", rssErrorDetail(SSLException("Connection closed during TLS")))
        assertEquals("SSL handshake failed", rssErrorDetail(SSLHandshakeException("")))
        assertEquals("Cert path validator failed", rssErrorDetail(SSLHandshakeException("Cert path validator failed")))
        assertEquals("SSL certificate verification failed", rssErrorDetail(SSLPeerUnverifiedException("")))
        assertEquals("Hostname mismatch", rssErrorDetail(SSLPeerUnverifiedException("Hostname mismatch")))
        assertEquals("Cert path validator failed", rssErrorDetail(SSLException("Handshake error", SSLHandshakeException("Cert path validator failed"))))
        assertEquals("SSL handshake failed", rssErrorDetail(SSLException("Handshake error", SSLHandshakeException(""))))
        assertEquals("Handshake failed due to EOF", rssErrorDetail(handshakeException("Handshake failed due to EOF", EOFException())))
        assertEquals("Connection closed unexpectedly", rssErrorDetail(EOFException()))
        assertEquals("Handshake failed", rssErrorDetail(handshakeException("Handshake failed", SocketException("Connection reset by peer"))))
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
        assertTrue(isTransientRssError(java.net.SocketException("Connection reset")))
        assertTrue(isTransientRssError(javax.net.ssl.SSLException("Connection closed by peer")))
        assertTrue(isTransientRssError(UnknownHostException("feed.example.com")))
        assertTrue(isTransientRssError(RssHttpFailure(408)))
        assertTrue(isTransientRssError(RssHttpFailure(429)))
        assertTrue(isTransientRssError(RssHttpFailure(500)))
        assertTrue(isTransientRssError(RssHttpFailure(502)))
        assertTrue(isTransientRssError(RssHttpFailure(503)))
        assertTrue(isTransientRssError(IOException("Network error", SocketTimeoutException())))
        assertTrue(isTransientRssError(IOException("Connection error", java.net.SocketException("Broken pipe"))))
        assertTrue(isTransientRssError(IOException("SSL error", SSLException("Connection reset"))))
        assertTrue(isTransientRssError(handshakeException("Handshake failed due to EOF", EOFException())))
        assertTrue(isTransientRssError(handshakeException("Handshake failed due to EOF", EOFException("Connection closed"))))
        assertTrue(isTransientRssError(handshakeException("Handshake failed", SocketException("Connection reset by peer"))))
        assertTrue(isTransientRssError(IOException("Network error", handshakeException("Handshake failed", EOFException()))))
    }

    @Test
    fun isTransientRssErrorIdentifiesFatalErrors() {
        assertFalse(isTransientRssError(SSLHandshakeException("Cert expired")))
        assertFalse(isTransientRssError(SSLPeerUnverifiedException("Hostname mismatch")))
        assertFalse(isTransientRssError(IOException("SSL error", SSLHandshakeException("Untrusted root"))))
        assertFalse(isTransientRssError(SSLException("Wrapped", SSLPeerUnverifiedException("No peer cert"))))
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

    @Test
    fun sslCertificateErrorDisablesFeedAndRecordsErrorMessage() {
        val feed = RssFeedEntity("feed-1", "https://example.com/rss", "Sample Feed", revision = 3, enabled = true)
        val now = 4000L
        val updated = applyRssFetchFailure(feed, SSLHandshakeException("Cert expired"), now)
        assertFalse(updated.enabled)
        assertEquals("Cert expired", updated.error)
        assertEquals(4L, updated.revision)
        assertFalse(updated.acquisitionXmlDone)
    }

    @Test
    fun sslHandshakeInterruptionPreservesFeedEnabledStatus() {
        val feed = RssFeedEntity("feed-1", "https://example.com/rss", "Sample Feed", revision = 3, enabled = true)
        val now = 5000L
        val updated = applyRssFetchFailure(feed, handshakeException("Handshake failed due to EOF", EOFException()), now)
        assertTrue(updated.enabled)
        assertEquals("Handshake failed due to EOF", updated.error)
        assertEquals(now, updated.lastAttemptAt)
        assertEquals(3L, updated.revision)
        assertFalse(updated.acquisitionXmlDone)
    }
}
