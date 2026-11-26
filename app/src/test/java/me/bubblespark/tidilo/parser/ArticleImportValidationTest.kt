package me.bubblespark.tidilo.parser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArticleImportValidationTest {

    @Test fun redditJsChallengeFormWithShortBodyIsDetectedAndRejected() {
        val html = """
            <html>
            <head><title>Reddit</title></head>
            <body>
                <p>Please wait...</p>
                <form hidden method="GET" action="/">
                    <input type="hidden" name="solution" />
                    <input type="hidden" name="js_challenge" value="1" />
                    <input type="hidden" name="jsc_token" value="abc" />
                </form>
                <script>/* challenge */</script>
            </body>
            </html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://www.reddit.com/r/test/comments/123/", html)
        assertTrue(parsed.isChallengePage)
        try {
            ArticlePreparation.prepareForImport(parsed)
            fail("Expected ArticleAccessBlocked")
        } catch (expected: ArticleAccessBlocked) {
            assertEquals(ArticleAccessBlocked.MESSAGE, expected.message)
        }
    }

    @Test fun cloudflareChallengeFormWithoutArticleMetadataIsRejected() {
        val html = """
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <div id="cf-challenge-running">Checking your browser</div>
                <form id="challenge-form" action="/cdn-cgi/challenge"></form>
            </body>
            </html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://example.com/post", html)
        assertTrue(parsed.isChallengePage)
        try {
            ArticlePreparation.prepareForImport(parsed)
            fail("Expected ArticleAccessBlocked")
        } catch (expected: ArticleAccessBlocked) {
            assertEquals(ArticleAccessBlocked.MESSAGE, expected.message)
        }
    }

    @Test fun challengeFormTakesPrecedenceOverMetaDescriptionAndJsonLd() {
        val html = """
            <html>
            <head>
                <title>Just a moment...</title>
                <meta name="description" content="Verify your browser" />
                <meta property="og:description" content="Security check" />
                <script type="application/ld+json">{"@type":"WebPage","name":"Security"}</script>
            </head>
            <body>
                <p>Checking your browser</p>
                <form id="challenge-form" action="/cdn-cgi/challenge"></form>
            </body>
            </html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://example.com/post", html)
        assertTrue(parsed.isChallengePage)
        try {
            ArticlePreparation.prepareForImport(parsed)
            fail("Expected ArticleAccessBlocked")
        } catch (expected: ArticleAccessBlocked) {
            assertEquals(ArticleAccessBlocked.MESSAGE, expected.message)
        }
    }

    @Test fun articleDiscussingJsChallengeInProseIsNotFlaggedAsChallenge() {
        val html = """
            <html>
            <head>
                <title>Understanding Anti-Bot Challenges</title>
                <meta name="description" content="How WAF challenge forms work." />
            </head>
            <body>
                <article>
                    <p>Reddit uses <code>js_challenge=1</code> and <code>jsc_token</code> query parameters in its interstitial.</p>
                </article>
            </body>
            </html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://example.com/article", html)
        assertFalse(parsed.isChallengePage)
        val prepared = ArticlePreparation.prepareForImport(parsed)
        assertEquals("Understanding Anti-Bot Challenges", prepared.title)
        assertEquals("How WAF challenge forms work.", prepared.description)
    }

    @Test fun emptyHttp200HtmlThrowsArticleContentUnavailableWhilePrepareStaysPure() {
        val html = "<html><head><title>Example</title></head><body><script>var x=1;</script></body></html>"
        val parsed = WebPageParser.parseHtml("https://example.com/empty", html)
        assertFalse(parsed.isChallengePage)
        val pure = ArticlePreparation.prepare(parsed)
        assertEquals("", pure.bodySnippet)
        assertEquals("", pure.description)

        try {
            ArticlePreparation.prepareForImport(parsed)
            fail("Expected ArticleContentUnavailable")
        } catch (expected: ArticleContentUnavailable) {
            assertEquals(ArticleContentUnavailable.MESSAGE, expected.message)
        }
    }

    @Test fun spaPageWithOgDescriptionAndEmptyBodyPassesPrepareForImport() {
        val html = """
            <html>
            <head>
                <meta property="og:title" content="App" />
                <meta property="og:description" content="Real summary" />
            </head>
            <body><div id="root"></div></body>
            </html>
        """.trimIndent()
        val parsed = WebPageParser.parseHtml("https://spa.example/app", html)
        assertFalse(parsed.isChallengePage)
        val prepared = ArticlePreparation.prepareForImport(parsed)
        assertEquals("App", prepared.title)
        assertEquals("Real summary", prepared.description)
    }

    @Test fun non200SuccessStatusesAreRejectedByArticleAcquirer() = runBlocking {
        for (status in listOf(202, 204)) {
            val network = object : ArticleNetwork {
                override val scopeKey = "fixture"
                override suspend fun fetch(url: String) = ArticleResponse(
                    url = url,
                    status = status,
                    bytes = "<html><title>Accepted</title><p>Body</p></html>".toByteArray(),
                    contentType = "text/html"
                )
            }
            try {
                ArticleAcquirer.acquire("https://example.com/status/$status", network)
                fail("Expected ArticleHttpFailure for HTTP $status")
            } catch (expected: ArticleHttpFailure) {
                assertEquals(status, expected.code)
            }
        }
    }
}
