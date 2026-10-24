package me.bubblespark.tidilo.data

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class OgImageManagerTest {

    @Test
    fun testConstants() {
        assertEquals(640, OgImageManager.MAX_IMAGE_DIMENSION)
        assertEquals(50 * 1024, OgImageManager.MAX_FILE_SIZE_BYTES)
        assertEquals(75, OgImageManager.DEFAULT_QUALITY)
    }

    @Test
    fun testExtractDomain() {
        assertEquals("github.com", OgImageManager.extractDomain("https://www.github.com/torvalds/linux"))
        assertEquals("developer.android.com", OgImageManager.extractDomain("https://developer.android.com/reference/kotlin/"))
        assertEquals("sub.example.co.jp", OgImageManager.extractDomain("https://sub.example.co.jp:8080/path"))
        assertEquals("zenn.dev", OgImageManager.extractDomain("http://zenn.dev/articles/123"))
        assertNull(OgImageManager.extractDomain("   "))
    }

    @Test
    fun testResolveImageUrl_absoluteAndRelative() {
        // Absolute URL
        val absolute = "https://cdn.example.com/images/banner.png"
        assertEquals(absolute, OgImageManager.resolveImageUrl("https://example.com/page", absolute))

        // Relative path with leading slash
        val relativeRoot = "/assets/og.jpg"
        assertEquals("https://example.com/assets/og.jpg", OgImageManager.resolveImageUrl("https://example.com/news/article", relativeRoot))

        // Relative path without leading slash
        val relativeSub = "og.jpg"
        assertEquals("https://example.com/news/og.jpg", OgImageManager.resolveImageUrl("https://example.com/news/", relativeSub))
    }

    @Test
    fun testComputeImageKey_urlDistinctIdentity() {
        val urlA = "https://example.com/article-a/og.png"
        val urlB = "https://example.com/article-b/og.png"

        val keyA = OgImageManager.computeImageKey(urlA)
        val keyB = OgImageManager.computeImageKey(urlB)

        assertTrue("Key should end with .webp", keyA.endsWith(".webp"))
        assertTrue("Key should end with .webp", keyB.endsWith(".webp"))
        assertNotEquals("Different paths with same basename must have distinct keys", keyA, keyB)
    }

    @Test
    fun testComputeImageKey_queryAndFragmentHandling() {
        val baseWithQuery1 = "https://example.com/og.png?width=1200&quality=80"
        val baseWithQuery2 = "https://example.com/og.png?width=600&quality=80"
        val baseWithFragment1 = "https://example.com/og.png#section1"
        val baseWithFragment2 = "https://example.com/og.png#section2"
        val baseWithoutFragment = "https://example.com/og.png"

        val keyQuery1 = OgImageManager.computeImageKey(baseWithQuery1)
        val keyQuery2 = OgImageManager.computeImageKey(baseWithQuery2)
        val keyFrag1 = OgImageManager.computeImageKey(baseWithFragment1)
        val keyFrag2 = OgImageManager.computeImageKey(baseWithFragment2)
        val keyBase = OgImageManager.computeImageKey(baseWithoutFragment)

        // Query parameters identify different resources -> must differ
        assertNotEquals("Different queries must produce distinct keys", keyQuery1, keyQuery2)

        // Fragments do not identify different resources -> must be identical
        assertEquals("Fragments must be stripped and produce identical key", keyFrag1, keyFrag2)
        assertEquals("Fragment-bearing URL must match base URL key", keyBase, keyFrag1)
    }

    @Test
    fun testCalculateInSampleSize() {
        // Source 1280x720, Target 640x640 -> inSampleSize = 2
        val options1 = BitmapFactory.Options().apply {
            outWidth = 1280
            outHeight = 720
        }
        assertEquals(2, OgImageManager.calculateInSampleSize(options1, 640, 640))

        // Source 2560x1440, Target 640x640 -> inSampleSize = 4
        val options2 = BitmapFactory.Options().apply {
            outWidth = 2560
            outHeight = 1440
        }
        assertEquals(4, OgImageManager.calculateInSampleSize(options2, 640, 640))

        // Source 400x300, Target 640x640 -> inSampleSize = 1
        val options3 = BitmapFactory.Options().apply {
            outWidth = 400
            outHeight = 300
        }
        assertEquals(1, OgImageManager.calculateInSampleSize(options3, 640, 640))
    }

    @Test
    fun testCompressToWebp_emptyBytesReturnsNull() {
        val result = OgImageManager.compressToWebp(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun testCacheKeyFormats() {
        val key = OgImageManager.computeImageKey("https://zenn.dev/images/hero.jpg")
        assertEquals("zenn.dev/$key", OgImageManager.cacheKey("zenn.dev", key))
        assertEquals("zenn.dev/__latest__", OgImageManager.fallbackCacheKey("zenn.dev"))
    }

    @Test
    fun testBookmarkItemOgImageFilenameResolution() {
        val bookmark1 = BookmarkItem(
            id = "1",
            title = "Article 1",
            url = "https://zenn.dev/author/articles/article-one",
            description = "Desc 1",
            tag = Tag.create("Tech", "Android"),
            ogImageUrl = "https://cdn.zenn.dev/images/og1.png"
        )
        val expectedKey1 = OgImageManager.computeImageKey("https://cdn.zenn.dev/images/og1.png")
        assertEquals(expectedKey1, bookmark1.ogImageFilename)

        val bookmarkRelative = BookmarkItem(
            id = "2",
            title = "Article 2",
            url = "https://example.com/blog/post-1",
            description = "Desc 2",
            tag = Tag.create("Tech", "Web"),
            ogImageUrl = "/assets/banner.jpg"
        )
        val expectedRelativeKey = OgImageManager.computeImageKey("https://example.com/assets/banner.jpg")
        assertEquals(expectedRelativeKey, bookmarkRelative.ogImageFilename)

        val bookmarkNoOg = BookmarkItem(
            id = "3",
            title = "Article 3",
            url = "https://example.com/article-3",
            description = "Desc 3",
            tag = Tag.create("Tech", "Web"),
            ogImageUrl = null
        )
        assertNull(bookmarkNoOg.ogImageFilename)

        val bookmarkBlankOg = BookmarkItem(
            id = "4",
            title = "Article 4",
            url = "https://example.com/article-4",
            description = "Desc 4",
            tag = Tag.create("Tech", "Web"),
            ogImageUrl = "   "
        )
        assertNull(bookmarkBlankOg.ogImageFilename)
    }

    @Test
    fun testDistinctBookmarksSameDomainHaveDistinctFilenamesAndCacheKeys() {
        // Both bookmarks use identical basename 'cover.png' but different paths
        val b1 = BookmarkItem(
            id = "b1",
            title = "Title 1",
            url = "https://zenn.dev/user1/articles/first",
            description = "",
            tag = Tag.create("Tech", "Kotlin"),
            ogImageUrl = "https://zenn.dev/articles/1/cover.png"
        )
        val b2 = BookmarkItem(
            id = "b2",
            title = "Title 2",
            url = "https://zenn.dev/user2/articles/second",
            description = "",
            tag = Tag.create("Tech", "Kotlin"),
            ogImageUrl = "https://zenn.dev/articles/2/cover.png"
        )

        assertNotNull(b1.ogImageFilename)
        assertNotNull(b2.ogImageFilename)
        assertNotEquals("Identical basename with different paths must have distinct image identities",
            b1.ogImageFilename, b2.ogImageFilename)

        val key1 = OgImageManager.cacheKey("zenn.dev", b1.ogImageFilename!!)
        val key2 = OgImageManager.cacheKey("zenn.dev", b2.ogImageFilename!!)

        assertNotEquals("Cache keys for different bookmarks under same domain must differ", key1, key2)
        assertEquals("zenn.dev/${b1.ogImageFilename}", key1)
        assertEquals("zenn.dev/${b2.ogImageFilename}", key2)
    }

    @Test
    fun testGenerationTokenAndCacheCleared(): Unit = runBlocking {
        val initialGen = OgImageManager.getCurrentGeneration()
        assertTrue("Generation should be non-negative", initialGen >= 0L)

        val tempDir = java.nio.file.Files.createTempDirectory("og_image_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
            }
            var clearedReceived = false
            val job = launch {
                OgImageManager.cacheClearedEvent.collect {
                    clearedReceived = true
                }
            }
            yield()
            OgImageManager.clearImageCache(fakeContext)
            yield()
            val afterGen = OgImageManager.getCurrentGeneration()
            assertTrue("Clearing cache should increment generation", afterGen > initialGen)
            assertTrue("Clearing cache should emit cleared event", clearedReceived)
            job.cancel()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOgImageScopedObservationFiltersCorrectly(): Unit = runBlocking {
        val receivedForDomainA = mutableListOf<Long>()
        val receivedForDomainB = mutableListOf<Long>()

        val jobA = launch {
            OgImageManager.observeOgImageUpdates("domain-a.com").collect {
                receivedForDomainA.add(it)
            }
        }
        val jobB = launch {
            OgImageManager.observeOgImageUpdates("domain-b.com").collect {
                receivedForDomainB.add(it)
            }
        }
        delay(50)
        OgImageManager.notifyOgImageUpdated("domain-a.com")
        delay(50)

        assertEquals(2, receivedForDomainA.size)
        assertTrue(receivedForDomainA[1] > receivedForDomainA[0])
        assertEquals(1, receivedForDomainB.size)

        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun testOgImageSuccessiveUpdatesAndClearEmitDistinctRevisions(): Unit = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("og_distinct_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
            }
            val emissions = mutableListOf<Long>()
            val job = launch {
                OgImageManager.observeOgImageUpdates("distinct-og.com").collect {
                    emissions.add(it)
                }
            }
            delay(50)

            // Successive update 1
            OgImageManager.notifyOgImageUpdated("distinct-og.com")
            delay(50)

            // Successive update 2
            OgImageManager.notifyOgImageUpdated("distinct-og.com")
            delay(50)

            // Full cache clear
            OgImageManager.clearImageCache(fakeContext)
            delay(50)

            assertEquals(4, emissions.size)
            assertEquals(emissions.size, emissions.distinct().size)

            job.cancel()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOgImageResumingCollectionObservesLatestRevision(): Unit = runBlocking {
        val domain = "resuming-og.com"
        OgImageManager.notifyOgImageUpdated(domain)

        val emissions = mutableListOf<Long>()
        val job = launch {
            OgImageManager.observeOgImageUpdates(domain).collect {
                emissions.add(it)
            }
        }
        delay(50)

        assertTrue("Resuming collection must receive latest revision immediately", emissions.isNotEmpty())
        assertTrue("Latest revision must be non-zero", emissions.first() > 0L)
        job.cancel()
    }

    @Test
    fun observeOgImageUpdates_returnsEmptyFlowForBlankDomain(): Unit = runBlocking {
        val received = mutableListOf<Long>()
        val job = launch {
            OgImageManager.observeOgImageUpdates("   ").collect {
                received.add(it)
            }
        }
        delay(50)
        OgImageManager.notifyOgImageUpdated("example.com")
        delay(50)
        assertTrue(received.isEmpty())
        job.cancel()
    }

    @Test
    fun testObserveExactImageUpdatesFiltersByKey(): Unit = runBlocking {
        val targetKey = "target-key"
        val otherKey = "other-key"
        val received = mutableListOf<String>()

        val job = launch {
            OgImageManager.observeExactImageUpdates(targetKey).collect {
                received.add(it)
            }
        }
        delay(50)

        // Emitting update for a different key should not trigger the observer
        OgImageManager.notifyExactImageUpdatedForTesting("example.com", otherKey)
        delay(50)
        assertTrue(received.isEmpty())

        // Emitting update for the target key should trigger the observer
        OgImageManager.notifyExactImageUpdatedForTesting("example.com", targetKey)
        delay(50)
        assertEquals(listOf(targetKey), received)

        job.cancel()
    }

    @Test
    fun testExactImageUpdatesDeliveredEvenWhenBufferSaturated(): Unit = runBlocking {
        val targetKey = "burst-key"
        val received = mutableListOf<String>()

        val job = launch {
            OgImageManager.observeExactImageUpdates(targetKey).collect {
                received.add(it)
            }
        }
        delay(50)

        // Rapidly emit 80 updates (exceeding extraBufferCapacity = 64)
        for (i in 1..80) {
            OgImageManager.notifyExactImageUpdatedForTesting("example.com", if (i % 20 == 0) targetKey else "other-key-$i")
        }

        // Wait for expected emissions with a bounded timeout rather than relying on a fixed delay
        withTimeout(3000) {
            while (received.size < 4) {
                delay(20)
            }
        }

        // 4 of the 80 emissions were for targetKey (i = 20, 40, 60, 80)
        assertEquals(4, received.size)

        job.cancel()
    }
}
