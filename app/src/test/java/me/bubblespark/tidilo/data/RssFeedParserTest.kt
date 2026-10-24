package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.parser.RssFeedParser
import org.junit.Assert.*
import org.junit.Test

class RssFeedParserTest {
    private fun metadata(xml: String): Pair<String?, String?> {
        val document = RssFeedParser.parse(xml, "https://feed.example/xml/feed")
        return document.iconUrl to document.siteUrl
    }

    @Test fun feedMetadataUsesDirectChildrenAndResolvesRelativeUrls() {
        assertEquals("https://feed.example/logo.png?size=200" to "https://publisher.example/", metadata("""
            <rss><channel><title>Feed</title><link>https://publisher.example/</link>
            <image xml:base="../"><url>logo.png?size=200#fragment</url></image>
            <item><link>https://other.example/article</link><image><url>wrong.png</url></image></item>
            </channel></rss>
        """))
    }

    @Test fun rdfRootImageAndAtomIconLogoAndPublisherAreParsed() {
        assertEquals("https://feed.example/icon.png" to "https://publisher.example/", metadata("""
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><channel>
            <link>https://publisher.example/</link></channel><image><url>/icon.png</url></image></rdf:RDF>
        """))
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://publisher.example/base/">
            <link rel="self" href="feed.xml"/><link rel="alternate" href="../"/>
            <icon>icon.png</icon><logo>logo.png</logo></feed>"""
        assertEquals("https://publisher.example/base/icon.png" to "https://publisher.example/", metadata(atom))
        assertEquals("https://publisher.example/base/logo.png" to "https://publisher.example/",
            metadata(atom.replace("<icon>icon.png</icon>", "<icon>javascript:bad</icon>")))
    }

    @Test fun missingAndUnsafeMetadataRemainNull() {
        assertEquals(null to null, metadata("""<rss><channel><link>https://user:password@publisher.example/</link>
            <image><url>file:///icon.png</url></image><item><link>https://article.example/</link></item></channel></rss>"""))
        assertEquals(null to null, metadata("""<feed><link rel="self" href="feed.xml"/></feed>"""))
    }

    @Test fun feedMetadataIgnoresExtensionNamespaceCollisions() {
        assertEquals("https://feed.example/rss.png" to "https://publisher.example/", metadata("""
            <rss xmlns:atom="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
            <channel><atom:link rel="self" href="https://feed.example/xml"/>
            <link>https://publisher.example/</link><media:image><media:url>https://wrong.example/icon</media:url></media:image>
            <image><url>/rss.png</url></image></channel></rss>
        """))
        assertEquals("https://feed.example/atom.png" to "https://publisher.example/", metadata("""
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:m="http://search.yahoo.com/mrss/">
            <m:icon>https://wrong.example/icon</m:icon><icon>/atom.png</icon>
            <m:link rel="alternate" href="https://wrong.example/"/><link rel="alternate" href="https://publisher.example/"/></feed>
        """))
    }

    @Test fun atomBodyDoesNotSelectMediaContentEvenWithAnArbitraryPrefix() {
        val xml = """<a:feed xmlns:a="http://www.w3.org/2005/Atom" xmlns:m="http://search.yahoo.com/mrss/"><a:entry><a:link href="https://example.com/post"/><m:content url="https://example.com/image.png"/><a:content type="html">&lt;p&gt;Actual body&lt;/p&gt;</a:content></a:entry></a:feed>"""
        val entry = RssFeedParser.parse(xml, "https://example.com/feed").entries.single()
        assertEquals("Actual body", entry.bodySnippet)
        val summaryOnly = xml.replace("<a:content type=\"html\">&lt;p&gt;Actual body&lt;/p&gt;</a:content>", "<a:summary>Summary body</a:summary>")
        assertEquals("Summary body", RssFeedParser.parse(summaryOnly, "https://example.com/feed").entries.single().bodySnippet)
    }
    @Test fun rssUsesEncodedContentMediaAndPublicationWithoutHtml() {
        val xml = """<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Feed &amp; News</title><item><guid>urn:one</guid><link>/post?a=1&amp;b=2#anchor</link><title><![CDATA[<b>Hello</b>]]></title><description><![CDATA[<p>Short &amp; sweet</p>]]></description><content:encoded><![CDATA[<p>Long content</p><img src="/inline.png">]]></content:encoded><media:thumbnail url="/media.png"/><pubDate>Fri, 23 Oct 2026 12:00:00 GMT</pubDate></item></channel></rss>"""
        val parsed = RssFeedParser.parse(xml, "https://example.com/feed.xml")
        assertEquals("Feed & News", parsed.title)
        val entry = parsed.entries.single()
        assertEquals("urn:one", entry.key)
        assertEquals("https://example.com/post?a=1&b=2", entry.url)
        assertEquals("Hello", entry.title)
        assertEquals("Short & sweet", entry.description)
        assertEquals("Long content", entry.bodySnippet)
        assertEquals("https://example.com/media.png", entry.imageUrl)
        assertNotNull(entry.publishedAt)
    }
    @Test fun atomResolvesAlternateLinksXmlBaseAndInlineImages() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://site.example/base/"><title>Atom</title><entry xml:base="posts/"><id>tag:example,1</id><link rel="self" href="entry.xml"/><link rel="alternate" type="text/html" href="article"/><title>Post</title><content type="html">&lt;p&gt;Body&lt;/p&gt;&lt;img src="picture.png"&gt;</content><updated>2026-10-23T12:00:00Z</updated></entry></feed>"""
        val entry = RssFeedParser.parse(xml, "https://origin.example/feed").entries.single()
        assertEquals("https://site.example/base/posts/article", entry.url)
        assertEquals("https://site.example/base/posts/picture.png", entry.imageUrl)
        assertEquals("Body", entry.bodySnippet)
        assertNull("updated is not a publication date", entry.publishedAt)
    }
    @Test fun rdfInvalidEntriesMissingDatesAndEmptyBodiesAreHandled() {
        val xml = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>RDF</title></channel><item rdf:about="urn:1"><title>Empty</title><link>https://example.com/one</link><dc:date>invalid</dc:date></item><item><link>javascript:bad()</link></item></rdf:RDF>"""
        val entry = RssFeedParser.parse(xml, "https://example.com/feed").entries.single()
        assertEquals("", entry.bodySnippet)
        assertNull(entry.publishedAt)
        assertEquals("urn:1", entry.key)
    }
    @Test fun truncatesBodyAndRejectsHtmlAndBrokenFeedDocuments() {
        val xml = "<rss><channel><item><link>https://example.com/a</link><description>${"a".repeat(1200)}</description></item></channel></rss>"
        assertEquals(800, RssFeedParser.parse(xml, "https://example.com/feed").entries.single().bodySnippet.length)
        for (invalid in listOf("<html><title>Login</title></html>", "<rss><channel><item>", "garbage")) {
            try { RssFeedParser.parse(invalid, "https://example.com/feed"); fail(invalid) }
            catch (_: IllegalArgumentException) { }
        }
    }
}
