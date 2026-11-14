package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.cache.DetailHeroPalette
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import me.bubblespark.tidilo.data.db.RssFeedEntity

/**
 * UI-facing contract for reading cached media artwork, brand accent colors, hero palettes,
 * and invalidation streams without direct dependency on cache manager singletons.
 */
interface CacheVisualSource {
    val cacheGenerationState: StateFlow<Long>

    fun extractDomain(url: String): String?

    fun getCachedIconBitmap(domain: String): ImageBitmap?
    fun getCachedDominantColor(domain: String): Color?
    fun generateDomainAccentColor(domain: String): Color
    fun resolveInstantDomainAccentColor(domain: String): Color =
        if (domain.isBlank()) Color.Unspecified
        else getCachedDominantColor(domain) ?: generateDomainAccentColor(domain)

    suspend fun getDominantColor(context: Context?, domain: String): Color
    suspend fun loadIconFromDisk(context: Context?, domain: String): ImageBitmap?
    suspend fun loadOrFetchIcon(context: Context?, domain: String, pageUrl: String? = null): ImageBitmap?
    fun observeIconUpdates(domain: String?): Flow<Long>

    fun feedIconKey(feed: RssFeedEntity): String
    fun feedPublisherDomain(feed: RssFeedEntity): String?
    suspend fun loadOrFetchFeedIcon(context: Context?, feed: RssFeedEntity): ImageBitmap?

    fun getCachedOgImageBitmap(domain: String, imageKey: String? = null): ImageBitmap?
    suspend fun loadOrFetchOgImage(
        context: Context?,
        pageUrl: String,
        domain: String,
        ogImageUrl: String?
    ): ImageBitmap?

    fun observeExactImageUpdates(imageKey: String): Flow<String>
    suspend fun getDetailHeroPalette(
        domain: String,
        imageKey: String?,
        bitmap: ImageBitmap
    ): DetailHeroPalette?
}

/**
 * Production [CacheVisualSource] backed by [MediaCacheStore] while preserving
 * `@VisibleForTesting` downloader/discoverer compatibility providers.
 */
internal object SingletonCacheVisualAdapter : CacheVisualSource {
    private fun storeFor(context: Context?): MediaCacheStore =
        if (context != null) MediaCacheStore.getInstance(context) else MediaCacheStore.shared

    override val cacheGenerationState: StateFlow<Long>
        get() = MediaCacheStore.shared.revisionBus.cacheGenerationState

    override fun extractDomain(url: String): String? =
        MediaCacheLayout.extractDomain(url)

    override fun getCachedIconBitmap(domain: String): ImageBitmap? =
        MediaCacheStore.shared.domainIcons.getCachedIconBitmap(domain)

    override fun getCachedDominantColor(domain: String): Color? =
        MediaCacheStore.shared.domainIcons.getCachedDominantColor(domain)

    override fun generateDomainAccentColor(domain: String): Color =
        MediaColorPalette.generateDomainAccentColor(domain)

    override suspend fun getDominantColor(context: Context?, domain: String): Color =
        withContext(Dispatchers.IO) {
            storeFor(context).domainIcons.getDominantColor(domain)
        }

    override suspend fun loadIconFromDisk(context: Context?, domain: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            storeFor(context).domainIcons.loadIconFromDisk(domain)
        }

    override suspend fun loadOrFetchIcon(
        context: Context?,
        domain: String,
        pageUrl: String?
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        storeFor(context).domainIcons.loadOrFetchIcon(domain, pageUrl)
    }

    override fun observeIconUpdates(domain: String?): Flow<Long> =
        MediaCacheStore.shared.revisionBus.observeIconUpdates(domain)

    override fun feedIconKey(feed: RssFeedEntity): String =
        MediaCacheLayout.feedIconKey(feed)

    override fun feedPublisherDomain(feed: RssFeedEntity): String? =
        MediaCacheLayout.feedPublisherDomain(feed)

    override suspend fun loadOrFetchFeedIcon(context: Context?, feed: RssFeedEntity): ImageBitmap? =
        withContext(Dispatchers.IO) {
            storeFor(context).domainIcons.loadOrFetchFeedIcon(feed)
        }

    override fun getCachedOgImageBitmap(domain: String, imageKey: String?): ImageBitmap? =
        MediaCacheStore.shared.ogImages.getCachedOgImageBitmap(domain, imageKey)

    override suspend fun loadOrFetchOgImage(
        context: Context?,
        pageUrl: String,
        domain: String,
        ogImageUrl: String?
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        storeFor(context).ogImages.loadOrFetchOgImage(pageUrl, domain, ogImageUrl)
    }

    override fun observeExactImageUpdates(imageKey: String): Flow<String> =
        MediaCacheStore.shared.revisionBus.observeExactImageUpdates(imageKey)

    override suspend fun getDetailHeroPalette(
        domain: String,
        imageKey: String?,
        bitmap: ImageBitmap
    ): DetailHeroPalette? = MediaCacheStore.shared.ogImages.getDetailHeroPalette(domain, imageKey, bitmap)
}

val LocalCacheVisualSource = staticCompositionLocalOf<CacheVisualSource> { SingletonCacheVisualAdapter }
