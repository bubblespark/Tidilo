package me.bubblespark.tidilo.data

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CacheStorageRepositoryTest {
    @Test fun measuresPhysicalFilesAndOtherCacheWithoutCountingMarkersAsImages() = runBlocking {
        val root = Files.createTempDirectory("summary-cache").toFile()
        try {
            fun file(path: String, size: Int) = root.resolve(path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
            file("domain_icons/example.png", 100)
            file("domain_icons/example.none", 0)
            file("domain_icons/icon.tmp", 17)
            file("og_images/hash.webp", 200)
            file("og_images/latest_example.webp", 200)
            file("og_images/og.tmp", 20)
            file("other/nested.bin", 33)
            val result = CacheStorageRepository.scan(root)
            assertEquals(1, result.iconFiles)
            assertEquals(2, result.ogFiles)
            assertEquals(117L, result.iconBytes)
            assertEquals(420L, result.ogBytes)
            assertEquals(33L, result.otherBytes)
            assertEquals(570L, result.totalBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun missingDirectoryIsZeroWithoutCreatingIt() = runBlocking {
        val root = Files.createTempDirectory("summary-empty").toFile()
        try {
            val missing = root.resolve("missing")
            assertEquals(0L, CacheStorageRepository.scan(missing).totalBytes)
            assertFalse(missing.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun formatUsesBinaryUnitsGroupingAndFixedMbPrecision() {
        assertEquals("0 KB (0.0 MB)", formatCacheBytes(0))
        assertEquals("1 KB (0.0 MB)", formatCacheBytes(1))
        assertEquals("1,234 KB (1.2 MB)", formatCacheBytes(1234L * 1024))
        assertEquals("1,048,576 KB (1,024.0 MB)", formatCacheBytes(1024L * 1024 * 1024))
    }
    @Test fun clearRemovesNestedFilesAndRescanYieldsZero() = runBlocking {
        val root = Files.createTempDirectory("summary-clear-test").toFile()
        try {
            val ogDir = root.resolve("og_images").apply { mkdirs() }
            val iconDir = root.resolve("domain_icons").apply { mkdirs() }
            ogDir.resolve("sample.webp").writeBytes(ByteArray(256))
            iconDir.resolve("sample.png").writeBytes(ByteArray(128))

            val initial = CacheStorageRepository.scan(root)
            assertEquals(1, initial.ogFiles)
            assertEquals(1, initial.iconFiles)
            assertTrue(initial.totalBytes > 0)

            ogDir.listFiles()?.forEach { it.delete() }
            iconDir.listFiles()?.forEach { it.delete() }

            val afterClear = CacheStorageRepository.scan(root)
            assertEquals(0, afterClear.ogFiles)
            assertEquals(0, afterClear.iconFiles)
            assertEquals(0L, afterClear.totalBytes)
        } finally { root.deleteRecursively() }
    }
}
