package me.bubblespark.tidilo.data.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.CoalescedFetchCoordinator
import me.bubblespark.tidilo.data.PermanentFetchFailure
import me.bubblespark.tidilo.parser.ArticleNetwork
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompressedOgImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/webp"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CompressedOgImage
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

internal interface OgImageCodec {
    fun decodeFile(file: File): ImageBitmap?
    fun decodeCompressedBytes(bytes: ByteArray): ImageBitmap?
    fun compressToWebp(rawBytes: ByteArray, maxDimension: Int, maxBytes: Int): CompressedOgImage?
    fun bitmapIdentity(bitmap: ImageBitmap): String
    fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color
    fun extractDetailHeroPalette(bitmap: ImageBitmap, domain: String): DetailHeroPalette
}

internal object AndroidOgImageCodec : OgImageCodec {
    override fun decodeFile(file: File): ImageBitmap? =
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()

    override fun decodeCompressedBytes(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    override fun compressToWebp(
        rawBytes: ByteArray,
        maxDimension: Int,
        maxBytes: Int
    ): CompressedOgImage? {
        if (rawBytes.isEmpty()) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false

            val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
                ?: return null

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
                if (scaled !== decodedBitmap) decodedBitmap.recycle()
                scaled
            } else {
                decodedBitmap
            }

            var quality = OgImageStore.DEFAULT_QUALITY
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

            if (compressedBytes.size >= maxBytes) return null

            CompressedOgImage(
                bytes = compressedBytes,
                width = finalWidth,
                height = finalHeight
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun bitmapIdentity(bitmap: ImageBitmap): String =
        bitmap.asAndroidBitmap().generationId.toString()

    override fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color =
        AndroidDomainIconCodec.extractDominantColor(bitmap.asAndroidBitmap(), domain)

    override fun extractDetailHeroPalette(bitmap: ImageBitmap, domain: String): DetailHeroPalette =
        extractDetailHeroPalette(bitmap.asAndroidBitmap(), domain)

    fun extractDetailHeroPalette(bitmap: Bitmap, domain: String): DetailHeroPalette {
        return try {
            val sampleSize = 32
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            MediaColorPalette.extractDetailHeroPaletteFromPixels(pixels, sampleSize, sampleSize, domain)
        } catch (_: Exception) {
            val fallback = MediaColorPalette.generateDomainAccentColor(domain)
            DetailHeroPalette(fallback, fallback, fallback)
        }
    }

    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
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
}

/**
 * File-rooted OG preview image store managing WebP disk persistence, bounded memory caching,
 * invalidation-safe accent/edge palette extraction, and coalesced network recovery.
 */
internal class OgImageStore(
    private val layout: MediaCacheLayout,
    private val revisionBus: MediaCacheRevisionBus,
    private val coordinator: CoalescedFetchCoordinator<ImageBitmap> =
        CoalescedFetchCoordinator(generation = revisionBus.generation),
    private val imageDownloader: (suspend (String) -> ByteArray?)? = null,
    private val compatImageDownloaderProvider: (() -> (suspend (String) -> ByteArray?)?)? = null,
    private val codec: OgImageCodec = AndroidOgImageCodec,
    private val loadScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val ogDominantColorCache = LruCache<String, Color>(128)
    private val ogHeroPaletteCache = LruCache<String, DetailHeroPalette>(128)
    private val dominantColorLock = Any()

    private class ColorSampling(val identity: String, var invalidated: Boolean = false)

    private val activeColorSampling = mutableSetOf<ColorSampling>()
    private val cacheMutex = Mutex()
    private val imageEpochs = ConcurrentHashMap<String, AtomicLong>()
    private val inFlightLoads = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    private fun bitmapCacheIdentity(domain: String, imageKey: String?, bitmap: ImageBitmap): String {
        val source = if (imageKey.isNullOrBlank()) {
            MediaCacheLayout.ogFallbackCacheKey(domain)
        } else {
            MediaCacheLayout.ogCacheKey(domain, imageKey)
        }
        return "$source|${codec.bitmapIdentity(bitmap)}"
    }

    suspend fun getDominantColor(domain: String, imageKey: String?, bitmap: ImageBitmap): Color? =
        resolveDominantColor(bitmapCacheIdentity(domain, imageKey, bitmap)) {
            codec.extractDominantColor(bitmap, domain)
        }

    suspend fun getDetailHeroPalette(
        domain: String,
        imageKey: String?,
        bitmap: ImageBitmap
    ): DetailHeroPalette? =
        resolveHeroPalette(bitmapCacheIdentity(domain, imageKey, bitmap)) {
            codec.extractDetailHeroPalette(bitmap, domain)
        }

    suspend fun resolveHeroPalette(
        identity: String,
        extract: suspend () -> DetailHeroPalette
    ): DetailHeroPalette? =
        resolveSampledValue(identity, ogHeroPaletteCache, extract) { id, palette ->
            ogHeroPaletteCache.put(id, palette)
            ogDominantColorCache.put(id, palette.accentColor)
        }

    suspend fun resolveDominantColor(identity: String, extract: suspend () -> Color): Color? =
        resolveSampledValue(identity, ogDominantColorCache, extract) { id, color ->
            ogDominantColorCache.put(id, color)
        }

    private suspend fun <T : Any> resolveSampledValue(
        identity: String,
        cache: LruCache<String, T>,
        extract: suspend () -> T,
        store: (String, T) -> Unit
    ): T? = withContext(Dispatchers.Default) {
        val sampling = synchronized(dominantColorLock) {
            cache.get(identity)?.let { return@withContext it }
            ColorSampling(identity).also { activeColorSampling.add(it) }
        }
        try {
            val value = extract()
            val workContext = currentCoroutineContext()
            synchronized(dominantColorLock) {
                workContext.ensureActive()
                if (sampling.invalidated) null else value.also { store(identity, it) }
            }
        } finally {
            synchronized(dominantColorLock) { activeColorSampling.remove(sampling) }
        }
    }

    fun invalidateDominantColors(source: String? = null) = synchronized(dominantColorLock) {
        activeColorSampling
            .filter { source == null || it.identity.substringBeforeLast('|') == source }
            .forEach { it.invalidated = true }
        if (source == null) {
            ogDominantColorCache.evictAll()
            ogHeroPaletteCache.evictAll()
        } else {
            fun <T : Any> LruCache<String, T>.removeBySource(targetSource: String) {
                snapshot().keys
                    .filter { it.substringBeforeLast('|') == targetSource }
                    .forEach { remove(it) }
            }
            ogDominantColorCache.removeBySource(source)
            ogHeroPaletteCache.removeBySource(source)
        }
    }

    fun getCachedOgImageBitmap(domain: String, imageKey: String? = null): ImageBitmap? {
        if (!imageKey.isNullOrBlank()) {
            return memoryCache.get(MediaCacheLayout.ogCacheKey(domain, imageKey))
        }
        return memoryCache.get(MediaCacheLayout.ogFallbackCacheKey(domain))
    }

    suspend fun loadOgImage(
        domain: String,
        imageKey: String? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val isSpecific = !imageKey.isNullOrBlank()
        val key = if (isSpecific) {
            MediaCacheLayout.ogCacheKey(domain, requireNotNull(imageKey))
        } else {
            MediaCacheLayout.ogFallbackCacheKey(domain)
        }

        memoryCache.get(key)?.let { return@withContext it }

        val startGen = revisionBus.getCurrentGeneration()
        val flightKey = "$startGen|$key"
        val deferred = inFlightLoads.computeIfAbsent(flightKey) {
            loadScope.async(start = CoroutineStart.LAZY) {
                loadOgImageInternal(domain, imageKey, isSpecific, startGen)
            }
        }
        deferred.invokeOnCompletion { inFlightLoads.remove(flightKey, deferred) }
        deferred.await()
    }

    private suspend fun loadOgImageInternal(
        domain: String,
        imageKey: String?,
        isSpecific: Boolean,
        startGen: Long
    ): ImageBitmap? {
        val epoch = imageKey?.let { imageEpochs.computeIfAbsent(it) { AtomicLong(0) } }
        val startEpoch = epoch?.get()
        val domainRevision = revisionBus.getOgDomainRevision(domain)
        val file = if (isSpecific) {
            layout.ogImageFile(requireNotNull(imageKey))
        } else {
            layout.domainLatestOgImageFile(domain)
        }

        if (!file.exists() || file.length() == 0L) return null

        return try {
            val imageBitmap = codec.decodeFile(file) ?: return null
            val isTargetExact = isSpecific && file.name == imageKey
            cacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (revisionBus.getCurrentGeneration() != startGen ||
                    epoch?.get() != startEpoch ||
                    (!isSpecific && revisionBus.getOgDomainRevision(domain) != domainRevision)
                ) {
                    return@withLock null
                }
                if (isTargetExact) {
                    memoryCache.put(MediaCacheLayout.ogCacheKey(domain, requireNotNull(imageKey)), imageBitmap)
                }
                if (!isSpecific) {
                    memoryCache.put(MediaCacheLayout.ogFallbackCacheKey(domain), imageBitmap)
                }
                imageBitmap
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadOrFetchOgImage(
        pageUrl: String,
        domain: String,
        ogImageUrl: String?,
        network: ArticleNetwork? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (ogImageUrl.isNullOrBlank()) return@withContext null
        val startGen = revisionBus.getCurrentGeneration()
        val resolvedUrl = MediaCacheLayout.resolveImageUrl(pageUrl, ogImageUrl).substringBefore('#')
        val imageKey = MediaCacheLayout.computeImageKey(resolvedUrl)
        val epoch = imageEpochs.computeIfAbsent(imageKey) { AtomicLong(0) }
        val startEpoch = epoch.get()
        loadOgImage(domain, imageKey)?.let {
            return@withContext if (revisionBus.getCurrentGeneration() == startGen) it else null
        }
        val result = coordinator.fetch("${network?.scopeKey ?: "manual"}|$imageKey", startGen) {
            loadOgImage(domain, imageKey)?.let { return@fetch it }
            currentCoroutineContext().ensureActive()
            if (revisionBus.getCurrentGeneration() != startGen) return@fetch null
            val downloader = compatImageDownloaderProvider?.invoke() ?: imageDownloader
            val raw = if (network != null) {
                val response = network.fetch(resolvedUrl)
                if (response.status !in 200..299) throw IOException("Image HTTP ${response.status}")
                response.bytes
            } else if (downloader != null) {
                downloader(resolvedUrl)
            } else {
                downloadImage(resolvedUrl)
            }
            if (raw == null || raw.size > MAX_DOWNLOAD_BYTES) throw PermanentFetchFailure()
            currentCoroutineContext().ensureActive()
            if (revisionBus.getCurrentGeneration() != startGen) return@fetch null
            val compressed = codec.compressToWebp(raw, MAX_IMAGE_DIMENSION, MAX_FILE_SIZE_BYTES)
                ?: throw PermanentFetchFailure()
            currentCoroutineContext().ensureActive()
            val bitmap = codec.decodeCompressedBytes(compressed.bytes) ?: throw PermanentFetchFailure()
            cacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (revisionBus.getCurrentGeneration() != startGen || epoch.get() != startEpoch) {
                    return@withLock null
                }
                layout.writeAtomically("og-", layout.ogImageFile(imageKey), compressed.bytes)
                runCatching {
                    layout.writeAtomically("og-", layout.domainLatestOgImageFile(domain), compressed.bytes)
                }
                invalidateDominantColors(MediaCacheLayout.ogCacheKey(domain, imageKey))
                invalidateDominantColors(MediaCacheLayout.ogFallbackCacheKey(domain))
                memoryCache.put(MediaCacheLayout.ogCacheKey(domain, imageKey), bitmap)
                memoryCache.put(MediaCacheLayout.ogFallbackCacheKey(domain), bitmap)
                revisionBus.notifyExactImageUpdated(domain, imageKey)
                bitmap
            }
        }
        cacheMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (revisionBus.getCurrentGeneration() != startGen || epoch.get() != startEpoch) {
                return@withLock null
            }
            if (result != null) {
                memoryCache.put(MediaCacheLayout.ogCacheKey(domain, imageKey), result)
            }
            result
        }
    }

    suspend fun processAndSaveOgImage(
        pageUrl: String,
        ogImageUrl: String?,
        network: ArticleNetwork? = null
    ): Boolean {
        val domain = MediaCacheLayout.extractDomain(pageUrl) ?: return false
        return loadOrFetchOgImage(pageUrl, domain, ogImageUrl, network) != null
    }

    suspend fun evictExpiredImage(
        filename: String,
        expectedModifiedAt: Long,
        domains: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            val dir = layout.ogImagesDir()
            val file = File(dir, filename)
            if (file.parentFile != dir ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                file.lastModified() != expectedModifiedAt
            ) {
                return@withLock false
            }
            val fallback = layout.isDomainLatestOgImageFileName(filename)
            val affectedDomains = if (fallback) {
                domains + layout.extractDomainFromLatestFileName(filename)
            } else {
                domains
            }
            if (!fallback) imageEpochs.computeIfAbsent(filename) { AtomicLong(0) }.incrementAndGet()
            if (!file.delete()) throw IOException("Could not remove expired OG image")
            if (fallback) {
                for (domain in affectedDomains) {
                    val fallbackKey = MediaCacheLayout.ogFallbackCacheKey(domain)
                    memoryCache.remove(fallbackKey)
                    invalidateDominantColors(fallbackKey)
                    revisionBus.notifyOgImageUpdated(domain)
                }
            } else {
                memoryCache.snapshot().keys.filter { it.endsWith("/$filename") }.forEach {
                    memoryCache.remove(it)
                    invalidateDominantColors(it)
                }
                for (domain in affectedDomains.ifEmpty { setOf("") }) {
                    revisionBus.notifyExactImageUpdated(domain, filename)
                }
            }
            true
        }
    }

    suspend fun deleteUnreferencedImage(domain: String, imageKey: String, removeLatest: Boolean) =
        deleteUnreferencedImage(setOf(domain), imageKey, if (removeLatest) setOf(domain) else emptySet())

    suspend fun deleteUnreferencedImage(
        domains: Set<String>,
        imageKey: String,
        removeLatestDomains: Set<String>
    ) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            imageEpochs.computeIfAbsent(imageKey) { AtomicLong(0) }.incrementAndGet()
            val exact = layout.ogImageFile(imageKey)
            for (domain in removeLatestDomains) {
                val latest = layout.domainLatestOgImageFile(domain)
                val matchesLatest = exact.isFile && latest.isFile &&
                    exact.length() == latest.length() && exact.readBytes().contentEquals(latest.readBytes())
                if (matchesLatest) {
                    if (!latest.delete()) throw IOException("Could not remove RSS image copy")
                    val fallbackKey = MediaCacheLayout.ogFallbackCacheKey(domain)
                    invalidateDominantColors(fallbackKey)
                    memoryCache.remove(fallbackKey)
                    revisionBus.notifyOgImageUpdated(domain)
                }
            }
            if (exact.exists() && !exact.delete()) throw IOException("Could not remove RSS image")
            memoryCache.snapshot().keys.filter { it.endsWith("/$imageKey") }.forEach {
                memoryCache.remove(it)
                invalidateDominantColors(it)
            }
            for (domain in domains) {
                invalidateDominantColors(MediaCacheLayout.ogCacheKey(domain, imageKey))
                revisionBus.notifyExactImageUpdated(domain, imageKey)
            }
        }
    }

    fun clearMemoryCache() {
        memoryCache.evictAll()
        invalidateDominantColors()
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            coordinator.clear()
            memoryCache.evictAll()
            invalidateDominantColors()
            val dir = layout.ogImagesDir(createIfMissing = false)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            revisionBus.onOgImagesCleared()
        }
    }

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
                                    if (url.protocol != "http" && url.protocol != "https") {
                                        throw PermanentFetchFailure()
                                    }
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
                                            val location = http.getHeaderField("Location")
                                                ?: throw PermanentFetchFailure()
                                            url = URL(url, location)
                                            continue
                                        }
                                        if (status !in 200..299) {
                                            if (status in 400..499 && status != 408 && status != 429) {
                                                throw PermanentFetchFailure()
                                            }
                                            throw IOException("Image HTTP status $status")
                                        }
                                        if (http.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                                            throw PermanentFetchFailure()
                                        }
                                        bytes = http.inputStream.use { input ->
                                            val output = ByteArrayOutputStream()
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                if (output.size() + count > MAX_DOWNLOAD_BYTES) {
                                                    throw PermanentFetchFailure()
                                                }
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
                                continuation.resume(bytes ?: throw PermanentFetchFailure())
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

    companion object {
        const val MAX_IMAGE_DIMENSION = 640
        const val MAX_FILE_SIZE_BYTES = 50 * 1024
        const val DEFAULT_QUALITY = 75
        private const val MAX_CACHE_SIZE_BYTES = 16 * 1024 * 1024
        private const val TIMEOUT_MS = 10_000
        private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
