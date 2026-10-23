package me.bubblespark.tidilo.parser

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

data class RssEntry(val key: String, val url: String, val title: String, val description: String,
    val bodySnippet: String, val imageUrl: String?, val publishedAt: Long?)
data class RssFeedDocument(val title: String, val entries: List<RssEntry>)

internal object RssFeedParser {
    /** Parses feed metadata only; article HTML and AI execution are outside this boundary. */
    fun parse(xml: String, responseUrl: String): RssFeedDocument {
        require(httpUrl(responseUrl, responseUrl) != null) { "Invalid feed URL" }
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "Feed document types are not supported" }
        val parser = Parser.xmlParser().setTrackErrors(32)
        val document = Jsoup.parse(xml, responseUrl, parser)
        val root = document.children().firstOrNull() ?: throw IllegalArgumentException("Missing feed document")
        val kind = root.localName()
        require(kind in setOf("rss", "feed", "rdf")) { "Expected RSS, Atom, or RDF XML" }
        require(parser.errors.isEmpty() && Regex("</${Regex.escape(root.tagName())}\\s*>").containsMatchIn(xml)) {
            "Incomplete feed XML"
        }
        val channel = if (kind == "feed") root else root.child("channel")
        require(channel != null) { "Missing feed channel" }
        val title = channel.child("title")?.let(::plain).orEmpty().ifBlank { URI(responseUrl).host }
        val items = if (kind == "feed") root.children().filter { it.localName() == "entry" }
            else (if (kind == "rdf") root else channel).children().filter { it.localName() == "item" }
        return RssFeedDocument(title, items.mapNotNull { item -> entry(item, responseUrl, kind == "feed") })
    }

    private fun entry(item: Element, responseUrl: String, atom: Boolean): RssEntry? {
        val link = if (atom) item.children().firstOrNull {
            it.localName() == "link" && it.attr("rel").let { rel -> rel.isBlank() || rel == "alternate" } &&
                it.attr("type").let { type -> type.isBlank() || type == "text/html" || type == "application/xhtml+xml" }
        } else item.child("link")
        val rawUrl = if (atom) link?.attr("href") else link?.text()
        val url = httpUrl(base(link ?: item, responseUrl), rawUrl.orEmpty()) ?: return null
        val key = item.child(if (atom) "id" else "guid")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.attr("rdf:about").takeIf { it.isNotBlank() } ?: url
        val description = item.child(if (atom) "summary" else "description")
        val content = item.children().firstOrNull { it.localName() == if (atom) "content" else "encoded" }
        val html = (content ?: description)?.let(::html).orEmpty()
        val snippet = Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim().take(800)
        val image = item.children().firstNotNullOfOrNull { element ->
            val name = element.tagName().lowercase()
            val candidate = when {
                name.endsWith(":thumbnail") -> element.attr("url")
                name.endsWith(":content") && element.attr("type").let { it.isBlank() || it.startsWith("image/") } &&
                    element.attr("medium").let { it.isBlank() || it == "image" } -> element.attr("url")
                name == "enclosure" && element.attr("type").startsWith("image/") -> element.attr("url")
                else -> ""
            }
            httpUrl(base(element, responseUrl), candidate)
        } ?: Jsoup.parse(html).select("img[src]").firstNotNullOfOrNull {
            httpUrl(base(content ?: description ?: item, responseUrl), it.attr("src"))
        }
        val published = item.child(if (atom) "published" else "pubDate")?.text()
            ?: item.children().firstOrNull { it.tagName().endsWith(":date") }?.text()
        return RssEntry(key, url, item.child("title")?.let(::plain).orEmpty().ifBlank { url },
            description?.let(::plain).orEmpty(), snippet, image, published?.let(::date))
    }

    private fun Element.localName() = tagName().substringAfterLast(':').lowercase()
    private fun Element.child(name: String) = children().firstOrNull { it.localName() == name.lowercase() }
    private fun html(element: Element): String = if (element.children().isEmpty()) element.wholeText() else element.html()
    private fun plain(element: Element) = Jsoup.parse(html(element)).text().trim()
    private fun base(element: Element, responseUrl: String): String {
        var result = responseUrl
        for (node in (element.parents().toList().reversed() + element)) {
            if (node.hasAttr("xml:base")) result = runCatching { URI(result).resolve(node.attr("xml:base")).toString() }.getOrDefault(result)
        }
        return result
    }
    private fun date(value: String): Long? {
        val text = value.trim()
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Preserve query identity while dropping fragments, which are not sent in HTTP requests. */
    fun httpUrl(base: String, raw: String): String? = runCatching {
        if (raw.isBlank()) return null
        val uri = URI(base).resolve(raw.trim()).normalize()
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        uri.toASCIIString().substringBefore('#')
    }.getOrNull()
}
