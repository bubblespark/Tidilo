package me.bubblespark.tidilo.parser

import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

object WebPageParser {

    internal const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 15000

    internal val REQUEST_HEADERS = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ja,en-US;q=0.9,en;q=0.8",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "none",
        "sec-fetch-user" to "?1",
        "upgrade-insecure-requests" to "1"
    )

    /**
     * Fetches and parses HTML of the given URL, extracting all elements (meta, title, OGP, JSON-LD, body, etc.).
     */
    suspend fun parseUrl(rawUrl: String): Result<ParsedWebPage> = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(rawUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid URL format: $rawUrl"))

        try {
            val startFetch = System.currentTimeMillis()
            val response = Jsoup.connect(normalizedUrl)
                .userAgent(USER_AGENT)
                .headers(REQUEST_HEADERS)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .execute()
            val fetchLatency = System.currentTimeMillis() - startFetch

            val startParse = System.currentTimeMillis()
            val finalUrl = response.url().toExternalForm()
            val statusCode = response.statusCode()
            val statusMessage = response.statusMessage()
            val contentType = response.contentType()
            val rawHtml = response.body()
            val rawHtmlBytes = rawHtml.toByteArray(Charsets.UTF_8).size

            val doc = response.parse()
            val parsed = extractElements(
                requestedUrl = normalizedUrl,
                finalUrl = finalUrl,
                statusCode = statusCode,
                statusMessage = statusMessage,
                contentType = contentType,
                rawHtml = rawHtml,
                rawHtmlSizeBytes = rawHtmlBytes,
                doc = doc,
                fetchLatencyMs = fetchLatency
            )
            val parseLatency = System.currentTimeMillis() - startParse

            Result.success(parsed.copy(parseLatencyMs = parseLatency))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts all elements from a pre-fetched HTML string.
     */
    fun parseHtml(
        url: String,
        html: String,
        finalUrl: String = url,
        statusCode: Int = 200,
        statusMessage: String = "OK",
        contentType: String = "text/html",
        fetchLatencyMs: Long = 0
    ): ParsedWebPage {
        val startParse = System.currentTimeMillis()
        val doc = Jsoup.parse(html, finalUrl)
        val rawHtmlBytes = html.toByteArray(Charsets.UTF_8).size

        val parsed = extractElements(
            requestedUrl = url,
            finalUrl = finalUrl,
            statusCode = statusCode,
            statusMessage = statusMessage,
            contentType = contentType,
            rawHtml = html,
            rawHtmlSizeBytes = rawHtmlBytes,
            doc = doc,
            fetchLatencyMs = fetchLatencyMs
        )
        val parseLatency = System.currentTimeMillis() - startParse
        return parsed.copy(parseLatencyMs = parseLatency)
    }

    private fun extractElements(
        requestedUrl: String,
        finalUrl: String,
        statusCode: Int,
        statusMessage: String,
        contentType: String?,
        rawHtml: String,
        rawHtmlSizeBytes: Int,
        doc: Document,
        fetchLatencyMs: Long
    ): ParsedWebPage {
        // 1. html lang
        val htmlLang = doc.selectFirst("html")?.let { htmlEl ->
            htmlEl.attr("lang").takeIf { it.isNotBlank() }
                ?: htmlEl.attr("xml:lang").takeIf { it.isNotBlank() }
        }

        // 2. title
        val title = doc.title().trim().takeIf { it.isNotBlank() }
            ?: doc.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotBlank() }

        // 3. canonical
        val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.let { link ->
            link.attr("abs:href").takeIf { it.isNotBlank() }
                ?: link.attr("href").takeIf { it.isNotBlank() }
        }

        // 3.1 apple-touch-icon / fluid-icon (high-resolution icon such as 180x180)
        val appleTouchIconUrl = (doc.selectFirst("link[rel~=(?i)apple-touch-icon]")
            ?: doc.selectFirst("link[rel~=(?i)fluid-icon]"))?.let { link ->
            link.attr("abs:href").takeIf { it.isNotBlank() }
                ?: link.attr("href").takeIf { it.isNotBlank() }
        }

        // 3.2 favicon / shortcut icon
        val faviconUrl = doc.selectFirst("link[rel~=(?i)icon]")?.let { link ->
            link.attr("abs:href").takeIf { it.isNotBlank() }
                ?: link.attr("href").takeIf { it.isNotBlank() }
        }

        // 4. All Meta Tags
        val metaTags = mutableListOf<WebMetaTag>()
        val ogpTags = mutableMapOf<String, String>()
        val twitterTags = mutableMapOf<String, String>()
        val standardMetaTags = mutableMapOf<String, String>()

        for (el in doc.select("meta")) {
            val name = el.attr("name").trim()
            val property = el.attr("property").trim()
            val httpEquiv = el.attr("http-equiv").trim()
            val itemprop = el.attr("itemprop").trim()
            val charset = el.attr("charset").trim()
            val content = el.attr("content").trim()

            when {
                charset.isNotBlank() -> {
                    metaTags.add(WebMetaTag(keyType = "charset", keyName = "charset", content = charset))
                }
                property.isNotBlank() -> {
                    metaTags.add(WebMetaTag(keyType = "property", keyName = property, content = content))
                    if (property.startsWith("og:", ignoreCase = true)) {
                        ogpTags[property] = content
                    } else if (property.startsWith("twitter:", ignoreCase = true)) {
                        twitterTags[property] = content
                    }
                }
                name.isNotBlank() -> {
                    metaTags.add(WebMetaTag(keyType = "name", keyName = name, content = content))
                    if (name.startsWith("twitter:", ignoreCase = true)) {
                        twitterTags[name] = content
                    } else if (name.startsWith("og:", ignoreCase = true)) {
                        ogpTags[name] = content
                    } else {
                        standardMetaTags[name.lowercase()] = content
                    }
                }
                httpEquiv.isNotBlank() -> {
                    metaTags.add(WebMetaTag(keyType = "http-equiv", keyName = httpEquiv, content = content))
                }
                itemprop.isNotBlank() -> {
                    metaTags.add(WebMetaTag(keyType = "itemprop", keyName = itemprop, content = content))
                }
            }
        }

        // 5. JSON-LD (schema.org)
        val jsonLdList = mutableListOf<JsonLdItem>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val jsonText = script.data().trim().ifBlank { script.html().trim() }
            if (jsonText.isNotBlank()) {
                parseJsonLd(jsonText, jsonLdList)
            }
        }

        // 6. Headings (h1, h2)
        val h1List = doc.select("h1").mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
        val h2List = doc.select("h2").mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }

        // 7. Body text extraction: Readability4J (Mozilla Readability algorithm)
        val readabilityArticle = try {
            val readability = Readability4J(finalUrl, rawHtml)
            val article = readability.parse()
            val text = article.textContent?.trim().orEmpty()
            ExtractedArticle(
                title = article.title?.trim()?.takeIf { it.isNotBlank() },
                byline = article.byline?.trim()?.takeIf { it.isNotBlank() },
                excerpt = article.excerpt?.trim()?.takeIf { it.isNotBlank() },
                textContent = text,
                charCount = text.length
            )
        } catch (_: Exception) {
            null
        }

        // 8. Body text extraction: Jsoup DOM cleaning fallback
        val jsoupBodyText = extractJsoupCleanText(doc)

        // 9. Derive canonical/resolved URL (resolving Google share wrapper, URL shorteners, canonical link, og:url)
        val resolvedUrl = resolveRealUrl(
            requestedUrl = requestedUrl,
            finalUrl = finalUrl,
            canonicalUrl = canonicalUrl,
            ogUrl = ogpTags["og:url"]
        )

        val domain = DomainIconManager.extractDomain(resolvedUrl)
            ?: DomainIconManager.extractDomain(requestedUrl)
            ?: DomainIconManager.extractDomain(finalUrl)

        val effectiveTitle = ogpTags["og:title"]?.trim()?.takeIf { it.isNotBlank() } ?: title
        val isNsfwSignal = detectNsfwSignal(
            effectiveTitle = effectiveTitle,
            standardMetaTags = standardMetaTags,
            metaTags = metaTags,
            ogpTags = ogpTags,
            jsonLdList = jsonLdList,
            domain = domain
        )

        return ParsedWebPage(
            requestedUrl = requestedUrl,
            finalUrl = finalUrl,
            statusCode = statusCode,
            statusMessage = statusMessage,
            contentType = contentType,
            htmlLang = htmlLang,
            title = title,
            canonicalUrl = canonicalUrl,
            metaTags = metaTags,
            ogpTags = ogpTags,
            twitterTags = twitterTags,
            standardMetaTags = standardMetaTags,
            jsonLdList = jsonLdList,
            h1List = h1List,
            h2List = h2List,
            readabilityArticle = readabilityArticle,
            jsoupBodyText = jsoupBodyText,
            jsoupBodyCharCount = jsoupBodyText.length,
            fetchLatencyMs = fetchLatencyMs,
            parseLatencyMs = 0,
            rawHtmlSizeBytes = rawHtmlSizeBytes,
            resolvedUrl = resolvedUrl,
            appleTouchIconUrl = appleTouchIconUrl,
            faviconUrl = faviconUrl,
            isNsfwSignal = isNsfwSignal,
            iconCandidates = me.bubblespark.tidilo.data.DomainIconResolver.parseHtml(rawHtml, finalUrl),
            imageBaseUrl = doc.baseUri()
        )
    }

    /**
     * Determines whether the parsed web page contains NSFW signals based on:
     * 0. Domain NSFW Policy: DomainPolicyManager.isNsfwDomain(domain) == true (unconditional)
     * 1. Effective title (og:title or <title>) containing adult/R-18 keywords
     * 2. Meta rating tag containing: "R-18", "R18", "18+", "RTA-5042-1996-1400-1577-RTA", "adult", "mature"
     * 3. Schema.org / JSON-LD properties:
     *    - isFamilyFriendly: false
     *    - contentRating: "R18", "R-18", "18+"
     *    - typicalAgeRange: "18+"
     */
    fun detectNsfwSignal(
        effectiveTitle: String?,
        standardMetaTags: Map<String, String>,
        metaTags: List<WebMetaTag>,
        ogpTags: Map<String, String>,
        jsonLdList: List<JsonLdItem>,
        domain: String? = null
    ): Boolean {
        // 0. Domain check (always assign NSFW if domain is marked in DomainPolicyManager)
        if (!domain.isNullOrBlank() && DomainPolicyManager.isNsfwDomain(domain)) {
            return true
        }

        // 1. Title keyword check (from og:title or title)
        if (!effectiveTitle.isNullOrBlank()) {
            val titleKeywords = listOf("R-18", "R18", "18禁", "成人向け", "成年コミック")
            if (titleKeywords.any { effectiveTitle.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        // 2. Rating meta tag check
        val ratingContent = standardMetaTags["rating"]
            ?: metaTags.firstOrNull { it.keyName.equals("rating", ignoreCase = true) }?.content
            ?: ogpTags["og:rating"]
        if (!ratingContent.isNullOrBlank()) {
            val ratingKeywords = listOf("R-18", "R18", "18+", "RTA-5042-1996-1400-1577-RTA", "adult", "mature")
            if (ratingKeywords.any { ratingContent.contains(it, ignoreCase = true) }) {
                return true
            }
        }

        // 3. Schema.org / JSON-LD check
        for (item in jsonLdList) {
            val isFamilyFriendly = item.properties["isFamilyFriendly"]
            if (isFamilyFriendly != null && isFamilyFriendly.trim().equals("false", ignoreCase = true)) {
                return true
            }

            val contentRating = item.properties["contentRating"]
            if (!contentRating.isNullOrBlank()) {
                val crKeywords = listOf("R18", "R-18", "18+")
                if (crKeywords.any { contentRating.contains(it, ignoreCase = true) }) {
                    return true
                }
            }

            val typicalAgeRange = item.properties["typicalAgeRange"]
            if (!typicalAgeRange.isNullOrBlank()) {
                if (typicalAgeRange.contains("18+", ignoreCase = true)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Parses JSON-LD content and extracts @type and primary properties.
     */
    private fun parseJsonLd(rawJson: String, output: MutableList<JsonLdItem>) {
        try {
            val tokener = JSONTokener(rawJson)
            val value = tokener.nextValue()
            if (value is JSONObject) {
                val graph = value.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) {
                        val obj = graph.optJSONObject(i)
                        if (obj != null) {
                            output.add(extractSingleJsonLd(obj))
                        }
                    }
                } else {
                    output.add(extractSingleJsonLd(value))
                }
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val obj = value.optJSONObject(i)
                    if (obj != null) {
                        output.add(extractSingleJsonLd(obj))
                    }
                }
            }
        } catch (e: Exception) {
            output.add(
                JsonLdItem(
                    rawJson = rawJson,
                    type = "ParseError",
                    context = null,
                    headlineOrName = null,
                    description = e.message
                )
            )
        }
    }

    private fun extractSingleJsonLd(obj: JSONObject): JsonLdItem {
        val type = when {
            obj.has("@type") -> {
                val t = obj.get("@type")
                if (t is JSONArray) {
                    (0 until t.length()).joinToString(", ") { t.optString(it) }
                } else {
                    t.toString()
                }
            }
            else -> null
        }
        val context = obj.optString("@context").takeIf { it.isNotBlank() }
        val name = obj.optString("name").takeIf { it.isNotBlank() }
            ?: obj.optString("headline").takeIf { it.isNotBlank() }
        val description = obj.optString("description").takeIf { it.isNotBlank() }

        val props = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k != "@graph") {
                val v = obj.opt(k)
                if (v != null && v !is JSONObject && v !is JSONArray) {
                    props[k] = v.toString()
                }
            }
        }

        val prettyJson = try {
            obj.toString(2)
        } catch (_: Exception) {
            obj.toString()
        }

        return JsonLdItem(
            rawJson = prettyJson,
            type = type,
            context = context,
            headlineOrName = name,
            description = description,
            properties = props
        )
    }

    /**
     * Extracts main body text from Jsoup DOM after stripping boilerplate tags (navigation, ads, footer, etc.).
     */
    private fun extractJsoupCleanText(originalDoc: Document): String {
        return try {
            val doc = originalDoc.clone()
            // Strip non-content elements like scripts, styles, navigation, headers, footers
            doc.select(
                "script, style, noscript, nav, header, footer, aside, form, svg, iframe, " +
                "[role=banner], [role=navigation], [role=complementary], .ad, .ads, .sidebar, .menu"
            ).remove()

            // Prefer <article> or <main> if available
            val mainEl = doc.selectFirst("article")
                ?: doc.selectFirst("main")
                ?: doc.selectFirst("[role=main]")
                ?: doc.body()

            // Join paragraphs with double newlines
            val paragraphs = mainEl.select("p, h1, h2, h3, h4, h5, h6, li, blockquote")
            if (paragraphs.isNotEmpty()) {
                paragraphs.map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            } else {
                mainEl.text().trim()
            }
        } catch (_: Exception) {
            ""
        }
    }

    internal fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return try {
            val uri = URI(withScheme)
            if (uri.host.isNullOrBlank()) null else uri.toASCIIString().substringBefore('#')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derives the most reliable destination Web page URL from shorteners or share wrappers
     * (such as share.google, google.com/url, etc.).
     */
    fun resolveRealUrl(
        requestedUrl: String,
        finalUrl: String,
        canonicalUrl: String?,
        ogUrl: String?
    ): String {
        // 1. Use canonicalUrl with highest priority if present and not a shortener/wrapper
        if (!canonicalUrl.isNullOrBlank() && isValidHttpUrl(canonicalUrl) && !isWrapperUrl(canonicalUrl)) {
            return canonicalUrl.trim()
        }

        // 2. Use og:url if present and not a shortener/wrapper
        if (!ogUrl.isNullOrBlank() && isValidHttpUrl(ogUrl) && !isWrapperUrl(ogUrl)) {
            return ogUrl.trim()
        }

        // 3. Use Jsoup HTTP redirect target (finalUrl) if not a shortener/wrapper
        if (isValidHttpUrl(finalUrl) && !isWrapperUrl(finalUrl)) {
            return finalUrl.trim()
        }

        // 4. Check if redirect target URL is embedded in query parameters (q, url, u)
        val fromFinalQuery = extractUrlFromQuery(finalUrl)
        if (fromFinalQuery != null && !isWrapperUrl(fromFinalQuery)) {
            return fromFinalQuery
        }
        val fromReqQuery = extractUrlFromQuery(requestedUrl)
        if (fromReqQuery != null && !isWrapperUrl(fromReqQuery)) {
            return fromReqQuery
        }

        // 5. Fallback
        return if (finalUrl.isNotBlank()) finalUrl.trim() else requestedUrl.trim()
    }

    private val WRAPPER_HOSTS = setOf(
        "share.google",
        "t.co",
        "bit.ly",
        "tinyurl.com",
        "is.gd",
        "buff.ly",
        "ow.ly",
        "ift.tt",
        "goo.gl"
    )

    fun isWrapperUrl(url: String): Boolean {
        return try {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            if (host in WRAPPER_HOSTS) return true
            if (host == "google.com" || host.endsWith(".google.com")) {
                val path = uri.path?.lowercase().orEmpty()
                if (path.startsWith("/share.google") || path.startsWith("/url")) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun extractUrlFromQuery(url: String): String? {
        return try {
            val uri = URI(url.trim())
            val query = uri.rawQuery ?: return null
            val pairs = query.split('&')
            for (pair in pairs) {
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0]
                    if (key == "q" || key == "url" || key == "u") {
                        val decoded = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
                        if (!decoded.isNullOrBlank() && isValidHttpUrl(decoded)) {
                            return decoded
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidHttpUrl(url: String): Boolean {
        return try {
            val uri = URI(url.trim())
            (uri.scheme?.equals("http", ignoreCase = true) == true ||
             uri.scheme?.equals("https", ignoreCase = true) == true) &&
             !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
