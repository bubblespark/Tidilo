package me.bubblespark.tidilo.parser

import java.net.URI

/** Source-independent metadata used by both manual and feed article ingestion. */
data class PreparedArticle(
    val url: String,
    val title: String,
    val siteName: String,
    val description: String,
    val bodySnippet: String,
    val ogImageUrl: String?,
    val isNsfw: Boolean
)

object ArticlePreparation {
    fun prepare(parsed: ParsedWebPage, preferredTitle: String? = null): PreparedArticle {
        require(parsed.statusCode in 200..299) { "Article HTTP ${parsed.statusCode}" }
        val url = parsed.resolvedUrl
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }
        fun String?.nonblank() = this?.trim()?.takeIf { it.isNotEmpty() }
        return PreparedArticle(url,
            preferredTitle.nonblank() ?: parsed.ogpTags["og:title"].nonblank() ?: parsed.title.nonblank() ?: host,
            parsed.ogpTags["og:site_name"].nonblank() ?: host,
            parsed.ogpTags["og:description"].nonblank() ?: parsed.standardMetaTags["description"].nonblank().orEmpty(),
            (parsed.readabilityArticle?.textContent.nonblank() ?: parsed.jsoupBodyText).take(800).replace("\n", " "),
            (parsed.ogpTags["og:image"].nonblank() ?: parsed.twitterTags["twitter:image"].nonblank())?.let {
                runCatching { URI(parsed.imageBaseUrl).resolve(it).toString() }.getOrNull()
            }, parsed.isNsfwSignal)
    }
}
