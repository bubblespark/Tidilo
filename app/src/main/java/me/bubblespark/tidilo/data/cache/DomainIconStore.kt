package me.bubblespark.tidilo.data.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.CoalescedFetchCoordinator
import me.bubblespark.tidilo.data.DomainIconCandidate
import me.bubblespark.tidilo.data.DomainIconResolver
import me.bubblespark.tidilo.data.ResolvedDomainIcon
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.parser.ArticleNetwork
import me.bubblespark.tidilo.parser.RssFeedParser
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

internal data class IconDownloadResult(
    val bytes: ByteArray?,
    val hasServerContact: Boolean,
    val hasTransientFailure: Boolean = !hasServerContact
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IconDownloadResult
        if (!bytes.contentEquals(other.bytes)) return false
        if (hasServerContact != other.hasServerContact) return false
        if (hasTransientFailure != other.hasTransientFailure) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bytes?.contentHashCode() ?: 0
        result = 31 * result + hasServerContact.hashCode()
        result = 31 * result + hasTransientFailure.hashCode()
        return result
    }
}

internal data class IconHtmlDiscoveryResult(
    val iconUrls: List<String>,
    val hasServerContact: Boolean,
    val hasTransientFailure: Boolean = !hasServerContact,
    val highPriorityUrls: Set<String> = emptySet(),
    val discoveredSiteName: String? = null
)

data class IconFetchResult(
    val success: Boolean,
    val hadTransientFailure: Boolean = false,
    val discoveredSiteName: String? = null
)

internal interface DecodedIconData {
    val width: Int
    val height: Int
    fun recycle()
}

internal class EncodedIconAsset(
    val pngBytes: ByteArray,
    val imageBitmap: ImageBitmap,
    val dominantColor: Color,
    val discard: () -> Unit
)

internal interface DomainIconCodec {
    fun decodeFile(file: File, domain: String): Pair<ImageBitmap, Color>?
    fun decodeBytesForResolve(bytes: ByteArray, minSize: Int, maxSize: Int): ResolvedDomainIcon<DecodedIconData>?
    fun encodeAndExtract(domain: String, decoded: DecodedIconData, maxSize: Int): EncodedIconAsset
    fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color
}

internal object AndroidDomainIconCodec : DomainIconCodec {
    private class AndroidDecodedIcon(val bitmap: Bitmap) : DecodedIconData {
        var retainedByOutput: Boolean = false
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height
        override fun recycle() {
            if (!retainedByOutput && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun decodeFile(file: File, domain: String): Pair<ImageBitmap, Color>? {
        val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val imageBitmap = rawBitmap.asImageBitmap()
        val color = extractDominantColor(rawBitmap, domain)
        return imageBitmap to color
    }

    override fun decodeBytesForResolve(
        bytes: ByteArray,
        minSize: Int,
        maxSize: Int
    ): ResolvedDomainIcon<DecodedIconData>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width < minSize || height < minSize) return null
        var sample = 1
        while (width / sample > maxSize * 2 || height / sample > maxSize * 2) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return ResolvedDomainIcon(AndroidDecodedIcon(decoded), width, height)
    }

