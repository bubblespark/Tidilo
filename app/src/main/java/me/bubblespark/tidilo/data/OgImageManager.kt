package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.OgImageDao
import me.bubblespark.tidilo.data.db.OgImageEntity
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * Manager for downloading, downsampling, compressing, and caching og:image assets in the database.
 *
 * - Downloads og:image during the Processing phase.
 * - Extracts a composite key of (domain, filename) to prevent duplicate downloads.
 * - Downsamples safely using BitmapFactory.Options.inSampleSize to avoid OOM errors.
 * - Constrains maximum dimension to 640px while preserving original aspect ratio.
 * - Encodes as WebP lossy, ensuring the final byte array is under 50KB.
 */
object OgImageManager {

    const val MAX_IMAGE_DIMENSION = 640
    const val MAX_FILE_SIZE_BYTES = 50 * 1024 // 50KB
    const val DEFAULT_QUALITY = 75
    private const val TIMEOUT_MS = 10_000
    private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024 // 10MB max download safeguard
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class CompressedImageResult(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val mimeType: String = "image/webp"
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CompressedImageResult
            if (!bytes.contentEquals(other.bytes)) return false
            if (width != other.width) return false
            if (height != other.height) return false
            if (mimeType != other.mimeType) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    /**
     * Extracts normalized domain from a URL (lowercase, strips 'www.' and port).
     */
    fun extractDomain(url: String): String? {
        return try {
            val trimmed = url.trim()
            val uri = URI(if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) "https://$trimmed" else trimmed)
            val host = uri.host ?: return null
            host.removePrefix("www.").lowercase().substringBefore(':')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves an image URL against the base page URL, supporting relative paths.
     */
    fun resolveImageUrl(pageUrl: String, imageUrl: String): String {
        val trimmed = imageUrl.trim()
        return try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                val baseUri = URI(pageUrl)
                baseUri.resolve(trimmed).toString()
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * Extracts a clean filename from an image URL, stripping query parameters and hashes.
     * If the path does not end with a valid filename, falls back to a deterministic SHA-256 hash.
     */
    fun extractFilename(imageUrl: String): String {
        return try {
            val withoutQuery = imageUrl.substringBefore('?').substringBefore('#')
            val rawFilename = withoutQuery.substringAfterLast('/')
            val clean = rawFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim()
            if (clean.length in 3..120 && clean.contains('.')) {
                clean
            } else {
                fallbackHashFilename(imageUrl)
            }
        } catch (_: Exception) {
            fallbackHashFilename(imageUrl)
        }
    }

    private fun fallbackHashFilename(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            val hex = hash.joinToString("") { "%02x".format(it) }.take(16)
            "$hex.webp"
        } catch (_: Exception) {
            "${input.hashCode().toUInt().toString(16)}.webp"
        }
    }

    /**
     * Calculates the optimal inSampleSize for safe downsampling.
     */
    @VisibleForTesting
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight || (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Downsamples and compresses raw image bytes to WebP format under [maxBytes].
     */
    fun compressToWebp(
        rawBytes: ByteArray,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        maxBytes: Int = MAX_FILE_SIZE_BYTES
    ): CompressedImageResult? {
        if (rawBytes.isEmpty()) return null

        try {
            // 1. Measure dimensions without allocating full bitmap in memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            // 2. Decode with inSampleSize to safely fit near maxDimension
            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false

            val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                ?: return null

            // 3. Scale down exactly if either dimension still exceeds maxDimension
            val srcWidth = decodedBitmap.width
            val srcHeight = decodedBitmap.height
            val scaledBitmap = if (srcWidth > maxDimension || srcHeight > maxDimension) {
                val ratio = minOf(
                    maxDimension.toFloat() / srcWidth,
                    maxDimension.toFloat() / srcHeight
                )
                val targetW = (srcWidth * ratio).toInt().coerceAtLeast(1)
                val targetH = (srcHeight * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
                if (scaled !== decodedBitmap) {
                    decodedBitmap.recycle()
                }
                scaled
            } else {
                decodedBitmap
            }

            // 4. Compress to WebP lossy with quality fallback if size exceeds maxBytes
            var quality = DEFAULT_QUALITY
            val outStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, outStream)

            var compressedBytes = outStream.toByteArray()
            val qualitiesToTry = listOf(60, 45, 30)
            var tryIndex = 0

            while (compressedBytes.size >= maxBytes && tryIndex < qualitiesToTry.size) {
                quality = qualitiesToTry[tryIndex++]
                outStream.reset()
                scaledBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, outStream)
                compressedBytes = outStream.toByteArray()
            }

            val finalWidth = scaledBitmap.width
            val finalHeight = scaledBitmap.height
            scaledBitmap.recycle()

            if (compressedBytes.size >= maxBytes) {
                return null
            }

            return CompressedImageResult(
                bytes = compressedBytes,
                width = finalWidth,
                height = finalHeight
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Downloads, compresses, and persists an og:image to the database during Processing.
     * Skips download if the (domain, filename) record already exists in the database.
     *
     * @return true if successfully saved or already exists; false if failed.
     */
    suspend fun processAndSaveOgImage(
        pageUrl: String,
        ogImageUrl: String?,
        ogImageDao: OgImageDao
    ): Boolean = withContext(Dispatchers.IO) {
        if (ogImageUrl.isNullOrBlank()) return@withContext false

        val domain = extractDomain(pageUrl) ?: return@withContext false
        val resolvedUrl = resolveImageUrl(pageUrl, ogImageUrl)
        val filename = extractFilename(resolvedUrl)

        // 1. Check for duplicate record
        if (ogImageDao.exists(domain, filename)) {
            return@withContext true
        }

        // 2. Fetch raw image bytes from network
        val rawBytes = runCatching {
            val response = Jsoup.connect(resolvedUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(MAX_DOWNLOAD_BYTES)
                .ignoreContentType(true)
                .followRedirects(true)
                .execute()
            response.bodyAsBytes()
        }.getOrNull() ?: return@withContext false

        // 3. Downsample and compress to WebP under 50KB
        val compressed = compressToWebp(rawBytes) ?: return@withContext false

        // 4. Persist to database
        val entity = OgImageEntity(
            domain = domain,
            filename = filename,
            imageBlob = compressed.bytes,
            mimeType = compressed.mimeType,
            width = compressed.width,
            height = compressed.height,
            sizeBytes = compressed.bytes.size
        )

        val insertedId = ogImageDao.insertOrIgnore(entity)
        return@withContext insertedId >= 0
    }
}
