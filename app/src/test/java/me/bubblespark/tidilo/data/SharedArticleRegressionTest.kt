package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.service.GeminiTaggingService
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.parser.ArticleAcquirer
import me.bubblespark.tidilo.parser.ArticleNetwork
import me.bubblespark.tidilo.parser.ArticleResponse
import me.bubblespark.tidilo.parser.ArticlePreparation

class SharedArticleRegressionTest {
    @Test fun publisherFixtureProducesIdenticalPreparedInputsThroughBothAcquisitionPaths() = runBlocking {
        val bytes = javaClass.getResourceAsStream("/article-ingestion/publisher.html")!!.use { it.readBytes() }
        val network = object : ArticleNetwork {
            override val scopeKey = "rss-fixture"
            override suspend fun fetch(url: String) = ArticleResponse(url, 200, bytes, "text/html; charset=utf-8")
        }
        val directParse = WebPageParser.parseHtml("https://example.com/post", String(bytes, Charsets.UTF_8))
        val acquired = ArticleAcquirer.acquire("https://example.com/post", network)
        assertEquals(ArticlePreparation.prepare(directParse), ArticlePreparation.prepare(acquired))
        assertEquals(directParse.iconCandidates, acquired.iconCandidates)
        assertFalse(ArticlePreparation.prepare(acquired).bodySnippet.contains("Navigation"))
    }
    @Test fun imageResolvesAgainstHtmlBaseRatherThanCanonicalUrl() {
        val page = WebPageParser.parseHtml("https://example.com/article", """
            <base href="https://cdn.example.com/assets/"><link rel="canonical" href="https://example.com/canonical">
            <meta property="og:image" content="cover.png"><p>Content</p>
        """)
        assertEquals("https://cdn.example.com/assets/cover.png", ArticlePreparation.prepare(page).ogImageUrl)
    }

    @Test fun transientArticleHttpFailureRemainsRetryable() = runBlocking {
        val network = object : ArticleNetwork {
            override val scopeKey = "fixture"
            override suspend fun fetch(url: String) = ArticleResponse(url, 503, byteArrayOf(), "text/html")
        }
        try { ArticleAcquirer.acquire("https://example.com/article", network); fail("Expected HTTP failure") }
        catch (error: Exception) { assertTrue(error is java.io.IOException) }
    }

    @Test fun sourceIndependentAcquisitionNormalizesFragment() = runBlocking {
        var requested = ""
        val network = object : ArticleNetwork {
            override val scopeKey = "fixture"
            override suspend fun fetch(url: String): ArticleResponse {
                requested = url
                return ArticleResponse(url, 200, "<title>Title</title><p>Body</p>".toByteArray(), "text/html")
            }
        }
        ArticleAcquirer.acquire("https://example.com/article#fragment", network)
        assertEquals("https://example.com/article", requested)
    }

    @Test fun blankOgMetadataFallsBackToHtmlMetadataInPrompt() {
        val parsed = WebPageParser.parseHtml("https://example.com/post", """
            <title>Article title</title><meta property="og:title" content=" ">
            <meta property="og:site_name" content=" ">
            <meta property="og:description" content=" ">
            <meta name="description" content="Actual description">
            <article><p>Actual article content for analysis.</p></article>
        """.trimIndent())
        val prompt = GeminiTaggingService.buildPrompt(parsed.resolvedUrl, parsed, AppLanguage.ENGLISH)
        assertTrue(prompt.contains("Title: Article title"))
        assertTrue(prompt.contains("Description: Actual description"))
        assertTrue(prompt.contains("Site: example.com"))
    }

    @Test fun legacyRssSnippetDoesNotProveHtmlPreparation() {
        val task = ImportTask(url = "https://example.com/post", title = "XML title", status = TaskStatus.READY,
            statusText = "", bodySnippet = "XML excerpt", rssFeedId = "feed")
        assertTrue(ImportRepository.needsArticlePreparse(task))
    }
}