    override fun encodeAndExtract(
        domain: String,
        decoded: DecodedIconData,
        maxSize: Int
    ): EncodedIconAsset {
        val androidDecoded = decoded as AndroidDecodedIcon
        val selected = androidDecoded.bitmap
        var scaled: Bitmap? = null
        var processed: Bitmap? = null
        try {
            scaled = downsampleIfNeeded(selected, maxSize)
            processed = makeBackgroundTransparent(scaled)
            val output = ByteArrayOutputStream()
            if (!processed.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Icon encoding failed")
            }
            val color = extractDominantColor(processed, domain)
            val imageBitmap = processed.asImageBitmap()
            if (processed === selected) {
                androidDecoded.retainedByOutput = true
            }
            val retainedProcessed = processed
            val asset = EncodedIconAsset(
                pngBytes = output.toByteArray(),
                imageBitmap = imageBitmap,
                dominantColor = color,
                discard = {
                    if (retainedProcessed === selected) {
                        androidDecoded.retainedByOutput = false
                        androidDecoded.recycle()
                    } else if (!retainedProcessed.isRecycled) {
                        retainedProcessed.recycle()
                    }
                }
            )
            if (scaled !== selected && scaled !== processed && !scaled.isRecycled) {
                scaled.recycle()
            }
            return asset
        } catch (t: Throwable) {
            if (scaled != null && scaled !== selected && scaled !== processed && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (processed != null && processed !== selected && !processed.isRecycled) {
                processed.recycle()
            }
            throw t
        }
    }

    override fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color =
        extractDominantColor(bitmap.asAndroidBitmap(), domain)

    fun extractDominantColor(bitmap: Bitmap, domain: String): Color {
        return try {
            val sampleSize = 24
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            MediaColorPalette.extractDominantColorFromPixels(pixels, domain)
        } catch (_: Exception) {
            MediaColorPalette.generateDomainAccentColor(domain)
        }
    }

    fun makeBackgroundTransparent(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 1 || h <= 1) return source

        return try {
            val cTL = source.getPixel(0, 0)
            val cTR = source.getPixel(w - 1, 0)
            val cBL = source.getPixel(0, h - 1)
            val cBR = source.getPixel(w - 1, h - 1)

            val aTL = android.graphics.Color.alpha(cTL)
            val aTR = android.graphics.Color.alpha(cTR)
            val aBL = android.graphics.Color.alpha(cBL)
            val aBR = android.graphics.Color.alpha(cBR)

            if (aTL < 30 && aTR < 30 && aBL < 30 && aBR < 30) {
                return source
            }

            fun isSimilar(c1: Int, c2: Int, maxDiff: Int = 18): Boolean {
                val rDiff = kotlin.math.abs(android.graphics.Color.red(c1) - android.graphics.Color.red(c2))
                val gDiff = kotlin.math.abs(android.graphics.Color.green(c1) - android.graphics.Color.green(c2))
                val bDiff = kotlin.math.abs(android.graphics.Color.blue(c1) - android.graphics.Color.blue(c2))
                return rDiff <= maxDiff && gDiff <= maxDiff && bDiff <= maxDiff
            }

            val allOpaque = aTL > 220 && aTR > 220 && aBL > 220 && aBR > 220
            if (!allOpaque || !isSimilar(cTL, cTR) || !isSimilar(cTL, cBL) || !isSimilar(cTL, cBR)) {
                return source
            }

            val bgR = android.graphics.Color.red(cTL)
            val bgG = android.graphics.Color.green(cTL)
            val bgB = android.graphics.Color.blue(cTL)

            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)

            val threshold = 22.0
            val smooth = 25.0

            for (i in pixels.indices) {
                val p = pixels[i]
                val r = android.graphics.Color.red(p)
                val g = android.graphics.Color.green(p)
                val b = android.graphics.Color.blue(p)
                val a = android.graphics.Color.alpha(p)

                val dist = kotlin.math.sqrt(
                    ((r - bgR) * (r - bgR) + (g - bgG) * (g - bgG) + (b - bgB) * (b - bgB)).toDouble()
                )

                if (dist < threshold) {
                    pixels[i] = 0
                } else if (dist < threshold + smooth) {
                    val factor = ((dist - threshold) / smooth).toFloat()
                    val newAlpha = (a * factor).toInt().coerceIn(0, 255)
                    pixels[i] = android.graphics.Color.argb(newAlpha, r, g, b)
                }
            }

            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            output.setPixels(pixels, 0, w, 0, 0, w, h)
            output
        } catch (_: Exception) {
            source
        }
    }

    fun downsampleIfNeeded(bitmap: Bitmap, maxSize: Int = DomainIconStore.MAX_ICON_SIZE): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap

        val ratio = kotlin.math.min(maxSize.toFloat() / w, maxSize.toFloat() / h)
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}

