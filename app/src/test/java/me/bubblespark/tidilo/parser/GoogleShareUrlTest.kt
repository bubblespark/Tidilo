package me.bubblespark.tidilo.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleShareUrlTest {

    @Test
    fun testShareGoogleParsingAndResolution() = runBlocking {
        val url = "https://share.google/PMNsmZyE1xb29GBQw"
        val result = WebPageParser.parseUrl(url)
        assertTrue("Parsing should succeed", result.isSuccess)
        val page = result.getOrThrow()

        // Verify resolved real URL is zenn.dev rather than share.google
        assertEquals("https://zenn.dev/trustdock/articles/1b84a04e39e27a", page.resolvedUrl)
        assertEquals("Antigravityを使ってAndroidアプリを作ってみた", page.ogpTags["og:title"])
        assertEquals("Zenn", page.ogpTags["og:site_name"])
    }

    @Test
    fun testResolveRealUrlPriorityRules() {
        // 1. canonicalUrl has highest priority (when not a wrapper URL)
        val res1 = WebPageParser.resolveRealUrl(
            requestedUrl = "https://share.google/XYZ",
            finalUrl = "https://www.google.com/share.google?q=XYZ",
            canonicalUrl = "https://developer.android.com/compose",
            ogUrl = "https://developer.android.com/compose/overview"
        )
        assertEquals("https://developer.android.com/compose", res1)

        // 2. Fall back to og:url when canonicalUrl is absent
        val res2 = WebPageParser.resolveRealUrl(
            requestedUrl = "https://t.co/abc",
            finalUrl = "https://example.com/redirect",
            canonicalUrl = null,
            ogUrl = "https://example.com/original-article"
        )
        assertEquals("https://example.com/original-article", res2)

        // 3. Google redirect URL (extracted from query parameter 'q')
        val res3 = WebPageParser.resolveRealUrl(
            requestedUrl = "https://www.google.com/url?q=https%3A%2F%2Fgithub.com%2Fgoogle%2Fguava&sa=D",
            finalUrl = "https://www.google.com/url?q=https%3A%2F%2Fgithub.com%2Fgoogle%2Fguava&sa=D",
            canonicalUrl = null,
            ogUrl = null
        )
        assertEquals("https://github.com/google/guava", res3)

        // 4. Wrapper/shortened domain detection
        assertTrue(WebPageParser.isWrapperUrl("https://share.google/PMNsmZyE1xb29GBQw"))
        assertTrue(WebPageParser.isWrapperUrl("https://www.google.com/share.google?q=123"))
        assertTrue(WebPageParser.isWrapperUrl("https://www.google.com/url?q=abc"))
        assertTrue(WebPageParser.isWrapperUrl("https://bit.ly/3xYzA"))
        assertTrue(WebPageParser.isWrapperUrl("https://t.co/xyz123"))
        assertTrue(!WebPageParser.isWrapperUrl("https://zenn.dev/articles"))
        assertTrue(!WebPageParser.isWrapperUrl("https://developer.android.com"))
    }
}
