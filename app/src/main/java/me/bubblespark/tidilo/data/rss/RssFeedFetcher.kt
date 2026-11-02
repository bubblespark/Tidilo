package me.bubblespark.tidilo.data.rss

import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.parser.RssFeedDocument
import me.bubblespark.tidilo.parser.RssFeedParser
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLException

internal data class RssHttpPage(val status: Int, val url: String, val body: String,
    val etag: String? = null, val lastModified: String? = null,
    val bytes: ByteArray = body.toByteArray(), val contentType: String? = null)
internal data class RssFetchedFeed(val page: RssHttpPage, val document: RssFeedDocument?)

internal class RssFeedFetcher(private val request: suspend (String, Map<String, String>) -> RssHttpPage) {
    suspend fun fetch(url: String, etag: String?, modified: String?): RssFetchedFeed {
        val headers = buildMap {
            etag?.let { put("If-None-Match", it) }
            modified?.let { put("If-Modified-Since", it) }
        }
        val page = request(url, headers)
        if (page.status == 304) return RssFetchedFeed(page, null)
        if (page.status != 200) throw RssHttpFailure(page.status)
        return RssFetchedFeed(page, RssFeedParser.parse(page.body, page.url))
    }
}

internal class RssHttpFailure(val code: Int) : java.io.IOException("HTTP $code") {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

internal fun rssErrorDetail(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }
    val certFailure = chain.firstOrNull {
        it is SSLPeerUnverifiedException || it is SSLHandshakeException
    }
    val unwrapped = certFailure ?: chain.firstOrNull {
        it is RssHttpFailure ||
        it is java.net.SocketTimeoutException ||
        it is java.net.ConnectException ||
        it is java.net.SocketException ||
        it is java.net.UnknownHostException ||
        it is SSLException
    } ?: error

    val detail = when (unwrapped) {
        is RssHttpFailure -> "HTTP ${unwrapped.code}"
        is java.net.SocketTimeoutException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "Connection timed out"
        is java.net.ConnectException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
        is java.net.SocketException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "Connection error"
        is java.net.UnknownHostException -> unwrapped.message?.takeIf { it.isNotBlank() }?.let { "Unable to resolve host: $it" } ?: "Unable to resolve host"
        is SSLPeerUnverifiedException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "SSL certificate verification failed"
        is SSLHandshakeException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "SSL handshake failed"
        is SSLException -> unwrapped.message?.takeIf { it.isNotBlank() } ?: "SSL connection error"
        else -> unwrapped.message?.takeIf { it.isNotBlank() } ?: (unwrapped::class.java.simpleName.takeIf { it.isNotBlank() } ?: "Error")
    }
    return detail.take(200)
}

internal fun isTransientRssError(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }
    if (chain.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException }) {
        return false
    }
    val target = chain.firstOrNull {
        it is java.net.SocketTimeoutException ||
        it is java.net.ConnectException ||
        it is java.net.SocketException ||
        it is java.net.UnknownHostException ||
        it is SSLException ||
        it is RssHttpFailure
    } ?: error
    return when (target) {
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is SSLException -> true
        is RssHttpFailure -> target.retryable
        else -> false
    }
}

internal fun applyRssFetchFailure(
    feed: RssFeedEntity,
    error: Throwable,
    now: Long = System.currentTimeMillis()
): RssFeedEntity {
    val readableError = rssErrorDetail(error)
    return if (isTransientRssError(error)) {
        feed.copy(
            lastAttemptAt = now,
            error = readableError,
            acquisitionXmlDone = false
        )
    } else {
        feed.copy(
            enabled = false,
            revision = feed.revision + 1,
            lastAttemptAt = now,
            error = readableError,
            acquisitionId = null,
            acquisitionMobileOverride = false,
            acquisitionXmlDone = false
        )
    }
}
