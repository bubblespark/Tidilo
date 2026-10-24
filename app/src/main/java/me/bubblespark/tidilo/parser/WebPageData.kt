package me.bubblespark.tidilo.parser

/**
 * Metadata tags inside an HTML document.
 */
data class WebMetaTag(
    val keyType: String, // "name", "property", "http-equiv", "itemprop", "charset"
    val keyName: String, // e.g. "og:title", "description", "twitter:card"
    val content: String
)

/**
 * Structured data extracted from JSON-LD (schema.org).
 */
data class JsonLdItem(
    val rawJson: String,
    val type: String?, // e.g. "NewsArticle", "WebPage", "TechArticle"
    val context: String?,
    val headlineOrName: String?,
    val description: String?,
    val properties: Map<String, String> = emptyMap()
)

/**
 * Content extraction result from Readability / main body parser.
 */
data class ExtractedArticle(
    val title: String?,
    val byline: String?,
    val excerpt: String?,
    val textContent: String,
    val charCount: Int
)

/**
 * Aggregated elements parsed from a web page.
 */
data class ParsedWebPage(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val statusMessage: String,
    val contentType: String?,
    val htmlLang: String?,
    val title: String?,
    val canonicalUrl: String?,
    val metaTags: List<WebMetaTag>,
    val ogpTags: Map<String, String>,
    val twitterTags: Map<String, String>,
    val standardMetaTags: Map<String, String>,
    val jsonLdList: List<JsonLdItem>,
    val h1List: List<String>,
    val h2List: List<String>,
    val readabilityArticle: ExtractedArticle?,
    val jsoupBodyText: String,
    val jsoupBodyCharCount: Int,
    val fetchLatencyMs: Long,
    val parseLatencyMs: Long,
    val rawHtmlSizeBytes: Int,
    val resolvedUrl: String = finalUrl,
    val appleTouchIconUrl: String? = null,
    val faviconUrl: String? = null,
    val isNsfwSignal: Boolean = false,
    val iconCandidates: List<me.bubblespark.tidilo.data.DomainIconCandidate>? = null,
    val imageBaseUrl: String = finalUrl
)