/**
 * File-rooted domain & feed icon store handling icon discovery, corner-transparency processing,
 * PNG caching, negative markers, legacy migration, and decoupled `og:site_name` reporting.
 */
internal class DomainIconStore(
    private val layout: MediaCacheLayout,
    private val revisionBus: MediaCacheRevisionBus,
    private val coordinator: CoalescedFetchCoordinator<IconFetchResult> =
        CoalescedFetchCoordinator(generation = revisionBus.generation),
    private val httpDownloader: ((String) -> IconDownloadResult)? = null,
    private val htmlDiscoverer: ((String) -> IconHtmlDiscoveryResult)? = null,
    private val compatHttpDownloaderProvider: (() -> ((String) -> IconDownloadResult)?)? = null,
    private val compatHtmlDiscovererProvider: (() -> ((String) -> IconHtmlDiscoveryResult)?)? = null,
    private val onSiteDisplayNameDiscovered: ((String, String) -> Unit)? = null,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val codec: DomainIconCodec = AndroidDomainIconCodec
) {
    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val dominantColorCache = LruCache<String, Color>(256)
    private val cacheLock = Any()

    fun hasIcon(domain: String): Boolean =
        getCachedIconBitmap(domain) != null || loadIconFromDisk(domain) != null

    fun shouldSkipFetch(domain: String): Boolean = synchronized(cacheLock) {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        val marker = layout.negativeMarkerFile(normalized)
        if (!marker.exists()) return@synchronized false
        val age = wallClockMillis() - marker.lastModified()
        if (age in 0 until NEGATIVE_CACHE_TTL_MS) {
            true
        } else {
            marker.delete()
            false
        }
    }

    fun writeNegativeMarker(domain: String) {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        val marker = layout.negativeMarkerFile(normalized)
        synchronized(cacheLock) {
            try {
                if (!marker.exists()) {
                    marker.createNewFile()
                } else {
                    marker.setLastModified(wallClockMillis())
                }
            } catch (_: Exception) {
            }
        }
    }

    fun removeNegativeMarker(domain: String) {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        val marker = layout.negativeMarkerFile(normalized)
        synchronized(cacheLock) {
            if (marker.exists()) {
                marker.delete()
            }
        }
    }

    fun getCachedIconBitmap(domain: String): ImageBitmap? =
        memoryCache.get(MediaCacheLayout.normalizeDomain(domain))

    fun loadIconFromDisk(domain: String): ImageBitmap? {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        return synchronized(cacheLock) load@{
            memoryCache.get(normalized)?.let { return@load it }
            val file = layout.domainIconFile(normalized)
            val legacy = layout.legacyDomainIconFile(normalized)
            val targetFile = if (!file.exists() || file.length() == 0L) {
                if (legacy != null && legacy.exists() && legacy.length() > 0L) {
                    val copied = runCatching {
                        legacy.copyTo(file, overwrite = true)
                        legacy.delete()
                        true
                    }.getOrDefault(false)
                    if (copied && file.exists() && file.length() > 0L) file else legacy
                } else {
                    return@load null
                }
            } else {
                file
            }

            try {
                val (imageBitmap, color) = codec.decodeFile(targetFile, normalized) ?: return@load null
                memoryCache.put(normalized, imageBitmap)
                dominantColorCache.put(normalized, color)
                revisionBus.notifyIconUpdated(normalized)
                imageBitmap
            } catch (_: Exception) {
                null
            }
        }
    }

    fun getIconBitmap(domain: String): ImageBitmap? =
        getCachedIconBitmap(domain) ?: loadIconFromDisk(domain)

    fun preloadIcons(domains: Collection<String?>) {
        val unique = domains.filterNotNull().map(MediaCacheLayout::normalizeDomain).distinct()
        for (domain in unique) {
            if (memoryCache.get(domain) == null && hasIcon(domain)) {
                loadIconFromDisk(domain)
            }
        }
    }

    fun getDominantColor(domain: String): Color {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        dominantColorCache.get(normalized)?.let { return it }

        val imageBitmap = getCachedIconBitmap(normalized) ?: loadIconFromDisk(normalized)
        val color = if (imageBitmap != null) {
            codec.extractDominantColor(imageBitmap, normalized)
        } else {
            MediaColorPalette.generateDomainAccentColor(normalized)
        }

        dominantColorCache.put(normalized, color)
        return color
    }

    fun getCachedDominantColor(domain: String): Color? =
        dominantColorCache.get(MediaCacheLayout.normalizeDomain(domain))

    suspend fun loadOrFetchFeedIcon(feed: RssFeedEntity): ImageBitmap? = withContext(Dispatchers.IO) {
        val generation = revisionBus.getCurrentGeneration()
        val key = MediaCacheLayout.feedIconKey(feed)
        val direct = feed.iconUrl?.let { RssFeedParser.httpUrl(feed.url, it) }
        if (direct != null) {
            (getCachedIconBitmap(key) ?: loadIconFromDisk(key))?.let { return@withContext it }
            if (!shouldSkipFetch(key)) coordinator.fetch("feed|$key", generation) {
                if (generation != revisionBus.getCurrentGeneration() || shouldSkipFetch(key)) {
                    return@fetch IconFetchResult(false)
                }
                if (getCachedIconBitmap(key) != null || loadIconFromDisk(key) != null) {
                    return@fetch IconFetchResult(true)
                }
                fetchIconInternal(key, direct, null, null, generation, null, null, directOnly = true)
            }
            if (generation != revisionBus.getCurrentGeneration()) return@withContext null
            getCachedIconBitmap(key)?.let { return@withContext it }
        }
        if (generation != revisionBus.getCurrentGeneration()) return@withContext null
        val bitmap = MediaCacheLayout.feedPublisherDomain(feed)?.let { loadOrFetchIcon(it) }
        if (generation == revisionBus.getCurrentGeneration()) bitmap else null
    }

    suspend fun loadOrFetchIcon(domain: String, pageUrl: String? = null): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val normalized = MediaCacheLayout.normalizeDomain(domain)
            if (MediaCacheLayout.extractDomain("https://$normalized") != normalized) return@withContext null
            val generation = revisionBus.getCurrentGeneration()
            (getCachedIconBitmap(normalized) ?: loadIconFromDisk(normalized))?.let { return@withContext it }
            val page = pageUrl?.takeIf { MediaCacheLayout.extractDomain(it) == normalized } ?: "https://$normalized"
            fetchAndSaveDomainIcon(page)
            synchronized(cacheLock) {
                if (generation != revisionBus.getCurrentGeneration()) null else getCachedIconBitmap(normalized)
            }
        }

    suspend fun fetchAndSaveDomainIcon(
        url: String,
        appleTouchIconUrl: String? = null,
        faviconUrl: String? = null,
        knownCandidates: List<DomainIconCandidate>? = null,
        network: ArticleNetwork? = null
    ): IconFetchResult = withContext(Dispatchers.IO) {
        val domain = MediaCacheLayout.extractDomain(url) ?: return@withContext IconFetchResult(false)
        val generation = revisionBus.getCurrentGeneration()
        if (getCachedIconBitmap(domain) != null || loadIconFromDisk(domain) != null) {
            return@withContext IconFetchResult(true)
        }
        if (shouldSkipFetch(domain)) return@withContext IconFetchResult(false)
        coordinator.fetch("${network?.scopeKey ?: "manual"}|$domain", generation) {
            if (getCachedIconBitmap(domain) != null || loadIconFromDisk(domain) != null) {
                return@fetch IconFetchResult(true)
            }
            if (shouldSkipFetch(domain) || generation != revisionBus.getCurrentGeneration()) {
                return@fetch IconFetchResult(false)
            }
            val page = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$domain"
            fetchIconInternal(domain, page, appleTouchIconUrl, faviconUrl, generation, knownCandidates, network)
        } ?: IconFetchResult(false, generation == revisionBus.getCurrentGeneration())
    }

    private suspend fun fetchIconInternal(
        domain: String,
        page: String,
        apple: String?,
        favicon: String?,
        generation: Long,
        knownCandidates: List<DomainIconCandidate>?,
        network: ArticleNetwork?,
        directOnly: Boolean = false
    ): IconFetchResult {
        var contacted = false
        var transientFailure = false
        var discoveredSiteName: String? = null
        val download: suspend (String) -> ResolvedDomainIcon<DecodedIconData>? = { candidate ->
            val result = if (network == null) {
                runInterruptible(Dispatchers.IO) { downloadBytes(candidate) }
            } else {
                val response = network.fetch(candidate)
                IconDownloadResult(
                    bytes = response.bytes.takeIf { response.status in 200..299 },
                    hasServerContact = true,
                    hasTransientFailure = isTransientHttpStatus(response.status)
                )
            }
            contacted = contacted || result.hasServerContact
            transientFailure = transientFailure || result.hasTransientFailure
            val bytes = result.bytes
            if (bytes == null || bytes.size > MAX_DOWNLOAD_BYTES) {
                null
            } else {
                val minimum = if (directOnly) 1 else MIN_ICON_SIZE
                codec.decodeBytesForResolve(bytes, minimum, MAX_ICON_SIZE)
            }
        }
        val selected = if (directOnly) {
            download(page)?.value
        } else {
            DomainIconResolver.resolve(
                pageUrl = page,
                domain = domain,
                appleUrl = apple,
                faviconUrl = favicon,
                discover = { source ->
                    val discovery = if (network == null) {
                        runInterruptible(Dispatchers.IO) { findHtmlIconUrls(source) }
                    } else {
                        val response = network.fetch(source)
                        if (response.status in 200..299) {
                            val doc = Jsoup.parse(ByteArrayInputStream(response.bytes), null, response.url)
                            val candidates = DomainIconResolver.parseHtml(doc, response.url)
                            IconHtmlDiscoveryResult(
                                iconUrls = candidates.map { it.url },
                                hasServerContact = true,
                                hasTransientFailure = isTransientHttpStatus(response.status),
                                highPriorityUrls = candidates.filter { it.highPriority }.map { it.url }.toSet()
                            )
                        } else {
                            IconHtmlDiscoveryResult(
                                iconUrls = emptyList(),
                                hasServerContact = true,
                                hasTransientFailure = isTransientHttpStatus(response.status)
                            )
                        }
                    }
                    contacted = contacted || discovery.hasServerContact
                    transientFailure = transientFailure || discovery.hasTransientFailure
                    val siteName = discovery.discoveredSiteName?.trim()?.takeIf { it.isNotBlank() }
                    if (siteName != null && generation == revisionBus.getCurrentGeneration()) {
                        discoveredSiteName = siteName
                        MediaCacheLayout.extractDomain(source)?.let { sourceDomain ->
                            onSiteDisplayNameDiscovered?.invoke(sourceDomain, siteName)
                        }
                    }
                    discovery.iconUrls.map { DomainIconCandidate(it, it in discovery.highPriorityUrls) }
                },
                download = download,
                discard = { it.recycle() },
                knownCandidates = knownCandidates
            )
        }
        currentCoroutineContext().ensureActive()
        if (generation != revisionBus.getCurrentGeneration()) {
            selected?.recycle()
            return IconFetchResult(false, discoveredSiteName = discoveredSiteName)
        }
        if (selected == null) {
            val workContext = currentCoroutineContext()
            synchronized(cacheLock) {
                workContext.ensureActive()
                if (generation == revisionBus.getCurrentGeneration() && contacted && !transientFailure) {
                    writeNegativeMarker(domain)
                }
            }
            if (transientFailure || !contacted) throw IOException("Transient domain icon failure")
            return IconFetchResult(false, discoveredSiteName = discoveredSiteName)
        }
        var encoded: EncodedIconAsset? = null
        var published = false
        try {
            encoded = codec.encodeAndExtract(domain, selected, MAX_ICON_SIZE)
            val workContext = currentCoroutineContext()
            return synchronized(cacheLock) {
                workContext.ensureActive()
                if (generation != revisionBus.getCurrentGeneration()) {
                    return@synchronized IconFetchResult(false, discoveredSiteName = discoveredSiteName)
                }
                layout.writeAtomically("icon-", layout.domainIconFile(domain), encoded.pngBytes)
                removeNegativeMarker(domain)
                memoryCache.put(domain, encoded.imageBitmap)
                dominantColorCache.put(domain, encoded.dominantColor)
                published = true
                revisionBus.notifyIconUpdated(domain)
                IconFetchResult(true, discoveredSiteName = discoveredSiteName)
            }
        } finally {
            if (!published) {
                encoded?.discard?.invoke()
            }
            selected.recycle()
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            coordinator.clear()
            memoryCache.evictAll()
            dominantColorCache.evictAll()
            val dir = layout.domainIconsDir(createIfMissing = false)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            val legacy = layout.legacyDomainIconsDir()
            if (legacy != null && legacy.exists()) {
                legacy.deleteRecursively()
            }
            revisionBus.onDomainIconsCleared()
        }
    }

    private fun extractSiteName(doc: org.jsoup.nodes.Document): String? =
        doc.select("meta[property='og:site_name'], meta[name='og:site_name']")
            .firstOrNull()
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun findHtmlIconUrls(pageUrl: String): IconHtmlDiscoveryResult {
        (compatHtmlDiscovererProvider?.invoke() ?: htmlDiscoverer)?.let { return it.invoke(pageUrl) }
        var results = emptyList<DomainIconCandidate>()
        var hasServerContact = false
        var hasTransientFailure = false
        var siteName: String? = null
        try {
            val doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .get()

            hasServerContact = true
            siteName = extractSiteName(doc)
            results = DomainIconResolver.parseHtml(doc, doc.location())
        } catch (e: HttpStatusException) {
            hasServerContact = true
            if (isTransientHttpStatus(e.statusCode)) {
                hasTransientFailure = true
            }
        } catch (_: UnsupportedMimeTypeException) {
            hasServerContact = true
        } catch (_: Exception) {
            hasTransientFailure = true
        }
        return IconHtmlDiscoveryResult(
            iconUrls = results.map { it.url },
            hasServerContact = hasServerContact,
            hasTransientFailure = hasTransientFailure,
            highPriorityUrls = results.filter { it.highPriority }.map { it.url }.toSet(),
            discoveredSiteName = siteName
        )
    }

    private fun downloadBytes(urlStr: String): IconDownloadResult {
        (compatHttpDownloaderProvider?.invoke() ?: httpDownloader)?.let { return it.invoke(urlStr) }
        return try {
            val response = Jsoup.connect(urlStr)
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .followRedirects(true)
                .timeout(TIMEOUT_MS)
                .maxBodySize(MAX_DOWNLOAD_BYTES)
                .execute()
            val statusCode = response.statusCode()
            val isTransient = isTransientHttpStatus(statusCode)
            val bytes = if (statusCode in 200..299) response.bodyAsBytes() else null
            IconDownloadResult(bytes, hasServerContact = true, hasTransientFailure = isTransient)
        } catch (e: HttpStatusException) {
            val isTransient = isTransientHttpStatus(e.statusCode)
            IconDownloadResult(bytes = null, hasServerContact = true, hasTransientFailure = isTransient)
        } catch (_: UnsupportedMimeTypeException) {
            IconDownloadResult(bytes = null, hasServerContact = true, hasTransientFailure = false)
        } catch (_: Exception) {
            IconDownloadResult(bytes = null, hasServerContact = false, hasTransientFailure = true)
        }
    }

    internal fun isTransientHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 429 || statusCode in 500..599

    companion object {
        const val MIN_ICON_SIZE = 96
        const val MAX_ICON_SIZE = 384
        const val NEGATIVE_CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000L
        private const val MAX_MEMORY_CACHE_BYTES = 4 * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
        private const val TIMEOUT_MS = 4000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
