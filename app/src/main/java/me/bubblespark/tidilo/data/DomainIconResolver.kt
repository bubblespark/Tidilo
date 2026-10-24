package me.bubblespark.tidilo.data

import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup

data class DomainIconCandidate(val url: String, val highPriority: Boolean = false)
internal data class ResolvedDomainIcon<T>(val value: T, val width: Int, val height: Int)

internal object DomainIconResolver {
    const val PREFERRED_SIZE = 180
    private const val MIN_SIZE = 96

    /** Classifies declarations for ordering; actual decoded dimensions determine acceptance. */
    fun parseHtml(html: String, pageUrl: String): List<DomainIconCandidate> {
        val document = Jsoup.parse(html, pageUrl)
        val result = mutableListOf<DomainIconCandidate>()
        for (element in document.select("link[rel]")) {
            val rel = element.attr("rel").lowercase()
            if (!rel.contains("icon")) continue
            val url = resolveUrl(pageUrl, element.absUrl("href")) ?: continue
            val large = element.attr("sizes").lowercase().split(Regex("\\s+")).any { size ->
                val dimensions = size.split('x')
                dimensions.size == 2 && (dimensions[0].toIntOrNull() ?: 0) >= PREFERRED_SIZE &&
                    (dimensions[1].toIntOrNull() ?: 0) >= PREFERRED_SIZE
            }
            result.add(DomainIconCandidate(url, rel.contains("apple-touch-icon") || large))
        }
        for (element in document.select("meta[name]")) {
            if (!element.attr("name").equals("msapplication-TileImage", true)) continue
            resolveUrl(pageUrl, element.absUrl("content"))?.let { result.add(DomainIconCandidate(it, true)) }
        }
        return result
    }

    private fun resolveUrl(pageUrl: String, value: String?): String? = runCatching {
        if (value.isNullOrBlank()) return null
        val uri = URI(pageUrl).resolve(value.trim()).normalize()
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        uri.toString().substringBefore('#')
    }.getOrNull()

    suspend fun <T : Any> resolve(
        pageUrl: String, domain: String, appleUrl: String? = null, faviconUrl: String? = null,
        discover: suspend (String) -> List<DomainIconCandidate>,
        download: suspend (String) -> ResolvedDomainIcon<T>?,
        discard: (T) -> Unit = {},
        knownCandidates: List<DomainIconCandidate>? = null
    ): T? {
        val attempted = mutableSetOf<String>()
        suspend fun attempt(candidate: DomainIconCandidate): ResolvedDomainIcon<T>? {
            currentCoroutineContext().ensureActive()
            val url = resolveUrl(pageUrl, candidate.url) ?: return null
            if (!attempted.add(url)) return null
            val image = download(url) ?: return null
            if (image.width < MIN_SIZE || image.height < MIN_SIZE) {
                discard(image.value)
                return null
            }
            return image
        }
        var best: ResolvedDomainIcon<T>? = null
        suspend fun highTier(candidates: List<DomainIconCandidate>): T? {
            for (candidate in candidates.filter { it.highPriority }) {
                val image = attempt(candidate) ?: continue
                if (image.width >= PREFERRED_SIZE && image.height >= PREFERRED_SIZE) {
                    best?.let { discard(it.value) }; best = null
                    return image.value
                }
                val previous = best
                if (previous == null || image.width.toLong() * image.height > previous.width.toLong() * previous.height) {
                    previous?.let { discard(it.value) }
                    best = image
                } else discard(image.value)
            }
            return null
        }
        suspend fun pageTier(candidates: List<DomainIconCandidate>): T? {
            highTier(candidates)?.let { return it }
            best?.let { best = null; return it.value }
            for (candidate in candidates.filterNot { it.highPriority }) {
                attempt(candidate)?.let { return it.value }
            }
            return null
        }
        try {
            // A preferred explicit apple icon can avoid HTML discovery entirely.
            if (!appleUrl.isNullOrBlank()) highTier(listOf(DomainIconCandidate(appleUrl, true)))?.let { return it }
            val article = (knownCandidates ?: discover(pageUrl)) + listOfNotNull(faviconUrl?.takeIf { it.isNotBlank() }?.let { DomainIconCandidate(it) })
            pageTier(article)?.let { return it }
            val path = runCatching { URI(pageUrl).path }.getOrNull()
            if (!path.isNullOrEmpty() && path != "/") pageTier(discover("https://$domain"))?.let { return it }
            for (url in listOf("https://$domain/apple-touch-icon.png", "https://$domain/apple-touch-icon-precomposed.png",
                "https://www.google.com/s2/favicons?domain=$domain&sz=256",
                "https://www.google.com/s2/favicons?domain=$domain&sz=128")) {
                attempt(DomainIconCandidate(url))?.let { return it.value }
            }
            return null
        } finally {
            best?.let { discard(it.value) }
        }
    }
}
