package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.collection.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manager for downloading, downsampling, compressing, and caching og:image assets in cacheDir.
 *
 * - Downloads og:image during the Processing phase.
 * - Extracts a URL-distinct SHA-256 hash identity (preserving query parameters, removing fragments).
 * - Downsamples safely using BitmapFactory.Options.inSampleSize to avoid OOM errors.
 * - Constrains maximum dimension to 640px while preserving original aspect ratio.
 * - Encodes as WebP lossy, ensuring the final byte array is under 50KB.
 * - Stores disposable image files under context.cacheDir/og_images.
 * - Supports generation token-based in-app cache clearing without affecting bookmark data.
 */
object OgImageManager {

    private const val MAX_CACHE_SIZE_BYTES = 16 * 1024 * 1024 // 16MB limit

    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val cacheGeneration = AtomicLong(0L)
    private val cacheMutex = Mutex()

    private val domainRevisions = ConcurrentHashMap<String, AtomicLong>()

    private val _ogImageUpdatedDomain = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val ogImageUpdatedDomain: SharedFlow<Pair<String, Long>> = _ogImageUpdatedDomain.asSharedFlow()

    private val _cacheClearedEvent = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val cacheClearedEvent: SharedFlow<Long> = _cacheClearedEvent.asSharedFlow()

    /**
     * Returns the composite monotonic revision for a given domain, combining its individual updates
     * and the global cache clearing generation.
     */
    fun getDomainRevision(domain: String): Long {
        val normalized = domain.trim().lowercase()
        val rev = domainRevisions[normalized]?.get() ?: 0L
        return rev + (cacheGeneration.get() * 1_000_000_000L)
    }

    /**
     * Emits a notification that a specific domain's OG image has been saved or updated.
     */
    fun notifyOgImageUpdated(domain: String) {
        val normalized = domain.trim().lowercase()
        val rev = domainRevisions.computeIfAbsent(normalized) { AtomicLong(0L) }.incrementAndGet()
        val currentRev = rev + (cacheGeneration.get() * 1_000_000_000L)
        _ogImageUpdatedDomain.tryEmit(Pair(normalized, currentRev))
    }

    /**
     * Returns a Flow that emits a distinct Long revision whenever the given domain's OG image is updated
     * or the full cache is cleared. Immediately emits the current revision upon collection start or resume.
     */
    fun observeOgImageUpdates(domain: String?): Flow<Long> {
        if (domain.isNullOrBlank()) return emptyFlow()
        val normalized = domain.trim().lowercase()
        return merge(
            ogImageUpdatedDomain
                .onSubscription { emit(normalized to getDomainRevision(normalized)) }
                .filter { it.first == normalized }
                .map { it.second },
            cacheClearedEvent
                .onSubscription { emit(getDomainRevision(normalized)) }
                .map { getDomainRevision(normalized) }
        ).distinctUntilChanged()
    }

    private val inFlightLoads = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /**
     * Returns the current cache generation token.
     */
    fun getCurrentGeneration(): Long = cacheGeneration.get()

    /**
     * Constructs a composite memory cache key for a domain and specific image key.
     */
    fun cacheKey(domain: String, imageKey: String): String = "$domain/$imageKey"

    /**
     * Constructs a fallback memory cache key for a domain's latest image.
     */
    fun fallbackCacheKey(domain: String): String = "$domain/__latest__"

    /**
     * Retrieves a cached ImageBitmap from in-memory LruCache.
     * Looks up by (domain, imageKey) if imageKey is provided; otherwise checks domain fallback key.
     */
    fun getCachedOgImageBitmap(domain: String, imageKey: String? = null): ImageBitmap? {
        if (!imageKey.isNullOrBlank()) {
            memoryCache.get(cacheKey(domain, imageKey))?.let { return it }
        }
        return memoryCache.get(fallbackCacheKey(domain))
    }

