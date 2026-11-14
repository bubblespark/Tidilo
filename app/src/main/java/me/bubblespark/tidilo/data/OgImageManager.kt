package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.cache.AndroidOgImageCodec
import me.bubblespark.tidilo.data.cache.CompressedOgImage
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheRevisionBus
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import me.bubblespark.tidilo.data.cache.OgImageStore
import me.bubblespark.tidilo.parser.ArticleNetwork
import java.io.File

/**
 * Compatibility facade delegating OG image caching, palette extraction, and invalidation
 * to [MediaCacheStore] while preserving Part A UI / instrumentation signatures.
 */
object OgImageManager {
    const val MAX_PENDING_FALLBACK_KEYS = MediaCacheRevisionBus.MAX_PENDING_FALLBACK_KEYS
    const val RECONCILE_ALL_KEY = MediaCacheRevisionBus.RECONCILE_ALL_KEY

    const val MAX_IMAGE_DIMENSION = OgImageStore.MAX_IMAGE_DIMENSION
    const val MAX_FILE_SIZE_BYTES = OgImageStore.MAX_FILE_SIZE_BYTES
    const val DEFAULT_QUALITY = OgImageStore.DEFAULT_QUALITY

    typealias CompressedImageResult = CompressedOgImage

    val cacheGenerationState: StateFlow<Long>
        get() = MediaCacheStore.shared.revisionBus.cacheGenerationState

    val ogImageUpdatedDomain: SharedFlow<Pair<String, Long>>
        get() = MediaCacheStore.shared.revisionBus.ogImageUpdatedDomain

    val cacheClearedEvent: SharedFlow<Long>
        get() = MediaCacheStore.shared.revisionBus.cacheClearedEvent

    @VisibleForTesting
    internal var imageDownloader: (suspend (String) -> ByteArray?)? = null

    internal suspend fun getDominantColor(domain: String, imageKey: String?, bitmap: ImageBitmap): Color? =
        MediaCacheStore.shared.ogImages.getDominantColor(domain, imageKey, bitmap)

    internal suspend fun getDetailHeroPalette(
        domain: String,
        imageKey: String?,
        bitmap: ImageBitmap
    ): DetailHeroPalette? =
        MediaCacheStore.shared.ogImages.getDetailHeroPalette(domain, imageKey, bitmap)

    internal suspend fun resolveHeroPalette(
        identity: String,
        extract: suspend () -> DetailHeroPalette
    ): DetailHeroPalette? =
        MediaCacheStore.shared.ogImages.resolveHeroPalette(identity, extract)

    internal suspend fun resolveDominantColor(identity: String, extract: suspend () -> Color): Color? =
        MediaCacheStore.shared.ogImages.resolveDominantColor(identity, extract)

    internal fun invalidateDominantColors(source: String? = null) =
        MediaCacheStore.shared.ogImages.invalidateDominantColors(source)

    internal suspend fun evictExpiredImage(
        context: Context,
        filename: String,
        expectedModifiedAt: Long,
        domains: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.evictExpiredImage(filename, expectedModifiedAt, domains)
    }

    internal suspend fun deleteUnreferencedImage(
        context: Context,
        domain: String,
        imageKey: String,
        removeLatest: Boolean
    ) = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.deleteUnreferencedImage(domain, imageKey, removeLatest)
    }

    internal suspend fun deleteUnreferencedImage(
        context: Context,
        domains: Set<String>,
        imageKey: String,
        removeLatestDomains: Set<String>
    ) = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.deleteUnreferencedImage(
            domains,
            imageKey,
            removeLatestDomains
        )
    }

    fun getDomainRevision(domain: String): Long =
        MediaCacheStore.shared.revisionBus.getOgDomainRevision(domain)

    fun notifyOgImageUpdated(domain: String) =
        MediaCacheStore.shared.revisionBus.notifyOgImageUpdated(domain)

    fun observeExactImageUpdates(imageKey: String): Flow<String> =
        MediaCacheStore.shared.revisionBus.observeExactImageUpdates(imageKey)

    @VisibleForTesting
    internal fun notifyExactImageUpdatedForTesting(domain: String, imageKey: String) =
        MediaCacheStore.shared.revisionBus.notifyExactImageUpdated(domain, imageKey)

    fun observeOgImageUpdates(domain: String?): Flow<Long> =
        MediaCacheStore.shared.revisionBus.observeOgImageUpdates(domain)

    fun getCurrentGeneration(): Long =
        MediaCacheStore.shared.revisionBus.getOgGeneration()

    fun cacheKey(domain: String, imageKey: String): String =
        MediaCacheLayout.ogCacheKey(domain, imageKey)

    fun fallbackCacheKey(domain: String): String =
        MediaCacheLayout.ogFallbackCacheKey(domain)

    fun getCachedOgImageBitmap(domain: String, imageKey: String? = null): ImageBitmap? =
        MediaCacheStore.shared.ogImages.getCachedOgImageBitmap(domain, imageKey)

    fun getOgImageDir(context: Context): File =
        MediaCacheStore.getInstance(context).layout.ogImagesDir()

    fun getOgImageFile(context: Context, imageKey: String): File =
        MediaCacheStore.getInstance(context).layout.ogImageFile(imageKey)

    fun getDomainLatestImageFile(context: Context, domain: String): File =
        MediaCacheStore.getInstance(context).layout.domainLatestOgImageFile(domain)

    suspend fun loadOgImage(
        context: Context,
        domain: String,
        imageKey: String? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.loadOgImage(domain, imageKey)
    }

    @VisibleForTesting
    fun clearMemoryCache() =
        MediaCacheStore.shared.ogImages.clearMemoryCache()

    suspend fun clearImageCache(context: Context) = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.clearImageCache()
    }

    fun extractDomain(url: String): String? =
        MediaCacheLayout.extractDomain(url)

    fun resolveImageUrl(pageUrl: String, imageUrl: String): String =
        MediaCacheLayout.resolveImageUrl(pageUrl, imageUrl)

    fun computeImageKey(resolvedImageUrl: String): String =
        MediaCacheLayout.computeImageKey(resolvedImageUrl)

    @VisibleForTesting
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int =
        AndroidOgImageCodec.calculateInSampleSize(options, reqWidth, reqHeight)

    fun compressToWebp(
        rawBytes: ByteArray,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        maxBytes: Int = MAX_FILE_SIZE_BYTES
    ): CompressedImageResult? =
        AndroidOgImageCodec.compressToWebp(rawBytes, maxDimension, maxBytes)

    suspend fun loadOrFetchOgImage(
        context: Context,
        pageUrl: String,
        domain: String,
        ogImageUrl: String?,
        network: ArticleNetwork? = null
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.loadOrFetchOgImage(pageUrl, domain, ogImageUrl, network)
    }

    suspend fun processAndSaveOgImage(
        context: Context,
        pageUrl: String,
        ogImageUrl: String?,
        network: ArticleNetwork? = null
    ): Boolean = withContext(Dispatchers.IO) {
        MediaCacheStore.getInstance(context).ogImages.processAndSaveOgImage(pageUrl, ogImageUrl, network)
    }
}

typealias DetailHeroPalette = me.bubblespark.tidilo.data.cache.DetailHeroPalette

internal fun extractDetailHeroPalette(bitmap: Bitmap, domain: String): DetailHeroPalette =
    AndroidOgImageCodec.extractDetailHeroPalette(bitmap, domain)

fun extractDetailHeroPaletteFromPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    domain: String
): DetailHeroPalette =
    MediaColorPalette.extractDetailHeroPaletteFromPixels(pixels, width, height, domain)
