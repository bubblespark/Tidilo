package me.bubblespark.tidilo.parser

import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.service.GeminiTaggingService
import org.junit.Assert.*
import org.junit.Test

class WebPageAdFilteringTest {
    private val editorial = "This device review explains battery life, controls, performance, and practical usage. ".repeat(7)

    @Test fun clearClassAndIdMarkersAreRemovedFromBothExtractorsAndPrompt() {
        val markers = listOf("ad", "ads", "ad-slot", "display_AD_banner", "ADVERTISEMENT", "advert", "advertising", "adsbygoogle", "content ads slot")
        for (attribute in listOf("class", "id")) {
            for (marker in markers) {
                val page = WebPageParser.parseHtml("https://example.com/review", """
                    <title>Device review</title><article><p>$editorial</p>
                    <div $attribute="$marker"><p>UnrelatedPromotion costs 100 dollars. Buy now.</p></div></article>
                """.trimIndent())
                assertTrue(page.jsoupBodyText.contains("battery life"))
                assertTrue(requireNotNull(page.readabilityArticle).textContent.contains("battery life"))
                assertFalse("Jsoup: $attribute=$marker", page.jsoupBodyText.contains("UnrelatedPromotion"))
                assertFalse("Readability: $attribute=$marker", page.readabilityArticle.textContent.contains("UnrelatedPromotion"))
                assertFalse("Prompt: $attribute=$marker", GeminiTaggingService.buildPrompt(page.resolvedUrl, page, AppLanguage.ENGLISH).contains("UnrelatedPromotion"))
            }
        }
    }

    @Test fun unrelatedNamesAffiliateWidgetsAndProductMentionsRemain() {
        val page = WebPageParser.parseHtml("https://example.com/review", """
            <title>Device review</title><article><p>$editorial</p>
            <div class="download reading adventure shadow" id="adapter"><p>Logicool G304 costs 100 dollars in this review.</p></div>
            <div class="af_box"><p>AffiliateWidget remains available for inspection.</p></div>
            </article>
        """.trimIndent())
        for (text in listOf(page.jsoupBodyText, requireNotNull(page.readabilityArticle).textContent)) {
            assertTrue(text.contains("Logicool G304"))
            assertTrue(text.contains("100 dollars"))
            assertTrue(text.contains("AffiliateWidget"))
        }
    }

    @Test fun nestedAdsDisappearWithoutLosingMetadataOrStructuralRoots() {
        val page = WebPageParser.parseHtml("https://example.com/review", """
            <html class="ad"><head><title>Device review</title>
            <base href="https://cdn.example.com/assets/">
            <meta property="og:description" content="Original metadata">
            <meta property="og:image" content="cover.png">
            <link rel="icon" href="icon.png">
            <script type="application/ld+json">{"@type":"Article","headline":"Device review"}</script>
            </head><body id="ads"><main class="advertisement"><article class="ad">
            <p>$editorial</p><div class="ad-slot"><div class="ads"><p>NestedPromotion</p></div></div>
            <p>EditorialConclusion stays visible.</p></article></main></body></html>
        """.trimIndent())
        assertEquals("Original metadata", page.ogpTags["og:description"])
        assertEquals("Article", page.jsonLdList.single().type)
        assertTrue(requireNotNull(page.iconCandidates).any { it.url == "https://cdn.example.com/assets/icon.png" })
        assertEquals("https://cdn.example.com/assets/cover.png", ArticlePreparation.prepare(page).ogImageUrl)
        for (text in listOf(page.jsoupBodyText, requireNotNull(page.readabilityArticle).textContent)) {
            assertTrue(text.contains("EditorialConclusion"))
            assertFalse(text.contains("NestedPromotion"))
        }
    }

    @Test fun preparedSnippetAndPromptKeepTheExistingLimitAndFallback() {
        val parsed = WebPageParser.parseHtml("https://example.com/review", "<title>Device review</title><p>$editorial</p>")
        val text = "A".repeat(799) + "\n" + "TruncatedTail"
        val page = parsed.copy(readabilityArticle = requireNotNull(parsed.readabilityArticle).copy(textContent = text, charCount = text.length))
        val expected = "A".repeat(799) + " "
        assertEquals(expected, ArticlePreparation.prepare(page).bodySnippet)
        assertTrue(GeminiTaggingService.buildPrompt(page.resolvedUrl, page, AppLanguage.ENGLISH).endsWith("Content: $expected"))
        assertFalse(GeminiTaggingService.buildPrompt(page.resolvedUrl, page, AppLanguage.ENGLISH).contains("TruncatedTail"))
        val fallback = page.copy(readabilityArticle = null, jsoupBodyText = "Fallback\nbody")
        assertTrue(GeminiTaggingService.buildPrompt(page.resolvedUrl, fallback, AppLanguage.ENGLISH).endsWith("Content: Fallback body"))
    }
}
