package me.bubblespark.tidilo.data

import java.io.File
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.cache.MediaCacheLayout

data class CacheStorageSnapshot(
    val iconFiles: Int = 0, val iconBytes: Long = 0,
    val ogFiles: Int = 0, val ogBytes: Long = 0, val otherBytes: Long = 0,
    val checkedAt: Instant = Instant.now()
) { val totalBytes: Long get() = iconBytes + ogBytes + otherBytes }

internal object CacheStorageRepository {
    suspend fun scan(root: File): CacheStorageSnapshot = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        var icons = 0
        var iconBytes = 0L
        var images = 0
        var imageBytes = 0L
        var otherBytes = 0L
        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val classification = MediaCacheLayout.classifyRelativePath(rootPath.relativize(file))
                when (classification.bucket) {
                    MediaCacheLayout.CacheBucket.DOMAIN_ICONS -> {
                        iconBytes += attrs.size()
                        if (classification.isPrimaryAsset) icons++
                    }
                    MediaCacheLayout.CacheBucket.OG_IMAGES -> {
                        imageBytes += attrs.size()
                        if (classification.isPrimaryAsset) images++
                    }
                    MediaCacheLayout.CacheBucket.OTHER -> otherBytes += attrs.size()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                context.ensureActive()
                if (exc is NoSuchFileException) return FileVisitResult.CONTINUE
                throw exc
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                context.ensureActive()
                if (exc != null && exc !is NoSuchFileException) throw exc
                return FileVisitResult.CONTINUE
            }
        })
        CacheStorageSnapshot(icons, iconBytes, images, imageBytes, otherBytes, Instant.now())
    }
}

fun formatCacheBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    val kilobytes = safeBytes / 1024 + if (safeBytes % 1024 > 0) 1 else 0
    return String.format(Locale.US, "%,d KB (%,.1f MB)", kilobytes, safeBytes / 1048576.0)
}
