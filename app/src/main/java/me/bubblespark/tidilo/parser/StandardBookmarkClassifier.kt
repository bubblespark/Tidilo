package me.bubblespark.tidilo.parser

import java.net.URI
import java.util.Locale
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal data class StandardClassification(
    val category: Category? = null,
    val subcategory: Subcategory? = null
)

private val schemaLabels = mapOf(
    "Article" to StandardClassification(Category.ARTICLE),
    "NewsArticle" to StandardClassification(Category.ARTICLE),
    "TechArticle" to StandardClassification(Category.ARTICLE, Subcategory.TECH),
    "Recipe" to StandardClassification(subcategory = Subcategory.FOOD),
    "Product" to StandardClassification(Category.COMMERCE),
    "VideoObject" to StandardClassification(Category.MEDIA),
    "DiscussionForumPosting" to StandardClassification(Category.COMMUNITY),
    "Dataset" to StandardClassification(Category.REFERENCE),
    "LearningResource" to StandardClassification(subcategory = Subcategory.EDUCATION)
)

private val schemaPrefixes = listOf("https://schema.org/", "http://schema.org/")

internal fun classifyStandardMetadata(page: ParsedWebPage): StandardClassification {
    val base = resolveHttpUri(page.resolvedUrl) ?: resolveHttpUri(page.requestedUrl)
    val labels = base?.let { primaryLabels(it, page.jsonLdScripts) }.orEmpty()
    val categories = labels.mapNotNull { it.category }.toSet()
    val topics = labels.mapNotNull { it.subcategory }.toSet()
    val fallback = when (page.pageOpenGraphType?.lowercase(Locale.ROOT)) {
        "article" -> Category.ARTICLE
        "video.movie", "video.episode", "video.tv_show", "video.other" -> Category.MEDIA
        else -> null
    }
    return StandardClassification(
        category = if (categories.isEmpty()) fallback else categories.singleOrNull(),
        subcategory = topics.singleOrNull()
    )
}

private fun primaryLabels(base: URI, scripts: List<String>): List<StandardClassification> {
    val nodes = scripts.flatMap(::readNodes)
    val byId = nodes.flatMap { node -> node.ids(base).map { it to node } }
        .groupBy({ it.first }, { it.second })
    val pageUri = base.withoutFragment()
    fun identifiesPage(uri: URI): Boolean = uri.withoutFragment() == pageUri
    val namedPages = nodes.filter { node ->
        val isWebPage = "WebPage" in node.types
        (isWebPage || node.values("mainEntity").isNotEmpty()) &&
            (node.links("url", base).any { it == pageUri } ||
                node.ids(base).any { it == pageUri || (isWebPage && identifiesPage(it)) })
    }
    val currentPages = namedPages.ifEmpty {
        listOfNotNull(nodes.singleOrNull { node ->
            node.isRoot && "WebPage" in node.types && node.ids(base).isEmpty() && node.links("url", base).isEmpty()
        })
    }
    val pageIds = currentPages.flatMap { it.ids(base) }.toSet()
    val explicit = nodes.flatMap { node ->
        val forward = if (node in currentPages) node.values("mainEntity").flatMap { value ->
            val inline = (value as? JSONObject)?.let { SchemaNode(it, node.schemaContext) }
            val referenced = linkUris(value, base).flatMap { byId[it].orEmpty() }
            referenced + listOfNotNull(inline)
        } else emptyList()
        val reverse = node.links("mainEntityOfPage", base).any { it == pageUri || it in pageIds }
        forward + if (reverse) listOf(node) else emptyList()
    }.flatMap { it.labels }
    if (explicit.isNotEmpty()) return explicit
    return nodes.filter { node ->
        node.labels.isNotEmpty() && node.links("url", base).any(::identifiesPage)
    }.singleOrNull()?.labels.orEmpty()
}

private fun readNodes(script: String): List<SchemaNode> {
    val parsed = try {
        val tokens = JSONTokener(script)
        tokens.nextValue().takeIf { tokens.nextClean() == '\u0000' }
    } catch (_: JSONException) {
        return emptyList()
    }
    return jsonValues(parsed).filterIsInstance<JSONObject>().flatMap { root ->
        val node = SchemaNode(root, false, isRoot = true)
        listOf(node) + jsonValues(root.opt("@graph")).filterIsInstance<JSONObject>()
            .map { SchemaNode(it, node.schemaContext) }
    }
}

private class SchemaNode(
    val json: JSONObject,
    inheritedContext: Boolean,
    val isRoot: Boolean = false
) {
    val schemaContext = if (json.has("@context")) {
        val contexts = jsonValues(json.opt("@context"))
        contexts.isNotEmpty() && contexts.all { it is String && "${it.trimEnd('/')}/" in schemaPrefixes }
    } else inheritedContext

    val types = jsonValues(json.opt("@type")).filterIsInstance<String>().mapNotNull { value ->
        val prefix = schemaPrefixes.firstOrNull { value.startsWith(it) }
        when {
            prefix != null -> value.removePrefix(prefix)
            schemaContext -> value
            else -> null
        }
    }
    val labels = types.mapNotNull(schemaLabels::get)

    fun ids(base: URI): List<URI> = jsonValues(json.opt("@id"))
        .filterIsInstance<String>().mapNotNull { resolveHttpUri(it, base) }

    fun values(property: String): List<Any> {
        val keys = schemaPrefixes.map { it + property } + if (schemaContext) listOf(property) else emptyList()
        return keys.flatMap { jsonValues(json.opt(it)) }
    }

    fun links(property: String, base: URI): List<URI> =
        values(property).flatMap { linkUris(it, base) }
}

private fun linkUris(value: Any, base: URI): List<URI> {
    val candidates = when (value) {
        is String -> listOf(value)
        is JSONObject -> listOfNotNull(value.opt("@id") as? String, value.opt("url") as? String)
        else -> emptyList()
    }
    return candidates.mapNotNull { resolveHttpUri(it, base) }
}

private fun jsonValues(value: Any?): List<Any> = when (value) {
    is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
    null, JSONObject.NULL -> emptyList()
    else -> listOf(value)
}

private fun URI.withoutFragment(): URI = URI(toString().substringBefore('#'))

private fun resolveHttpUri(value: String, base: URI? = null): URI? = runCatching {
    if (value.isBlank()) return null
    val resolved = when {
        base == null -> URI(value)
        value.startsWith('?') -> URI(base.withoutFragment().toString().substringBefore('?') + value)
        else -> base.resolve(value)
    }.normalize()
    val scheme = resolved.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val host = resolved.host?.lowercase(Locale.ROOT) ?: return null
    val defaultPort = if (scheme == "https") 443 else 80
    val port = if (resolved.port in listOf(-1, defaultPort)) "" else ":${resolved.port}"
    val path = resolved.rawPath.ifEmpty { "/" }
    val query = resolved.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = resolved.rawFragment?.let { "#$it" }.orEmpty()
    URI("$scheme://$host$port$path$query$fragment")
}.getOrNull()
