package me.bubblespark.tidilo.parser

import java.io.ByteArrayInputStream
import org.jsoup.Jsoup

/** Request-scoped transport: never changes process networking or persistent Wi-Fi preferences. */
interface ArticleNetwork {
    val scopeKey: String
    suspend fun fetch(url: String): ArticleResponse
}

data class ArticleResponse(val url: String, val status: Int, val bytes: ByteArray, val contentType: String?)

class ArticleHttpFailure(val code: Int) : java.io.IOException("Article HTTP $code") {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

/** Distinguishes article acquisition failures from AICore execution failures. */
class ArticleAcquisitionFailure(cause: Exception) : java.io.IOException(
    "Article acquisition failed: ${cause.message ?: "Could not fetch article"}", cause
)

object ArticleAcquirer {
    suspend fun acquire(url: String, network: ArticleNetwork? = null): ParsedWebPage {
        val normalized = WebPageParser.normalizeUrl(url) ?: throw IllegalArgumentException("Invalid article URL")
        val parsed = if (network == null) WebPageParser.parseUrl(normalized).getOrThrow() else {
            val response = network.fetch(normalized)
            if (response.status !in 200..299) throw ArticleHttpFailure(response.status)
            require(response.bytes.size <= 2 * 1024 * 1024) { "Article HTML exceeds 2 MB" }
            require(response.contentType == null || response.contentType.contains("html", ignoreCase = true)) { "Article is not HTML" }
            val charset = Regex("charset=[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(response.contentType.orEmpty())?.groupValues?.get(1)
            val document = Jsoup.parse(ByteArrayInputStream(response.bytes), charset, response.url)
            WebPageParser.parseHtml(normalized, String(response.bytes, document.outputSettings().charset()).removePrefix("\uFEFF"), response.url, response.status,
                contentType = response.contentType ?: "text/html")
        }
        if (parsed.statusCode !in 200..299) throw ArticleHttpFailure(parsed.statusCode)
        require(parsed.contentType == null || parsed.contentType.contains("html", ignoreCase = true)) { "Article is not HTML" }
        return parsed
    }
}
