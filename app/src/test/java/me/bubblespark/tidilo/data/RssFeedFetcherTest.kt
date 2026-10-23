package me.bubblespark.tidilo.data

import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.rss.RssFeedFetcher
import me.bubblespark.tidilo.data.rss.RssHttpPage
import org.junit.Assert.*
import org.junit.Test

class RssFeedFetcherTest {
    @Test fun conditionalFetchAndXmlImageAvoidArticleRequests() = runBlocking {
        val urls = mutableListOf<String>()
        val fetcher = RssFeedFetcher { url, headers ->
            urls.add(url)
            assertEquals("etag", headers["If-None-Match"])
            assertEquals("last", headers["If-Modified-Since"])
            RssHttpPage(200, url, """<rss><channel><title>Feed</title><item><title>Post</title><link>https://example.com/post</link><enclosure type="image/png" url="https://example.com/image.png"/></item></channel></rss>""", "new")
        }
        val result = fetcher.fetch("https://example.com/feed", "etag", "last")
        assertEquals("new", result.page.etag)
        assertEquals("https://example.com/image.png", result.document!!.entries.single().imageUrl)
        assertEquals(listOf("https://example.com/feed"), urls)
    }
    @Test fun notModifiedDoesNotParseAndMetadataFailureKeepsItem() = runBlocking {
        val unchanged = RssFeedFetcher { url, _ -> RssHttpPage(304, url, "") }
        assertNull(unchanged.fetch("https://example.com/feed", null, null).document)
        val fetcher = RssFeedFetcher { url, _ ->
            if (url.endsWith("/post")) throw java.io.IOException("Offline image metadata")
            RssHttpPage(200, url, "<rss><channel><item><title>Post</title><link>https://example.com/post</link><description>Feed text</description></item></channel></rss>")
        }
        val item = fetcher.enrich(fetcher.fetch("https://example.com/feed", null, null).document!!.entries.single())
        assertEquals("Feed text", item.description)
        assertNull(item.imageUrl)
    }
}
