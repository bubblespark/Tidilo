package me.bubblespark.tidilo.data

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
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
    fun testExtractFilename_standardPaths() {
        assertEquals("hero_image.png", OgImageManager.extractFilename("https://example.com/assets/hero_image.png"))
        assertEquals("og-banner.jpg", OgImageManager.extractFilename("https://example.com/images/og-banner.jpg?w=1200&h=630#anchor"))
        assertEquals("thumbnail.webp", OgImageManager.extractFilename("https://cdn.site.org/v2/thumbnail.webp?token=xyz"))
    }

    @Test
    fun testExtractFilename_fallbackForMissingExtensionOrTrailingSlash() {
        val filename1 = OgImageManager.extractFilename("https://example.com/images/")
        assertTrue("Fallback should generate .webp extension", filename1.endsWith(".webp"))

        val filename2 = OgImageManager.extractFilename("https://example.com/api/og?title=test")
        assertTrue("Fallback should generate .webp extension", filename2.endsWith(".webp"))
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
        assertEquals("zenn.dev/hero.jpg", OgImageManager.cacheKey("zenn.dev", "hero.jpg"))
        assertEquals("github.com/og.png", OgImageManager.cacheKey("github.com", "og.png"))
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
        assertEquals("og1.png", bookmark1.ogImageFilename)

        val bookmarkRelative = BookmarkItem(
            id = "2",
            title = "Article 2",
            url = "https://example.com/blog/post-1",
            description = "Desc 2",
            tag = Tag.create("Tech", "Web"),
            ogImageUrl = "/assets/banner.jpg"
        )
        assertEquals("banner.jpg", bookmarkRelative.ogImageFilename)

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
        val b1 = BookmarkItem(
            id = "b1",
            title = "Title 1",
            url = "https://zenn.dev/user1/articles/first",
            description = "",
            tag = Tag.create("Tech", "Kotlin"),
            ogImageUrl = "https://zenn.dev/images/cover_first.webp"
        )
        val b2 = BookmarkItem(
            id = "b2",
            title = "Title 2",
            url = "https://zenn.dev/user2/articles/second",
            description = "",
            tag = Tag.create("Tech", "Kotlin"),
            ogImageUrl = "https://zenn.dev/images/cover_second.webp"
        )

        assertEquals("cover_first.webp", b1.ogImageFilename)
        assertEquals("cover_second.webp", b2.ogImageFilename)

        val key1 = OgImageManager.cacheKey("zenn.dev", b1.ogImageFilename!!)
        val key2 = OgImageManager.cacheKey("zenn.dev", b2.ogImageFilename!!)

        assertEquals("zenn.dev/cover_first.webp", key1)
        assertEquals("zenn.dev/cover_second.webp", key2)
        assertTrue("Cache keys for different bookmarks under same domain must differ", key1 != key2)
    }
}
