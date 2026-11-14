package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.cache.AndroidDomainIconCodec
import me.bubblespark.tidilo.data.cache.DomainIconStore
import me.bubblespark.tidilo.data.cache.IconDownloadResult
import me.bubblespark.tidilo.data.cache.IconFetchResult
import me.bubblespark.tidilo.data.cache.IconHtmlDiscoveryResult
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.parser.ArticleNetwork

/**
 * Compatibility facade delegating domain & feed icon caching to [MediaCacheStore]
 * while preserving Part A UI / instrumentation signatures.
 */
object DomainIconManager {

    const val MIN_ICON_SIZE = DomainIconStore.MIN_ICON_SIZE
    const val MAX_ICON_SIZE = DomainIconStore.MAX_ICON_SIZE
    const val NEGATIVE_CACHE_TTL_MS = DomainIconStore.NEGATIVE_CACHE_TTL_MS

    val PLATINUM_WHITE: Color = MediaColorPalette.PLATINUM_WHITE
    val SLATE_GRAY: Color = MediaColorPalette.SLATE_GRAY

    internal typealias DownloadResult = IconDownloadResult
    internal typealias HtmlDiscoveryResult = IconHtmlDiscoveryResult
    typealias FetchResult = IconFetchResult

    val iconUpdatedDomain: SharedFlow<Pair<String, Long>>
        get() = MediaCacheStore.shared.revisionBus.iconUpdatedDomain

    val cacheClearedEvent: SharedFlow<Long>
        get() = MediaCacheStore.shared.revisionBus.cacheClearedEvent

    val cacheGenerationState: StateFlow<Long>
        get() = MediaCacheStore.shared.revisionBus.cacheGenerationState

    @VisibleForTesting
    internal var httpDownloader: ((String) -> DownloadResult)? = null

    @VisibleForTesting
    internal var htmlDiscoverer: ((String) -> HtmlDiscoveryResult)? = null

    fun feedIconKey(feed: RssFeedEntity): String =
        MediaCacheLayout.feedIconKey(feed)

    fun feedPublisherDomain(feed: RssFeedEntity): String? =
        MediaCacheLayout.feedPublisherDomain(feed)

    suspend fun loadOrFetchFeedIcon(context: Context, feed: RssFeedEntity): ImageBitmap? =
        withContext(Dispatchers.IO) {
            MediaCacheStore.getInstance(context).domainIcons.loadOrFetchFeedIcon(feed)
        }

    suspend fun loadOrFetchIcon(context: Context, domain: String, pageUrl: String? = null): ImageBitmap? =
        withContext(Dispatchers.IO) {
            MediaCacheStore.getInstance(context).domainIcons.loadOrFetchIcon(domain, pageUrl)
        }

    fun getCurrentGeneration(): Long =
        MediaCacheStore.shared.revisionBus.getIconGeneration()

    fun getDomainRevision(domain: String): Long =
        MediaCacheStore.shared.revisionBus.getIconDomainRevision(domain)

    fun notifyIconUpdated(domain: String) =
        MediaCacheStore.shared.revisionBus.notifyIconUpdated(domain)

    fun observeIconUpdates(domain: String?): Flow<Long> =
        MediaCacheStore.shared.revisionBus.observeIconUpdates(domain)

    fun extractDomain(url: String): String? =
        MediaCacheLayout.extractDomain(url)

    fun hasIcon(context: Context, domain: String): Boolean =
        MediaCacheStore.getInstance(context).domainIcons.hasIcon(domain)

    fun shouldSkipFetch(context: Context, domain: String): Boolean =
        MediaCacheStore.getInstance(context).domainIcons.shouldSkipFetch(domain)

    fun writeNegativeMarker(context: Context, domain: String) =
        MediaCacheStore.getInstance(context).domainIcons.writeNegativeMarker(domain)

    fun removeNegativeMarker(context: Context, domain: String) =
        MediaCacheStore.getInstance(context).domainIcons.removeNegativeMarker(domain)

    fun getCachedIconBitmap(domain: String): ImageBitmap? =
        MediaCacheStore.shared.domainIcons.getCachedIconBitmap(domain)

    fun loadIconFromDisk(context: Context, domain: String): ImageBitmap? =
        MediaCacheStore.getInstance(context).domainIcons.loadIconFromDisk(domain)

    fun getIconBitmap(context: Context, domain: String): ImageBitmap? =
        MediaCacheStore.getInstance(context).domainIcons.getIconBitmap(domain)

    fun preloadIcons(context: Context, domains: Collection<String?>) =
        MediaCacheStore.getInstance(context).domainIcons.preloadIcons(domains)

    fun getDominantColor(context: Context, domain: String): Color =
        MediaCacheStore.getInstance(context).domainIcons.getDominantColor(domain)

    fun getCachedDominantColor(domain: String): Color? =
        MediaCacheStore.shared.domainIcons.getCachedDominantColor(domain)

    fun colorToHsv(colorInt: Int, hsv: FloatArray) =
        MediaColorPalette.colorToHsv(colorInt, hsv)

    fun extractDominantColor(bitmap: Bitmap, domain: String): Color =
        AndroidDomainIconCodec.extractDominantColor(bitmap, domain)

    fun extractDominantColorFromPixels(pixels: IntArray, domain: String): Color =
        MediaColorPalette.extractDominantColorFromPixels(pixels, domain)

    fun ensureDarkThemeLegibility(color: Color): Color =
        MediaColorPalette.ensureDarkThemeLegibility(color)

    fun generateDomainAccentColor(domain: String): Color =
        MediaColorPalette.generateDomainAccentColor(domain)

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).domainIcons.clearCache()
    }

    fun makeBackgroundTransparent(source: Bitmap): Bitmap =
        AndroidDomainIconCodec.makeBackgroundTransparent(source)

    fun downsampleIfNeeded(bitmap: Bitmap, maxSize: Int = MAX_ICON_SIZE): Bitmap =
        AndroidDomainIconCodec.downsampleIfNeeded(bitmap, maxSize)

    @VisibleForTesting
    internal fun isTransientHttpStatus(statusCode: Int): Boolean =
        MediaCacheStore.shared.domainIcons.isTransientHttpStatus(statusCode)

    suspend fun fetchAndSaveDomainIcon(
        context: Context,
        url: String,
        appleTouchIconUrl: String? = null,
        faviconUrl: String? = null,
        knownCandidates: List<DomainIconCandidate>? = null,
        network: ArticleNetwork? = null
    ): FetchResult = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).domainIcons.fetchAndSaveDomainIcon(
            url = url,
            appleTouchIconUrl = appleTouchIconUrl,
            faviconUrl = faviconUrl,
            knownCandidates = knownCandidates,
            network = network
        )
    }
}
