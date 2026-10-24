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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manager for downloading, downsampling, compressing, and caching og:image assets in cacheDir.
 *
 * - Downloads og:image during import and on-demand when a grid card needs it.
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

    private val downloads = OgImageFetchCoordinator<ImageBitmap>()
    private val cacheGeneration = downloads.generation
    private val _cacheGenerationState = MutableStateFlow(0L)
    val cacheGenerationState: StateFlow<Long> = _cacheGenerationState.asStateFlow()
    private val _exactImageUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val emissionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @VisibleForTesting
    internal var imageDownloader: (suspend (String) -> ByteArray?)? = null
    private val cacheMutex = Mutex()
    private val imageEpochs = ConcurrentHashMap<String, AtomicLong>()

    /** Called after a transactional reference check; invalidate only this deleted image's work. */
    internal suspend fun deleteUnreferencedImage(context: Context, domain: String, imageKey: String, removeLatest: Boolean) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            imageEpochs.computeIfAbsent(imageKey) { AtomicLong(0) }.incrementAndGet()
            val exact = getOgImageFile(context, imageKey)
            val latest = getDomainLatestImageFile(context, domain)
            val matchesLatest = removeLatest && exact.isFile && latest.isFile &&
                exact.length() == latest.length() && exact.readBytes().contentEquals(latest.readBytes())
            if (matchesLatest && !latest.delete()) throw IOException("Could not remove RSS image copy")
            if (exact.exists() && !exact.delete()) throw IOException("Could not remove RSS image")
            memoryCache.snapshot().keys.filter { it.endsWith("/$imageKey") }.forEach { memoryCache.remove(it) }
            if (matchesLatest) memoryCache.remove(fallbackCacheKey(domain))
            notifyExactImageUpdated(domain, imageKey)
        }
    }

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

    /** Exact-image notifications update observers without invalidating sibling card requests. */
    fun observeExactImageUpdates(imageKey: String): Flow<String> =
        _exactImageUpdates.filter { it == imageKey }

    private fun notifyExactImageUpdated(domain: String, imageKey: String) {
        if (!_exactImageUpdates.tryEmit(imageKey)) {
            emissionScope.launch {
                _exactImageUpdates.emit(imageKey)
            }
        }
        notifyOgImageUpdated(domain)
    }

    @VisibleForTesting
    internal fun notifyExactImageUpdatedForTesting(domain: String, imageKey: String) {
        notifyExactImageUpdated(domain, imageKey)
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
            return memoryCache.get(cacheKey(domain, imageKey))
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
     * 2. Only when imageKey is not provided, looks up the latest image for the domain.
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
            CoroutineScope(Dispatchers.IO + SupervisorJob()).async(start = CoroutineStart.LAZY) {
                loadOgImageInternal(context, domain, imageKey, isSpecific, startGen)
            }
        }
        deferred.invokeOnCompletion { inFlightLoads.remove(flightKey, deferred) }
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
        val epoch = imageKey?.let { imageEpochs.computeIfAbsent(it) { AtomicLong(0) } }
        val startEpoch = epoch?.get()
        val domainRevision = getDomainRevision(domain)
        val file = if (isSpecific) {
            getOgImageFile(context, requireNotNull(imageKey))
        } else {
            getDomainLatestImageFile(context, domain)
        }

        if (!file.exists() || file.length() == 0L) return null

        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val imageBitmap = bitmap.asImageBitmap()
            val isTargetExact = isSpecific && file.name == imageKey
            cacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (cacheGeneration.get() != startGen || epoch?.get() != startEpoch ||
                    (!isSpecific && getDomainRevision(domain) != domainRevision)) return@withLock null
                if (isTargetExact) {
                    memoryCache.put(cacheKey(domain, imageKey), imageBitmap)
                }
                if (!isSpecific) {
                    memoryCache.put(fallbackCacheKey(domain), imageBitmap)
                }
                imageBitmap
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            downloads.clear()
            memoryCache.evictAll()
            val dir = File(context.cacheDir, "og_images")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            domainRevisions.clear()
            _cacheGenerationState.value = cacheGeneration.get()
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

    /** Loads only this bookmark's image, sharing missing-image work with imports and other cards. */
    suspend fun loadOrFetchOgImage(
        context: Context,
        pageUrl: String,
        domain: String,
        ogImageUrl: String?,
        network: me.bubblespark.tidilo.parser.ArticleNetwork? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (ogImageUrl.isNullOrBlank()) return@withContext null
        val startGen = cacheGeneration.get()
        val resolvedUrl = resolveImageUrl(pageUrl, ogImageUrl).substringBefore('#')
        val imageKey = computeImageKey(resolvedUrl)
        val epoch = imageEpochs.computeIfAbsent(imageKey) { AtomicLong(0) }
        val startEpoch = epoch.get()
        loadOgImage(context, domain, imageKey)?.let {
            return@withContext if (cacheGeneration.get() == startGen) it else null
        }
        val result = downloads.fetch("${network?.scopeKey ?: "manual"}|$imageKey", startGen) {
            // Another request may have populated the cache while this one waited for a permit.
            loadOgImage(context, domain, imageKey)?.let { return@fetch it }
            currentCoroutineContext().ensureActive()
            if (cacheGeneration.get() != startGen) return@fetch null
            val downloader = imageDownloader
            val raw = if (network != null) {
                val response = network.fetch(resolvedUrl)
                if (response.status !in 200..299) throw java.io.IOException("Image HTTP ${response.status}")
                response.bytes
            } else if (downloader != null) downloader(resolvedUrl) else downloadImage(resolvedUrl)
            if (raw == null || raw.size > MAX_DOWNLOAD_BYTES) throw PermanentOgImageFailure()
            currentCoroutineContext().ensureActive()
            if (cacheGeneration.get() != startGen) return@fetch null
            val compressed = compressToWebp(raw) ?: throw PermanentOgImageFailure()
            currentCoroutineContext().ensureActive()
            val bitmap = BitmapFactory.decodeByteArray(compressed.bytes, 0, compressed.bytes.size)
                ?.asImageBitmap() ?: throw PermanentOgImageFailure()
            cacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (cacheGeneration.get() != startGen || epoch.get() != startEpoch) return@withLock null
                writeAtomically(getOgImageFile(context, imageKey), compressed.bytes)
                // Legacy domain previews remain available only to callers explicitly requesting them.
                runCatching { writeAtomically(getDomainLatestImageFile(context, domain), compressed.bytes) }
                memoryCache.put(cacheKey(domain, imageKey), bitmap)
                memoryCache.put(fallbackCacheKey(domain), bitmap)
                notifyExactImageUpdated(domain, imageKey)
                bitmap
            }
        }
        cacheMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (cacheGeneration.get() != startGen || epoch.get() != startEpoch) return@withLock null
            // The same image URL can be shared by pages on different domains.
            if (result != null) memoryCache.put(cacheKey(domain, imageKey), result)
            result
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File.createTempFile("og-", ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    /** Disconnect on cancellation and await the blocking worker before releasing its semaphore permit. */
    private suspend fun downloadImage(resolvedUrl: String): ByteArray {
        try {
            return withTimeout(TIMEOUT_MS.toLong()) {
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val connection = AtomicReference<HttpURLConnection?>()
                        val worker = launch(Dispatchers.IO) {
                            try {
                                var url = URL(resolvedUrl)
                                var bytes: ByteArray? = null
                                for (redirect in 0..10) {
                                    if (url.protocol != "http" && url.protocol != "https") throw PermanentOgImageFailure()
                                    currentCoroutineContext().ensureActive()
                                    val http = url.openConnection() as HttpURLConnection
                                    connection.set(http)
                                    try {
                                        currentCoroutineContext().ensureActive()
                                        http.connectTimeout = TIMEOUT_MS
                                        http.readTimeout = TIMEOUT_MS
                                        http.instanceFollowRedirects = false
                                        http.setRequestProperty("User-Agent", USER_AGENT)
                                        val status = http.responseCode
                                        if (status in listOf(301, 302, 303, 307, 308)) {
                                            val location = http.getHeaderField("Location") ?: throw PermanentOgImageFailure()
                                            url = URL(url, location)
                                            continue
                                        }
                                        if (status !in 200..299) {
                                            if (status in 400..499 && status != 408 && status != 429) throw PermanentOgImageFailure()
                                            throw IOException("Image HTTP status $status")
                                        }
                                        if (http.contentLengthLong > MAX_DOWNLOAD_BYTES) throw PermanentOgImageFailure()
                                        bytes = http.inputStream.use { input ->
                                            val output = ByteArrayOutputStream()
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                if (output.size() + count > MAX_DOWNLOAD_BYTES) throw PermanentOgImageFailure()
                                                output.write(buffer, 0, count)
                                            }
                                            output.toByteArray()
                                        }
                                        break
                                    } finally {
                                        http.disconnect()
                                        connection.compareAndSet(http, null)
                                    }
                                }
                                continuation.resume(bytes ?: throw PermanentOgImageFailure())
                            } catch (failure: Exception) {
                                continuation.resumeWithException(failure)
                            }
                        }
                        continuation.invokeOnCancellation {
                            worker.cancel()
                            connection.get()?.disconnect()
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Image download timed out", timeout)
        }
    }

    /**
     * Downloads, compresses, and persists an og:image to cacheDir during Processing.
     * Shares on-demand recovery and validates existing files before treating them as cached.
     *
     * @return true if successfully saved or already exists; false if failed.
     */
    suspend fun processAndSaveOgImage(
        context: Context,
        pageUrl: String,
        ogImageUrl: String?,
        network: me.bubblespark.tidilo.parser.ArticleNetwork? = null
    ): Boolean {
        val domain = extractDomain(pageUrl) ?: return false
        return loadOrFetchOgImage(context, pageUrl, domain, ogImageUrl, network) != null
    }
}
