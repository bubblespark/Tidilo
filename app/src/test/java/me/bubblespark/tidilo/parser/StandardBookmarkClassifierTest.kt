package me.bubblespark.tidilo.parser

import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import org.junit.Assert.assertEquals
import org.junit.Test

class StandardBookmarkClassifierTest {
    @Test
    fun combinesIndependentAxesAndUsesFirstOpenGraphType() {
        val result = classify(
            """{"@context":"https://schema.org","@type":"Recipe","mainEntityOfPage":"?item=1"}""",
            """<meta property="og:type" content="article"><meta property="og:type" content="video.other">"""
        )
        assertEquals(StandardClassification(Category.ARTICLE, Subcategory.FOOD), result)
    }

    @Test
    fun resolvesGraphReferencesWithoutUsingNamesOrEmbeddedObjects() {
        val result = classify(
            """{"@context":"https://schema.org","@graph":[
                {"@id":"?item=1","@type":"WebPage","mainEntity":{"@id":"#primary"}},
                {"@id":"#primary","@type":["VideoObject","LearningResource"],"name":"Unrelated name"},
                {"@id":"#related","@type":"Product","name":"Page title"}
            ]}""",
            "<title>Page title</title>"
        )
        assertEquals(StandardClassification(Category.MEDIA, Subcategory.EDUCATION), result)
    }

    @Test
    fun conflictingPrimaryCategoriesCannotUseOpenGraphFallback() {
        val result = classify(
            """{"@context":"https://schema.org","@type":"WebPage","mainEntity":[
                {"@type":"Product"},{"@type":"TechArticle"}]}""",
            """<meta property="og:type" content="article">"""
        )
        assertEquals(StandardClassification(null, Subcategory.TECH), result)
    }

    @Test
    fun ignoresUnlinkedNodesAndMalformedData() {
        assertEquals(StandardClassification(), classify(
            """{"@context":"https://schema.org","@type":"TechArticle","headline":"Page title"}""",
            """<title>Page title</title><script type="application/ld+json">{bad</script>"""
        ))
    }

    @Test
    fun matchesTheCurrentUrlAndIgnoresEmbeddedVideo() {
        assertEquals(StandardClassification(Category.COMMERCE), classify(
            """{"@type":"https://schema.org/Product","https://schema.org/url":"?item=1",
                "video":{"@type":"https://schema.org/VideoObject"}}"""
        ))
    }

    @Test
    fun resolvesFragmentIdentifiedPageButIgnoresUnrelatedReverseReferences() {
        assertEquals(StandardClassification(Category.REFERENCE), classify(
            """{"@context":"https://schema.org","@graph":[
                {"@type":"WebPage","@id":"#page","mainEntity":{"@id":"#dataset"}},
                {"@type":"Dataset","@id":"#dataset"},
                {"@type":"Product","mainEntityOfPage":{"@id":"#related-container"}}
            ]}"""
        ))
    }

    @Test
    fun doesNotUseOpenGraphInsideAnEmbeddedTemplate() {
        assertEquals(StandardClassification(), classify("{}", """
            <template><meta property="og:type" content="article"></template>
        """))
    }

    @Test
    fun ignoresCompactTypesWhenContextOverridesTheVocabulary() {
        assertEquals(StandardClassification(), classify(
            """{"@context":["https://schema.org",{"@vocab":"https://example.net/"}],
                "@type":"Product","https://schema.org/url":"?item=1"}"""
        ))
    }

    private fun classify(json: String, head: String = ""): StandardClassification =
        classifyStandardMetadata(WebPageParser.parseHtml(
            "https://example.com/page?item=1",
            """<html><head>$head<script type="application/ld+json">$json</script></head></html>"""
        ))
}
