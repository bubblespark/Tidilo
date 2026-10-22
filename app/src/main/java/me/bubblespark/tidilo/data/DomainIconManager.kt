package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * Domain-level icon caching and local storage management.
 *
 * - Fetches domain icons and caches them in disposable local storage (cacheDir/domain_icons/).
 * - Prevents redundant fetches if an icon is already cached locally.
 * - Filters out icons smaller than MIN_ICON_SIZE (96x96).
 * - Downsamples icons larger than MAX_ICON_SIZE (384x384).
 * - Selects the largest resolution candidate among available candidates.
 * - Uses bounded memory cache (LruCache) to prevent OOM.
 * - Supports clearing image cache without deleting bookmark data.
 */
object DomainIconManager {

    const val MIN_ICON_SIZE = 96
    const val MAX_ICON_SIZE = 384
    const val DOMAIN_TIMEOUT_MS = 10_000L
    const val NEGATIVE_CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000L // 14 days
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val MAX_MEMORY_CACHE_BYTES = 4 * 1024 * 1024 // 4MB limit
    private val memoryCache = object : LruCache<String, ImageBitmap>(MAX_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val _iconUpdatedDomain = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val iconUpdatedDomain: SharedFlow<String> = _iconUpdatedDomain.asSharedFlow()

    private val _cacheClearedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheClearedEvent: SharedFlow<Unit> = _cacheClearedEvent.asSharedFlow()

    private val cacheLock = Any()
    private var cacheGeneration = 0L

    fun getCurrentGeneration(): Long = synchronized(cacheLock) { cacheGeneration }

    /**
     * Emits a notification that a specific domain's icon has been saved, updated, or removed.
     */
    fun notifyIconUpdated(domain: String) {
        val normalized = domain.trim().lowercase()
        _iconUpdatedDomain.tryEmit(normalized)
    }

    /**
     * Returns a Flow that emits whenever the given domain's icon is updated or the full cache is cleared.
     */
    fun observeIconUpdates(domain: String?): Flow<String> {
        if (domain.isNullOrBlank()) return emptyFlow()
        val normalized = domain.trim().lowercase()
        return merge(
            iconUpdatedDomain.filter { it == normalized },
            cacheClearedEvent.map { normalized }
        )
    }

    /**
     * Extracts normalized domain name from a URL (lowercase, removes 'www.' and port).
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

    private fun getIconDir(context: Context): File {
        val dir = File(context.cacheDir, "domain_icons")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getIconFile(context: Context, domain: String): File {
        val dir = getIconDir(context)
        return File(dir, "$domain.png")
    }

    private fun getLegacyIconFile(context: Context, domain: String): File {
        val dir = File(context.filesDir, "domain_icons")
        return File(dir, "$domain.png")
    }

    /**
     * Checks if a domain icon file exists in local storage.
     */
    fun hasIcon(context: Context, domain: String): Boolean {
        val file = getIconFile(context, domain)
        return (file.exists() && file.length() > 0) || getLegacyIconFile(context, domain).run { exists() && length() > 0 }
    }

    private fun getNegativeMarkerFile(context: Context, domain: String): File {
        val dir = getIconDir(context)
        return File(dir, "$domain.none")
    }

    /**
     * Checks if a domain has a valid negative cache marker (fetch failure within TTL).
     */
    fun shouldSkipFetch(context: Context, domain: String): Boolean = synchronized(cacheLock) {
        val normalized = domain.trim().lowercase()
        val marker = getNegativeMarkerFile(context, normalized)
        if (!marker.exists()) return@synchronized false
        val age = System.currentTimeMillis() - marker.lastModified()
        if (age in 0 until NEGATIVE_CACHE_TTL_MS) {
            true
        } else {
            marker.delete()
            false
        }
    }

    /**
     * Writes a zero-byte negative cache sentinel file indicating icon discovery failure.
     */
    fun writeNegativeMarker(context: Context, domain: String) {
        val normalized = domain.trim().lowercase()
        val marker = getNegativeMarkerFile(context, normalized)
        synchronized(cacheLock) {
            try {
                if (!marker.exists()) {
                    marker.createNewFile()
                } else {
                    marker.setLastModified(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Removes the negative cache marker for the specified domain.
     */
    fun removeNegativeMarker(context: Context, domain: String) {
        val normalized = domain.trim().lowercase()
        val marker = getNegativeMarkerFile(context, normalized)
        synchronized(cacheLock) {
            if (marker.exists()) {
                marker.delete()
            }
        }
    }

    /**
     * Removes all negative cache markers from the domain icons storage.
     */
    fun clearAllNegativeMarkers(context: Context) {
        synchronized(cacheLock) {
            try {
                val dir = getIconDir(context)
                if (dir.exists()) {
                    dir.listFiles { _, name -> name.endsWith(".none") }?.forEach { it.delete() }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Returns the cached ImageBitmap from memory, or null if uncached (0ms, non-blocking).
     */
    fun getCachedIconBitmap(domain: String): ImageBitmap? {
        val normalized = domain.trim().lowercase()
        return memoryCache.get(normalized)
    }

    /**
     * Loads the domain icon from disk, registers it in memory cache, and returns it.
     * (Disk icons are already transparent, so no redundant transparency processing is run).
     */
    fun loadIconFromDisk(context: Context, domain: String): ImageBitmap? {
        val normalized = domain.trim().lowercase()
        return synchronized(cacheLock) load@{
            memoryCache.get(normalized)?.let { return@load it }
            val file = getIconFile(context, normalized)
            val legacy = getLegacyIconFile(context, normalized)
            val targetFile = if (!file.exists() || file.length() == 0L) {
                if (legacy.exists() && legacy.length() > 0L) {
                    val copied = runCatching {
                        legacy.copyTo(file, overwrite = true)
                        legacy.delete()
                        true
                    }.getOrDefault(false)
                    if (copied && file.exists() && file.length() > 0L) {
                        file
                    } else {
                        legacy
                    }
                } else {
                    return@load null
                }
            } else {
                file
            }

            try {
                val rawBitmap = BitmapFactory.decodeFile(targetFile.absolutePath) ?: return@load null
                val imageBitmap = rawBitmap.asImageBitmap()
                memoryCache.put(normalized, imageBitmap)
                imageBitmap
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Retrieves domain icon ImageBitmap from memory cache or disk. Returns null if not stored.
     */
    fun getIconBitmap(context: Context, domain: String): ImageBitmap? {
        return getCachedIconBitmap(domain) ?: loadIconFromDisk(context, domain)
    }

    /**
     * Preloads existing disk-cached icons into memory cache on background thread without network calls.
     */
    fun preloadIcons(context: Context, domains: Collection<String?>) {
        val unique = domains.filterNotNull().map { it.trim().lowercase() }.distinct()
        for (domain in unique) {
            if (memoryCache.get(domain) == null && hasIcon(context, domain)) {
                loadIconFromDisk(context, domain)
            }
        }
    }

    private val dominantColorCache = LruCache<String, Color>(256)

    /**
     * Extracts and caches the dominant brand color of a domain icon.
     * Falls back to a deterministic accent color based on domain hash if unavailable.
     */
    fun getDominantColor(context: Context, domain: String): Color {
        val normalized = domain.trim().lowercase()
        dominantColorCache.get(normalized)?.let { return it }

        val imageBitmap = getCachedIconBitmap(normalized) ?: loadIconFromDisk(context, normalized)
        val color = if (imageBitmap != null) {
            extractDominantColor(imageBitmap.asAndroidBitmap(), normalized)
        } else {
            generateDomainAccentColor(normalized)
        }

        dominantColorCache.put(normalized, color)
        return color
    }

    /**
     * Synchronously returns cached dominant color from memory or null if not yet computed.
     */
    fun getCachedDominantColor(domain: String): Color? {
        val normalized = domain.trim().lowercase()
        return dominantColorCache.get(normalized)
    }

    /**
     * Adaptive neutral colors for monochrome icons calibrated for dark-mode visibility.
     */
    val PLATINUM_WHITE = Color(0xFFE2E8F0) // Tailored for light monochrome icons (Tailwind Slate-200)
    val SLATE_GRAY = Color(0xFF94A3B8)     // Tailored for dark monochrome icons (Tailwind Slate-400)

    /**
     * Pure-Kotlin RGB to HSV converter for portable, testable color analysis without Android framework stubs.
     */
    fun colorToHsv(colorInt: Int, hsv: FloatArray) {
        val r = (colorInt ushr 16) and 0xFF
        val g = (colorInt ushr 8) and 0xFF
        val b = colorInt and 0xFF
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        val v = max
        val s = if (max == 0f) 0f else delta / max
        val h = when {
            delta == 0f -> 0f
            max == rf -> ((gf - bf) / delta) % 6f * 60f
            max == gf -> ((bf - rf) / delta + 2f) * 60f
            else -> ((rf - gf) / delta + 4f) * 60f
        }.let { if (it < 0f) it + 360f else it }

        hsv[0] = h
        hsv[1] = s
        hsv[2] = v
    }

    /**
     * Samples vibrant, saturated pixels from an icon bitmap to determine its primary brand color,
     * or assigns an adaptive neutral (platinum white / slate gray) for monochrome icons.
     */
    fun extractDominantColor(bitmap: Bitmap, domain: String): Color {
        return try {
            val sampleSize = 24
            val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, false)
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
            if (scaled != bitmap) scaled.recycle()

            extractDominantColorFromPixels(pixels, domain)
        } catch (_: Exception) {
            generateDomainAccentColor(domain)
        }
    }

    /**
     * Analyzes pixel array to extract a vibrant brand color or an adaptive monochrome neutral.
     */
    fun extractDominantColorFromPixels(pixels: IntArray, domain: String): Color {
        var bestColor = 0
        var bestScore = -1f
        val hsv = FloatArray(3)

        var visiblePixelCount = 0
        var totalVisibleWeight = 0f
        var maxVisibleWeight = 0f
        var totalVisibleBrightness = 0f
        var chromaticWeight = 0f

        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha == 0) continue

            val alphaWeight = alpha / 255f
            visiblePixelCount++
            totalVisibleWeight += alphaWeight
            maxVisibleWeight = kotlin.math.max(maxVisibleWeight, alphaWeight)

            val rgbBrightness = (
                (((pixel ushr 16) and 0xFF) / 255f) +
                    (((pixel ushr 8) and 0xFF) / 255f) +
                    ((pixel and 0xFF) / 255f)
            ) / 3f
            totalVisibleBrightness += rgbBrightness * alphaWeight

            colorToHsv(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]

            if (saturation > 0.15f) {
                chromaticWeight += alphaWeight
            }

            // Avoid washed-out white or dark near-black for chromatic brand selection
            if (value < 0.15f || (saturation < 0.1f && value > 0.9f)) continue

            // Score by saturation, medium-high luminance, and alpha weight
            val score = saturation * (1f - kotlin.math.abs(value - 0.7f)) * alphaWeight
            if (score > bestScore) {
                bestScore = score
                bestColor = pixel
            }
        }

        if (visiblePixelCount == 0 || totalVisibleWeight == 0f) {
            // Completely transparent image; fallback to hash-based accent
            return generateDomainAccentColor(domain)
        }

        val chromaticRatio = chromaticWeight / totalVisibleWeight
        return if (bestScore > 0.15f * maxVisibleWeight && chromaticRatio > 0.03f) {
            // Vibrant chromatic brand icon (e.g. YouTube, Spotify)
            val r = (bestColor ushr 16) and 0xFF
            val g = (bestColor ushr 8) and 0xFF
            val b = bestColor and 0xFF
            ensureDarkThemeLegibility(Color(r, g, b))
        } else {
            // Monochrome icon (e.g. GitHub, X, Notion)
            val averageBrightness = totalVisibleBrightness / totalVisibleWeight
            if (averageBrightness >= 0.5f) {
                PLATINUM_WHITE
            } else {
                SLATE_GRAY
            }
        }
    }

    /**
     * Calibrates accent color to ensure strong contrast, vibrant ambient glow, and legible text on dark backgrounds.
     * Prevents deep navy, dark blue, and low-luminance tones from dissolving into the dark theme surface.
     */
    fun ensureDarkThemeLegibility(color: Color): Color {
        val hsv = FloatArray(3)
        val argb = ((color.alpha * 255).toInt() shl 24) or
                   ((color.red * 255).toInt() shl 16) or
                   ((color.green * 255).toInt() shl 8) or
                   (color.blue * 255).toInt()
        colorToHsv(argb, hsv)
        val hue = hsv[0]
        var saturation = hsv[1]
        var value = hsv[2]

        // Human eyes have lower perceived sensitivity to blue and violet (hue 190° - 275°)
        val isBlueOrViolet = hue in 190f..275f

        val minValue = if (isBlueOrViolet) 0.88f else 0.72f
        if (value < minValue) {
            value = minValue
        }

        // For deep dark blues, moderate saturation while boosting brightness to yield a luminous brand blue
        if (isBlueOrViolet && saturation > 0.65f) {
            saturation = 0.58f
            value = 0.92f
        } else if (saturation > 0.85f && value < 0.80f) {
            saturation = 0.75f
            value = 0.85f
        }

        return Color.hsv(hue, saturation, value)
    }

    /**
     * Generates a deterministic, aesthetically pleasing accent color based on domain name hash.
     */
    fun generateDomainAccentColor(domain: String): Color {
        val hash = kotlin.math.abs(domain.hashCode())
        val hue = (hash % 360).toFloat()
        val isBlue = hue in 190f..275f
        val sat = if (isBlue) 0.48f else 0.55f
        val v = if (isBlue) 0.92f else 0.85f
        return Color.hsv(hue, sat, v)
    }

    /**
     * Removes domain icon from local disk storage and in-memory cache.
     */
    fun removeIcon(context: Context, domain: String) {
        val normalized = domain.trim().lowercase()
        synchronized(cacheLock) {
            memoryCache.remove(normalized)
            dominantColorCache.remove(normalized)
        }
        val file = getIconFile(context, normalized)
        if (file.exists()) {
            file.delete()
        }
        val legacy = getLegacyIconFile(context, normalized)
        if (legacy.exists()) {
            legacy.delete()
        }
        removeNegativeMarker(context, normalized)
        notifyIconUpdated(normalized)
    }

    /**
     * Clears all domain icon caches (in-memory bitmaps, dominant colors, and cacheDir/filesDir storage).
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        synchronized(cacheLock) {
            cacheGeneration++
            memoryCache.evictAll()
            dominantColorCache.evictAll()
            val dir = File(context.cacheDir, "domain_icons")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            val legacy = File(context.filesDir, "domain_icons")
            if (legacy.exists()) {
                legacy.deleteRecursively()
            }
            _cacheClearedEvent.tryEmit(Unit)
        }
    }

    /**
     * If the four corners are a uniform opaque color (solid background), transparentizes it to extract the logo symbol.
     * Returns the source bitmap unmodified if the corners are already transparent.
     */
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

            // If all 4 corners are transparent (alpha < 30), it is already transparent PNG
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
                return source // Do not convert if corners are not uniform solid background
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
                    pixels[i] = 0 // Fully transparent
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

    /**
     * Downsamples bitmap maintaining aspect ratio if larger than maxSize (default 384x384).
     */
    fun downsampleIfNeeded(bitmap: Bitmap, maxSize: Int = MAX_ICON_SIZE): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap

        val ratio = kotlin.math.min(maxSize.toFloat() / w, maxSize.toFloat() / h)
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    internal data class DownloadResult(
        val bytes: ByteArray?,
        val hasServerContact: Boolean,
        val hasTransientFailure: Boolean = !hasServerContact
    )

    internal data class HtmlDiscoveryResult(
        val iconUrls: List<String>,
        val hasServerContact: Boolean,
        val hasTransientFailure: Boolean = !hasServerContact
    )

    /**
     * Checks if an HTTP status code represents a temporary/transient server error (e.g. 408, 429, 5xx).
     */
    @androidx.annotation.VisibleForTesting
    internal fun isTransientHttpStatus(statusCode: Int): Boolean {
        return statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

    data class FetchResult(
        val success: Boolean,
        val hadTransientFailure: Boolean = false
    )

    @androidx.annotation.VisibleForTesting
    internal var httpDownloader: ((String) -> DownloadResult)? = null

    @androidx.annotation.VisibleForTesting
    internal var htmlDiscoverer: ((String) -> HtmlDiscoveryResult)? = null

    /**
     * Discovers icon candidate URLs from HTML markup (<link rel="icon">, <link rel="apple-touch-icon">, etc.).
     */
    private fun findHtmlIconUrls(pageUrl: String): HtmlDiscoveryResult {
        htmlDiscoverer?.let { return it.invoke(pageUrl) }
        val results = mutableListOf<String>()
        var hasServerContact = false
        var hasTransientFailure = false
        try {
            val doc = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(4000)
                .followRedirects(true)
                .get()

            hasServerContact = true

            // Extract og:site_name and register as domain display name
            val siteName = doc.select("meta[property='og:site_name'], meta[name='og:site_name']").firstOrNull()?.attr("content")?.trim()
            if (!siteName.isNullOrBlank()) {
                val domain = extractDomain(pageUrl)
                if (domain != null) {
                    DomainPolicyManager.setDomainDisplayName(domain, siteName)
                }
            }

            val linkElements = doc.select("link[rel*='icon']")
            for (el in linkElements) {
                val href = el.absUrl("href")
                if (href.isNotBlank() && (href.startsWith("http://") || href.startsWith("https://"))) {
                    results.add(href)
                }
            }

            // msapplication-TileImage (Windows Tile icon, often 144x144)
            val tileMeta = doc.select("meta[name='msapplication-TileImage'], meta[name='msapplication-tileimage']")
            for (el in tileMeta) {
                val content = el.absUrl("content").ifEmpty { el.attr("content") }
                if (content.isNotBlank() && (content.startsWith("http://") || content.startsWith("https://"))) {
                    results.add(content)
                }
            }
        } catch (e: HttpStatusException) {
            hasServerContact = true
            if (isTransientHttpStatus(e.statusCode)) {
                hasTransientFailure = true
            }
        } catch (_: UnsupportedMimeTypeException) {
            hasServerContact = true
            // Completed discovery with non-HTML content (e.g. PDF/video), treated as permanent non-icon source
        } catch (_: Exception) {
            hasTransientFailure = true
        }
        return HtmlDiscoveryResult(results, hasServerContact, hasTransientFailure)
    }

    /**
     * Fetches domain icon and saves it to local disk storage.
     * - Selects the highest resolution candidate.
     * - Requires at least 96x96 resolution.
     * - Downsamples icons exceeding 384x384.
     * - Skips if already cached unless forceRefresh is true.
     */
    suspend fun fetchAndSaveDomainIcon(
        context: Context,
        url: String,
        appleTouchIconUrl: String? = null,
        faviconUrl: String? = null,
        forceRefresh: Boolean = false,
        recordFailureMarker: Boolean = true
    ): FetchResult {
        val requestGeneration = synchronized(cacheLock) { cacheGeneration }
        return withContext(Dispatchers.IO) {
            val domain = extractDomain(url) ?: return@withContext FetchResult(success = false, hadTransientFailure = false)

            if (!forceRefresh) {
                if (hasIcon(context, domain)) {
                    return@withContext FetchResult(success = true, hadTransientFailure = false)
                }
                if (shouldSkipFetch(context, domain)) {
                    return@withContext FetchResult(success = false, hadTransientFailure = false)
                }
            } else {
                removeNegativeMarker(context, domain)
            }

            val candidates = LinkedHashSet<String>()
            if (!appleTouchIconUrl.isNullOrBlank()) candidates.add(appleTouchIconUrl.trim())
            if (!faviconUrl.isNullOrBlank()) candidates.add(faviconUrl.trim())

            var hasServerContact = false
            var hasTransientDiscoveryFailure = false
            var hasTransientDownloadFailure = false

            // Discover icons from HTML markup
            val targetPageUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$domain"
            val htmlDiscovery = findHtmlIconUrls(targetPageUrl)
            if (htmlDiscovery.hasServerContact) hasServerContact = true
            if (htmlDiscovery.hasTransientFailure) hasTransientDiscoveryFailure = true
            candidates.addAll(htmlDiscovery.iconUrls)

            // Fallback to domain root HTML if no icons discovered or target is a deep URL
            val rootPageUrl = "https://$domain"
            if (targetPageUrl != rootPageUrl && targetPageUrl != "$rootPageUrl/") {
                val rootDiscovery = findHtmlIconUrls(rootPageUrl)
                if (rootDiscovery.hasServerContact) hasServerContact = true
                if (rootDiscovery.hasTransientFailure) hasTransientDiscoveryFailure = true
                candidates.addAll(rootDiscovery.iconUrls)
            }

            // Direct root path candidates
            candidates.add("https://$domain/apple-touch-icon.png")
            candidates.add("https://$domain/apple-touch-icon-precomposed.png")

            // Google Favicon service candidates
            candidates.add("https://www.google.com/s2/favicons?domain=$domain&sz=256")
            candidates.add("https://www.google.com/s2/favicons?domain=$domain&sz=128")

            var bestBitmap: Bitmap? = null
            var bestArea = 0

            for (candidateUrl in candidates) {
                try {
                    val download = downloadBytes(candidateUrl)
                    if (download.hasServerContact) hasServerContact = true
                    if (download.hasTransientFailure) hasTransientDownloadFailure = true
                    val bytes = download.bytes ?: continue

                    // Resolution check: exclude candidates smaller than 96x96
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    val width = boundsOptions.outWidth
                    val height = boundsOptions.outHeight

                    if (width < MIN_ICON_SIZE || height < MIN_ICON_SIZE) {
                        continue
                    }

                    val area = width * height
                    if (area > bestArea) {
                        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (rawBitmap != null) {
                            bestBitmap = rawBitmap
                            bestArea = area
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (bestBitmap != null) {
                val saved = synchronized(cacheLock) {
                    if (requestGeneration != cacheGeneration) {
                        false
                    } else {
                        val scaledBitmap = downsampleIfNeeded(bestBitmap, MAX_ICON_SIZE)
                        val processedBitmap = makeBackgroundTransparent(scaledBitmap)

                        val file = getIconFile(context, domain)
                        FileOutputStream(file).use { out ->
                            processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        removeNegativeMarker(context, domain)
                        memoryCache.put(domain, processedBitmap.asImageBitmap())
                        dominantColorCache.put(domain, extractDominantColor(processedBitmap, domain))
                        notifyIconUpdated(domain)
                        true
                    }
                }
                return@withContext FetchResult(success = saved, hadTransientFailure = false)
            }

            val hadTransientFailure = !hasServerContact || hasTransientDownloadFailure || hasTransientDiscoveryFailure

            synchronized(cacheLock) {
                if (requestGeneration == cacheGeneration &&
                    !hadTransientFailure &&
                    recordFailureMarker
                ) {
                    writeNegativeMarker(context, domain)
                }
            }
            FetchResult(success = false, hadTransientFailure = hadTransientFailure)
        }
    }

    /**
     * Forces re-fetching of a single domain icon (with 10-second timeout).
     * Deletes existing icon and returns false upon timeout or failure.
     */
    suspend fun refreshDomainIcon(
        context: Context,
        domain: String
    ): Boolean = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(DOMAIN_TIMEOUT_MS) {
            fetchAndSaveDomainIcon(
                context = context,
                url = "https://$domain",
                forceRefresh = true
            )
        }
        if (result?.success == true) {
            true
        } else {
            removeIcon(context, domain)
            false
        }
    }

    /**
     * Sequentially refreshes icons for all provided domains.
     */
    suspend fun refreshAllDomainIcons(
        context: Context,
        domains: List<String>,
        onProgress: ((current: Int, total: Int, currentDomain: String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        clearAllNegativeMarkers(context)
        val uniqueDomains = domains.distinct()
        val total = uniqueDomains.size
        for ((index, domain) in uniqueDomains.withIndex()) {
            onProgress?.invoke(index, total, domain)
            refreshDomainIcon(context, domain)
            onProgress?.invoke(index + 1, total, domain)
        }
    }

    private fun downloadBytes(urlStr: String): DownloadResult {
        httpDownloader?.let { return it.invoke(urlStr) }
        return try {
            val response = Jsoup.connect(urlStr)
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .followRedirects(true)
                .timeout(4000)
                .maxBodySize(5 * 1024 * 1024)
                .execute()
            val statusCode = response.statusCode()
            val isTransient = isTransientHttpStatus(statusCode)
            val bytes = if (statusCode in 200..299) response.bodyAsBytes() else null
            DownloadResult(bytes, hasServerContact = true, hasTransientFailure = isTransient)
        } catch (e: HttpStatusException) {
            val isTransient = isTransientHttpStatus(e.statusCode)
            DownloadResult(bytes = null, hasServerContact = true, hasTransientFailure = isTransient)
        } catch (_: UnsupportedMimeTypeException) {
            DownloadResult(bytes = null, hasServerContact = true, hasTransientFailure = false)
        } catch (_: Exception) {
            DownloadResult(bytes = null, hasServerContact = false, hasTransientFailure = true)
        }
    }
}
