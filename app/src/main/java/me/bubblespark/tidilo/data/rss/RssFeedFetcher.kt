package me.bubblespark.tidilo.data.rss

import me.bubblespark.tidilo.parser.RssFeedDocument
import me.bubblespark.tidilo.parser.RssFeedParser

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