    /**
     * Directory inside cacheDir storing OG images.
     */
    fun getOgImageDir(context: Context): File {
        val dir = File(context.cacheDir, "og_images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Resolves the local cache file for a specific image key.
     */
    fun getOgImageFile(context: Context, imageKey: String): File {
        return File(getOgImageDir(context), imageKey)
    }

    /**
     * Resolves the local cache file for a domain's latest fallback image.
     */
    fun getDomainLatestImageFile(context: Context, domain: String): File {
        val safeDomain = domain.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        return File(getOgImageDir(context), "latest_$safeDomain.webp")
    }

    /**
     * Loads a cached OG image from cacheDir on Dispatchers.IO.
     *
     * 1. If imageKey is provided, looks up specific image (domain, imageKey).
     * 2. If imageKey is not provided or specific image is not found, falls back to the latest
     *    image for the domain.
     * 3. Stores the decoded ImageBitmap into in-memory LruCache.
     */
    suspend fun loadOgImage(
        context: Context,
        domain: String,
        imageKey: String? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val isSpecific = !imageKey.isNullOrBlank()
        val key = if (!imageKey.isNullOrBlank()) cacheKey(domain, imageKey) else fallbackCacheKey(domain)

        // 1. Check memory cache first
        if (isSpecific) {
            memoryCache.get(key)?.let { return@withContext it }
        } else {
            memoryCache.get(fallbackCacheKey(domain))?.let { return@withContext it }
        }

        // 2. Request deduplication
        val startGen = cacheGeneration.get()
        val flightKey = "$startGen|$key"
        val deferred = inFlightLoads.computeIfAbsent(flightKey) {
            CoroutineScope(Dispatchers.IO).async {
                try {
                    loadOgImageInternal(context, domain, imageKey, isSpecific, startGen)
                } finally {
                    inFlightLoads.remove(flightKey)
                }
            }
        }
        deferred.await()
    }

    /**
     * Backward-compatible alias for loadOgImage.
     */
    suspend fun loadOgImageFromDb(
        context: Context,
        domain: String,
        filename: String? = null
    ): ImageBitmap? = loadOgImage(context, domain, filename)

    private suspend fun loadOgImageInternal(
        context: Context,
        domain: String,
        imageKey: String?,
        isSpecific: Boolean,
        startGen: Long
    ): ImageBitmap? {
        val file = if (isSpecific) {
            val specificFile = if (imageKey != null) getOgImageFile(context, imageKey) else getDomainLatestImageFile(context, domain)
            if (specificFile.exists() && specificFile.length() > 0) {
                specificFile
            } else {
                getDomainLatestImageFile(context, domain)
            }
        } else {
            getDomainLatestImageFile(context, domain)
        }

        if (!file.exists() || file.length() == 0L) return null

        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val imageBitmap = bitmap.asImageBitmap()
            val isTargetExact = isSpecific && file.name == imageKey
            cacheMutex.withLock {
                if (cacheGeneration.get() == startGen) {
                    if (isTargetExact) {
                        memoryCache.put(cacheKey(domain, imageKey), imageBitmap)
                    }
                    if (!isSpecific || file.name == getDomainLatestImageFile(context, domain).name) {
                        memoryCache.put(fallbackCacheKey(domain), imageBitmap)
                    }
                }
            }
            imageBitmap
        } catch (_: Exception) {
            null
        }
    }

    @VisibleForTesting
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * Clears all cached OG image files on disk, invalidates in-memory bitmaps,
     * and increments generation token to discard in-flight downloads.
     */
    suspend fun clearImageCache(context: Context) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            cacheGeneration.incrementAndGet()
            memoryCache.evictAll()
            val dir = File(context.cacheDir, "og_images")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            domainRevisions.clear()
            _cacheClearedEvent.tryEmit(cacheGeneration.get())
        }
    }

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
     * Computes a deterministic, URL-distinct image key from a resolved image URL.
     * Preserves query parameters (?...) and removes fragment (#...).
     * Hashes the normalized string using SHA-256.
     */
    fun computeImageKey(resolvedImageUrl: String): String {
        val normalized = resolvedImageUrl.trim().substringBefore('#')
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            val hex = hash.joinToString("") { "%02x".format(it) }
            "$hex.webp"
        } catch (_: Exception) {
            "${normalized.hashCode().toUInt().toString(16)}.webp"
        }
    }

    /**
     * Extracts a clean filename from an image URL, stripping query parameters and hashes.
     * Maintained for backward compatibility; prefer computeImageKey for distinct identity.
     */
    fun extractFilename(imageUrl: String): String {
        return computeImageKey(imageUrl)
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
     * Downloads, compresses, and persists an og:image to cacheDir during Processing.
     * Skips download if the imageKey file already exists on disk.
     *
     * @return true if successfully saved or already exists; false if failed.
     */
    suspend fun processAndSaveOgImage(
        context: Context,
        pageUrl: String,
        ogImageUrl: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (ogImageUrl.isNullOrBlank()) return@withContext false

        val domain = extractDomain(pageUrl) ?: return@withContext false
        val resolvedUrl = resolveImageUrl(pageUrl, ogImageUrl)
        val imageKey = computeImageKey(resolvedUrl)

        val targetFile = getOgImageFile(context, imageKey)
        val latestFile = getDomainLatestImageFile(context, domain)

        // 1. Check if file already exists in cacheDir
        if (targetFile.exists() && targetFile.length() > 0) {
            runCatching {
                if (!latestFile.exists() || latestFile.lastModified() < targetFile.lastModified()) {
                    targetFile.copyTo(latestFile, overwrite = true)
                }
            }
            return@withContext true
        }

        val startGen = cacheGeneration.get()

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

        // Check generation before CPU-intensive compression
        if (cacheGeneration.get() != startGen) return@withContext false

        // 3. Downsample and compress to WebP under 50KB
        val compressed = compressToWebp(rawBytes) ?: return@withContext false

        // Check generation before disk write
        if (cacheGeneration.get() != startGen) return@withContext false

        // 4. Persist to cacheDir
        return@withContext cacheMutex.withLock {
            if (cacheGeneration.get() != startGen) return@withLock false
            try {
                getOgImageDir(context)
                targetFile.writeBytes(compressed.bytes)
                runCatching { latestFile.writeBytes(compressed.bytes) }

                val bitmap = BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size)
                val imageBitmap = bitmap?.asImageBitmap()
                if (imageBitmap != null) {
                    memoryCache.put(cacheKey(domain, imageKey), imageBitmap)
                    memoryCache.put(fallbackCacheKey(domain), imageBitmap)
                }
                notifyOgImageUpdated(domain)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
