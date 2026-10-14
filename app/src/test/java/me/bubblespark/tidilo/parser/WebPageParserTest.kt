package me.bubblespark.tidilo.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageParserTest {

    @Test
    fun testParseHtmlWithFullElements() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <title>テスト記事タイトル - Sample Tech Blog</title>
                <link rel="canonical" href="https://example.com/articles/sample-1">
                <!-- Standard Meta -->
                <meta name="description" content="これはAIタギングのためのテスト記事概要です。">
                <meta name="keywords" content="Android,Kotlin,Jetpack Compose,AICore">
                <meta name="author" content="Tidilo Dev">
                <!-- OGP -->
                <meta property="og:title" content="テスト記事タイトル">
                <meta property="og:description" content="OGP用の説明テキストです。">
                <meta property="og:type" content="article">
                <meta property="og:url" content="https://example.com/articles/sample-1">
                <meta property="og:image" content="https://example.com/images/thumb.png">
                <!-- Twitter -->
                <meta name="twitter:card" content="summary_large_image">
                <meta name="twitter:title" content="Twitter用タイトル">
                <!-- JSON-LD -->
                <script type="application/ld+json">
                {
                    "@context": "https://schema.org",
                    "@type": "TechArticle",
                    "headline": "テスト記事タイトル",
                    "description": "JSON-LDによる構造化データの解説です。",
                    "datePublished": "2026-10-10",
                    "author": {
                        "@type": "Person",
                        "name": "Tidilo Team"
                    }
                }
                </script>
            </head>
            <body>
                <header>
                    <nav><a href="/">Home</a> | <a href="/about">About</a></nav>
                </header>
                <main>
                    <h1>Android AICore と Web Parser の連携について</h1>
                    <p>この段落は記事の導入部です。Gemini Nano を活用してスマートなタギングを実現します。</p>
                    <h2>1. 要素抽出のポイント</h2>
                    <p>タイトルやOGP、JSON-LDなどのメタデータに加え、ReadabilityやJsoupを用いた本文抽出が不可欠です。</p>
                    <h2>2. まとめ</h2>
                    <p>これにより、高精度なタグ分類が可能となります。</p>
                </main>
                <footer>
                    <p>© 2026 Tidilo. All rights reserved.</p>
                </footer>
            </body>
            </html>
        """.trimIndent()

        val parsed = WebPageParser.parseHtml(
            url = "https://example.com/articles/sample-1",
            html = sampleHtml
        )

        // 1. Basic metadata
        assertEquals("ja", parsed.htmlLang)
        assertEquals("テスト記事タイトル - Sample Tech Blog", parsed.title)
        assertEquals("https://example.com/articles/sample-1", parsed.canonicalUrl)

        // 2. OGP & Twitter
        assertEquals("テスト記事タイトル", parsed.ogpTags["og:title"])
        assertEquals("OGP用の説明テキストです。", parsed.ogpTags["og:description"])
        assertEquals("article", parsed.ogpTags["og:type"])
        assertEquals("summary_large_image", parsed.twitterTags["twitter:card"])

        // 3. Standard Meta
        assertEquals("これはAIタギングのためのテスト記事概要です。", parsed.standardMetaTags["description"])
        assertEquals("Android,Kotlin,Jetpack Compose,AICore", parsed.standardMetaTags["keywords"])

        // 4. JSON-LD
        assertTrue("JSON-LD should be detected", parsed.jsonLdList.isNotEmpty())
        val jsonLd = parsed.jsonLdList.first()
        assertEquals("TechArticle", jsonLd.type)
        assertEquals("テスト記事タイトル", jsonLd.headlineOrName)

        // 5. Headings
        assertEquals(1, parsed.h1List.size)
        assertEquals("Android AICore と Web Parser の連携について", parsed.h1List.first())
        assertEquals(2, parsed.h2List.size)

        // 6. Main content extraction
        assertTrue("Jsoup clean text should not be blank", parsed.jsoupBodyText.isNotBlank())
        assertTrue("Jsoup should contain main content", parsed.jsoupBodyText.contains("Gemini Nano を活用してスマートなタギングを実現します。"))
        // Verify navigation and footer elements are stripped
        assertTrue("Jsoup clean text should not contain header nav", !parsed.jsoupBodyText.contains("Home | About"))
        assertTrue("Jsoup clean text should not contain footer", !parsed.jsoupBodyText.contains("All rights reserved."))

        // Verify Readability4J output
        assertNotNull(parsed.readabilityArticle)
        assertTrue(parsed.readabilityArticle!!.textContent.isNotBlank())
        assertTrue(parsed.readabilityArticle!!.charCount > 0)

        // Verify regular articles yield false for NSFW signal
        org.junit.Assert.assertFalse(parsed.isNsfwSignal)
    }

    @Test
    fun testNsfwSignalDetectionFromTitleKeywords() {
        val keywords = listOf("R-18", "R18", "18禁", "成人向け", "成年コミック")
        for (kw in keywords) {
            val html = """
                <html><head><title>作品タイトル 【$kw】</title></head><body>本文</body></html>
            """.trimIndent()
            val parsed = WebPageParser.parseHtml("https://example.com/item", html)
            assertTrue("Expected NSFW signal for keyword: $kw", parsed.isNsfwSignal)
        }
    }

    @Test
    fun testNsfwSignalDetectionFromMetaRating() {
        val ratings = listOf("RTA-5042-1996-1400-1577-RTA", "R-18", "R18", "18+", "adult", "mature")
        for (rating in ratings) {
            val html = """
                <html><head>
                    <title>Sample Work</title>
                    <meta name="rating" content="$rating">
                </head><body>Content</body></html>
            """.trimIndent()
            val parsed = WebPageParser.parseHtml("https://example.com/item", html)
            assertTrue("Expected NSFW signal for meta rating: $rating", parsed.isNsfwSignal)
        }
    }

    @Test
    fun testNsfwSignalDetectionFromJsonLd() {
        // 1. isFamilyFriendly: false
        val html1 = """
            <html><head>
                <script type="application/ld+json">
                {"@type": "Product", "isFamilyFriendly": false}
                </script>
            </head><body>Content</body></html>
        """.trimIndent()
        val parsed1 = WebPageParser.parseHtml("https://example.com/item", html1)
        assertTrue("Expected NSFW signal for isFamilyFriendly: false", parsed1.isNsfwSignal)

        // 2. contentRating: "R18"
        val html2 = """
            <html><head>
                <script type="application/ld+json">
                {"@type": "MediaObject", "contentRating": "R18"}
                </script>
            </head><body>Content</body></html>
        """.trimIndent()
        val parsed2 = WebPageParser.parseHtml("https://example.com/item", html2)
        assertTrue("Expected NSFW signal for contentRating: R18", parsed2.isNsfwSignal)

        // 3. typicalAgeRange: "18+"
        val html3 = """
            <html><head>
                <script type="application/ld+json">
                {"@type": "CreativeWork", "typicalAgeRange": "18+"}
                </script>
            </head><body>Content</body></html>
        """.trimIndent()
        val parsed3 = WebPageParser.parseHtml("https://example.com/item", html3)
        assertTrue("Expected NSFW signal for typicalAgeRange: 18+", parsed3.isNsfwSignal)
    }

    @Test
    fun testPixivDesktopArtworkRtaRatingNsfwDetection() {
        val html = """
            <!DOCTYPE html><html lang="ja"><head>
                <title>#にじさんじ ９月まとめ１ - 水木のイラスト - pixiv</title>
                <meta name="rating" content="RTA-5042-1996-1400-1577-RTA" data-next-head=""/>
            </head><body>Content</body></html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://www.pixiv.net/artworks/150183100", html)
        assertEquals("RTA-5042-1996-1400-1577-RTA", parsed.standardMetaTags["rating"])
        assertTrue("Expected NSFW signal from Pixiv RTA rating", parsed.isNsfwSignal)
    }

    @Test
    fun testNsfwSignalDetectionFromDomainPolicy() {
        val testDomain = "adult-site-example.com"
        me.bubblespark.tidilo.data.DomainPolicyManager.registerDomain(testDomain, isNsfw = true)
        me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfw(testDomain, true)

        val cleanHtml = """
            <html><head><title>Clean Content</title></head><body>Normal text</body></html>
        """.trimIndent()

        val parsed = WebPageParser.parseHtml("https://$testDomain/page1", cleanHtml)
        assertTrue("Expected NSFW signal unconditionally when domain is designated as NSFW", parsed.isNsfwSignal)

        me.bubblespark.tidilo.data.DomainPolicyManager.setDomainNsfw(testDomain, false)
        val parsedAfterDisable = WebPageParser.parseHtml("https://$testDomain/page1", cleanHtml)
        org.junit.Assert.assertFalse(parsedAfterDisable.isNsfwSignal)
    }
}
