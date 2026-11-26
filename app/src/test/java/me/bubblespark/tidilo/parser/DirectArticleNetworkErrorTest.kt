package me.bubblespark.tidilo.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectArticleNetworkErrorTest {

    @Test fun networkExceptionErrorCodesMapToReadableMessages() {
        assertEquals("Unable to resolve host", formatDirectNetworkFailure(1, "Exception in CronetUrlRequest: net::ERR_NAME_NOT_RESOLVED"))
        assertEquals("No internet connection", formatDirectNetworkFailure(2, "Exception in CronetUrlRequest: net::ERR_INTERNET_DISCONNECTED"))
        assertEquals("Connection interrupted by network change", formatDirectNetworkFailure(3, "raw"))
        assertEquals("Connection timed out", formatDirectNetworkFailure(4, "raw"))
        assertEquals("Connection closed unexpectedly", formatDirectNetworkFailure(5, "raw"))
        assertEquals("Connection timed out", formatDirectNetworkFailure(6, "raw"))
        assertEquals("Connection refused", formatDirectNetworkFailure(7, "raw"))
        assertEquals("Connection reset", formatDirectNetworkFailure(8, "raw"))
        assertEquals("Unable to reach host", formatDirectNetworkFailure(9, "raw"))
    }

    @Test fun unknownOrNullErrorCodeFallsBackSafelyWithoutRawCronetPrefix() {
        assertEquals(
            "SSL connection error",
            formatDirectNetworkFailure(11, "Exception in CronetUrlRequest: net::ERR_SSL_PROTOCOL_ERROR, ErrorCode=11")
        )
        assertEquals(
            "Unable to resolve host",
            formatDirectNetworkFailure(null, "Exception in CronetUrlRequest: net::ERR_NAME_NOT_RESOLVED, ErrorCode=1")
        )
        assertEquals(
            "Connection timed out",
            formatDirectNetworkFailure(null, "Exception in CronetUrlRequest: net::ERR_TIMED_OUT")
        )
        assertEquals(
            "Network request failed",
            formatDirectNetworkFailure(null, null)
        )
        assertEquals(
            "Network request failed",
            formatDirectNetworkFailure(99, "   ")
        )
    }
}
