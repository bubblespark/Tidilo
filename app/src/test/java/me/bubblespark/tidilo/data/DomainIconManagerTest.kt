package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainIconManagerTest {

    @Test
    fun extractDomain_removesWwwAndLowerCase() {
        assertEquals("github.com", DomainIconManager.extractDomain("https://www.github.com/torvalds/linux"))
        assertEquals("github.com", DomainIconManager.extractDomain("https://github.com/torvalds/linux"))
        assertEquals("developer.android.com", DomainIconManager.extractDomain("https://developer.android.com/reference/kotlin/"))
        assertEquals("zenn.dev", DomainIconManager.extractDomain("http://zenn.dev/articles/12345"))
        assertEquals("qiita.com", DomainIconManager.extractDomain("qiita.com/items/abc"))
        assertEquals("news.yahoo.co.jp", DomainIconManager.extractDomain("https://news.yahoo.co.jp/articles/123"))
    }

    @Test
    fun extractDomain_handlesPortsAndSubdomains() {
        assertEquals("localhost", DomainIconManager.extractDomain("http://localhost:8080/test"))
        assertEquals("sub.domain.co.jp", DomainIconManager.extractDomain("https://www.sub.domain.co.jp:443/path?query=1#hash"))
    }

    @Test
    fun extractDomain_returnsNullForInvalidUrl() {
        assertNull(DomainIconManager.extractDomain("   "))
    }

    @Test
    fun constants_verifyResolutionAndTimeoutSpecifications() {
        assertEquals(96, DomainIconManager.MIN_ICON_SIZE)
        assertEquals(384, DomainIconManager.MAX_ICON_SIZE)
        assertEquals(10_000L, DomainIconManager.DOMAIN_TIMEOUT_MS)
    }

    @Test
    fun generateDomainAccentColor_returnsConsistentColorForDomain() {
        val color1 = DomainIconManager.generateDomainAccentColor("github.com")
        val color2 = DomainIconManager.generateDomainAccentColor("github.com")
        assertEquals(color1, color2)

        val zennColor = DomainIconManager.generateDomainAccentColor("zenn.dev")
        assertNotNull(zennColor)
    }

    @Test
    fun testClearCacheIncrementsVersion() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("domain_icon_test").toFile()
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): java.io.File = java.io.File(tempDir, "cache").apply { mkdirs() }
                override fun getFilesDir(): java.io.File = java.io.File(tempDir, "files").apply { mkdirs() }
            }
            val initialVersion = DomainIconManager.iconUpdateVersion.value
            val initialGen = DomainIconManager.getCurrentGeneration()
            DomainIconManager.clearCache(fakeContext)
            assertTrue(DomainIconManager.iconUpdateVersion.value > initialVersion)
            assertTrue(DomainIconManager.getCurrentGeneration() > initialGen)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun iconUpdateVersion_initialValueIsNonNegative() {
        assertTrue(DomainIconManager.iconUpdateVersion.value >= 0L)
    }
}
