package me.bubblespark.tidilo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DomainIconResolverTest {
    private val page = "https://example.com/article"

    @Test
    fun explicitPreferredIconSkipsHtmlAndLowerCandidates() = runBlocking {
        val calls = mutableListOf<String>()
        val result = DomainIconResolver.resolve(page, "example.com", "/apple.png", "/favicon.png",
            discover = { error("HTML must not be requested") },
            download = { calls.add(it); ResolvedDomainIcon("apple", 180, 180) })
        assertEquals("apple", result)
        assertEquals(listOf("https://example.com/apple.png"), calls)
    }

    @Test
    fun bestSmallHighPriorityIconWinsBeforeStandardTier() = runBlocking {
        val calls = mutableListOf<String>()
        val result = DomainIconResolver.resolve(page, "example.com", discover = {
            listOf(DomainIconCandidate("/standard"), DomainIconCandidate("/high1", true), DomainIconCandidate("/high2", true))
        }, download = {
            calls.add(it)
            if (it.endsWith("high1")) ResolvedDomainIcon("small", 96, 96) else ResolvedDomainIcon("best", 144, 144)
        })
        assertEquals("best", result)
        assertEquals(listOf("https://example.com/high1", "https://example.com/high2"), calls)
    }

    @Test
    fun unusableArticleCandidatesFallBackToRootOnce() = runBlocking {
        val pages = mutableListOf<String>()
        val result = DomainIconResolver.resolve(page, "example.com", discover = {
            pages.add(it)
            listOf(DomainIconCandidate(if (it == page) "/bad" else "/root"))
        }, download = { if (it.endsWith("root")) ResolvedDomainIcon("root", 96, 96) else null })
        assertEquals("root", result)
        assertEquals(listOf(page, "https://example.com"), pages)
    }

    @Test
    fun usableArticleIconSkipsRootAndGoogle() = runBlocking {
        val pages = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val result = DomainIconResolver.resolve(page, "example.com", discover = {
            pages.add(it); listOf(DomainIconCandidate("/icon"))
        }, download = { calls.add(it); ResolvedDomainIcon("icon", 96, 120) })
        assertEquals("icon", result)
        assertEquals(listOf(page), pages)
        assertEquals(listOf("https://example.com/icon"), calls)
    }

    @Test
    fun google256UsableImageSkips128EvenBelowPreferredSize() = runBlocking {
        val calls = mutableListOf<String>()
        val result = DomainIconResolver.resolve("https://example.com/", "example.com", discover = { emptyList() }, download = {
            calls.add(it)
            if (it.endsWith("sz=256")) ResolvedDomainIcon("google", 128, 128) else null
        })
        assertEquals("google", result)
        assertEquals(listOf("https://example.com/apple-touch-icon.png", "https://example.com/apple-touch-icon-precomposed.png",
            "https://www.google.com/s2/favicons?domain=example.com&sz=256"), calls)
    }

    @Test
    fun duplicateUrlsAreFetchedOnceAndTooSmallImagesAreRejected() = runBlocking {
        val calls = mutableListOf<String>()
        val result = DomainIconResolver.resolve(page, "example.com", "/same#one", "/same#two",
            discover = { listOf(DomainIconCandidate("https://example.com/same")) },
            download = { calls.add(it); ResolvedDomainIcon("too small", 95, 200) })
        assertNull(result)
        assertEquals(1, calls.count { it == "https://example.com/same" })
        assertEquals(5, calls.size)
    }

    @Test
    fun parserHonorsHtmlBaseForRelativeIconUrls() {
        assertEquals(listOf(DomainIconCandidate("https://cdn.example.com/assets/icon.png", true)),
            DomainIconResolver.parseHtml("<base href='https://cdn.example.com/assets/'><link rel='apple-touch-icon' href='icon.png'>", page))
    }

    @Test
    fun parserRetainsPriorityAndResolvesRelativeCandidates() {
        val candidates = DomainIconResolver.parseHtml("""
            <link rel="icon" href="small.png" sizes="32x32">
            <link rel="ICON" href="large.png" sizes="16x16 180x180">
            <link rel="apple-touch-icon-precomposed" href="/apple.png">
            <meta name="msapplication-TileImage" content="/tile.png">
            <link rel="icon" href="data:image/png;base64,abc">
        """.trimIndent(), page)
        assertEquals(listOf(
            DomainIconCandidate("https://example.com/small.png"),
            DomainIconCandidate("https://example.com/large.png", true),
            DomainIconCandidate("https://example.com/apple.png", true),
            DomainIconCandidate("https://example.com/tile.png", true)
        ), candidates)
    }
}
