package me.bubblespark.tidilo.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.bubblespark.tidilo.data.cache.DetailHeroPalette
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CacheVisualStateTest {

    private class FakeImageBitmap(
        val tag: String,
        override val width: Int = 64,
        override val height: Int = 64
    ) : ImageBitmap {
        override val hasAlpha: Boolean get() = true
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace
            get() = throw UnsupportedOperationException("Not needed in JVM visual state tests")

        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int
        ) = Unit

        override fun prepareToDraw() = Unit
    }

    private class FakeCacheVisualSource : CacheVisualSource {
        val generation = MutableStateFlow(0L)
        override val cacheGenerationState: StateFlow<Long> = generation.asStateFlow()

        val iconBitmaps = mutableMapOf<String, ImageBitmap>()
        val dominantColors = mutableMapOf<String, Color>()
        val generatedColors = mutableMapOf<String, Color>()
        val iconUpdates = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val ogBitmaps = mutableMapOf<String, ImageBitmap>()
        val exactImageUpdates = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val palettes = mutableMapOf<String, DetailHeroPalette>()

        var diskLoadCalls = 0
        var networkIconLoadCalls = 0
        var dominantColorLoadCalls = 0
        var feedIconLoadCalls = 0
        var ogLoadCalls = 0

        var onLoadIconFromDisk: suspend (String) -> ImageBitmap? = { iconBitmaps[it] }
        var onLoadOrFetchIcon: suspend (String, String?) -> ImageBitmap? = { domain, _ -> iconBitmaps[domain] }
        var onGetDominantColor: suspend (String) -> Color = { domain ->
            dominantColors[domain] ?: generatedColors[domain] ?: generateDomainAccentColor(domain)
        }
        var onLoadOrFetchFeedIcon: suspend (RssFeedEntity) -> ImageBitmap? = { feed ->
            iconBitmaps[feedIconKey(feed)]
        }
        var onLoadOrFetchOgImage: suspend (String, String, String?) -> ImageBitmap? = { pageUrl, domain, _ ->
            ogBitmaps[ogMapKey(domain, pageUrl)]
        }

        override fun extractDomain(url: String): String? = MediaCacheLayout.extractDomain(url)

        override fun getCachedIconBitmap(domain: String): ImageBitmap? = iconBitmaps[domain]

        override fun getCachedDominantColor(domain: String): Color? = dominantColors[domain]

        override fun generateDomainAccentColor(domain: String): Color =
            generatedColors[domain] ?: Color(0xFF336699)

        override suspend fun getDominantColor(context: Context?, domain: String): Color {
            dominantColorLoadCalls++
            return onGetDominantColor(domain)
        }

        override suspend fun loadIconFromDisk(context: Context?, domain: String): ImageBitmap? {
            diskLoadCalls++
            return onLoadIconFromDisk(domain)
        }

        override suspend fun loadOrFetchIcon(context: Context?, domain: String, pageUrl: String?): ImageBitmap? {
            networkIconLoadCalls++
            return onLoadOrFetchIcon(domain, pageUrl)
        }

        override fun observeIconUpdates(domain: String?): Flow<Long> =
            kotlinx.coroutines.flow.flow {
                var rev = 0L
                emit(rev)
                iconUpdates.collect { updatedDomain ->
                    if (domain != null && updatedDomain == domain) {
                        rev++
                        emit(rev)
                    }
                }
            }

        override fun feedIconKey(feed: RssFeedEntity): String = MediaCacheLayout.feedIconKey(feed)

        override fun feedPublisherDomain(feed: RssFeedEntity): String? = MediaCacheLayout.feedPublisherDomain(feed)

        override suspend fun loadOrFetchFeedIcon(context: Context?, feed: RssFeedEntity): ImageBitmap? {
            feedIconLoadCalls++
            return onLoadOrFetchFeedIcon(feed)
        }

        override fun getCachedOgImageBitmap(domain: String, imageKey: String?): ImageBitmap? =
            ogBitmaps[ogMapKey(domain, imageKey)]

        override suspend fun loadOrFetchOgImage(
            context: Context?,
            pageUrl: String,
            domain: String,
            ogImageUrl: String?
        ): ImageBitmap? {
            ogLoadCalls++
            return onLoadOrFetchOgImage(pageUrl, domain, ogImageUrl)
        }

        override fun observeExactImageUpdates(imageKey: String): Flow<String> =
            kotlinx.coroutines.flow.flow {
                exactImageUpdates.collect { key ->
                    if (key == imageKey) emit(key)
                }
            }

        override suspend fun getDetailHeroPalette(
            domain: String,
            imageKey: String?,
            bitmap: ImageBitmap
        ): DetailHeroPalette? = palettes[ogMapKey(domain, imageKey)]

        private fun ogMapKey(domain: String, imageKey: String?): String =
            "$domain|${imageKey.orEmpty()}"
    }

    @Test
    fun resolveInitialDomainVisual_returnsCachedBitmapAndColorOrFallback() {
        val source = FakeCacheVisualSource()
        val cachedBitmap = FakeImageBitmap("cached")
        source.iconBitmaps["example.com"] = cachedBitmap
        source.dominantColors["example.com"] = Color.Red
        source.generatedColors["fallback.org"] = Color.Green

        assertEquals(
            DomainIconVisual(cachedBitmap, Color.Red),
            resolveInitialDomainVisual("example.com", source)
        )
        assertEquals(
            DomainIconVisual(null, Color.Green),
            resolveInitialDomainVisual("fallback.org", source)
        )
        assertEquals(
            DomainIconVisual(null, Color.Unspecified),
            resolveInitialDomainVisual("   ", source)
        )
    }

    @Test
    fun observeDomainVisualFlow_memoryHitSkipsIoAndEmitsSingleUpdateOnInvalidation() = runBlocking {
        val source = FakeCacheVisualSource()
        val initialBitmap = FakeImageBitmap("initial")
        val updatedBitmap = FakeImageBitmap("updated")
        source.iconBitmaps["example.com"] = initialBitmap
        source.dominantColors["example.com"] = Color.Cyan

        val results = mutableListOf<DomainIconVisual>()
        val secondReady = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Unconfined) {
            observeDomainVisualFlow(
                domain = "example.com",
                pageUrl = "https://example.com/post",
                allowNetwork = true,
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(2).toList(results)
            secondReady.complete(Unit)
        }

        yield()
        assertEquals(0, source.diskLoadCalls)
        assertEquals(0, source.networkIconLoadCalls)
        assertEquals(0, source.dominantColorLoadCalls)

        source.iconBitmaps["example.com"] = updatedBitmap
        source.dominantColors["example.com"] = Color.Magenta
        source.iconUpdates.emit("example.com")

        withTimeout(2000) { secondReady.await() }
        job.join()

        assertEquals(2, results.size)
        assertSame(initialBitmap, results[0].bitmap)
        assertEquals(Color.Cyan, results[0].accentColor)
        assertSame(updatedBitmap, results[1].bitmap)
        assertEquals(Color.Magenta, results[1].accentColor)
    }

    @Test
    fun observeDomainVisualFlow_respectsAllowNetworkFlagOnCacheMiss() = runBlocking {
        val diskSource = FakeCacheVisualSource()
        val diskBitmap = FakeImageBitmap("disk")
        diskSource.onLoadIconFromDisk = { domain ->
            diskSource.iconBitmaps[domain] = diskBitmap
            diskSource.dominantColors[domain] = Color.Yellow
            diskBitmap
        }

        val diskResult = withTimeout(2000) {
            observeDomainVisualFlow(
                domain = "disk.org",
                pageUrl = "https://disk.org/a",
                allowNetwork = false,
                context = null,
                source = diskSource,
                ioDispatcher = Dispatchers.Unconfined
            ).take(1).toList().single()
        }

        assertEquals(1, diskSource.diskLoadCalls)
        assertEquals(0, diskSource.networkIconLoadCalls)
        assertSame(diskBitmap, diskResult.bitmap)
        assertEquals(Color.Yellow, diskResult.accentColor)

        val netSource = FakeCacheVisualSource()
        val netBitmap = FakeImageBitmap("network")
        netSource.onLoadOrFetchIcon = { domain, _ ->
            netSource.iconBitmaps[domain] = netBitmap
            netSource.dominantColors[domain] = Color.Blue
            netBitmap
        }

        val netResult = withTimeout(2000) {
            observeDomainVisualFlow(
                domain = "net.org",
                pageUrl = "https://net.org/b",
                allowNetwork = true,
                context = null,
                source = netSource,
                ioDispatcher = Dispatchers.Unconfined
            ).take(1).toList().single()
        }

        assertEquals(0, netSource.diskLoadCalls)
        assertEquals(1, netSource.networkIconLoadCalls)
        assertSame(netBitmap, netResult.bitmap)
        assertEquals(Color.Blue, netResult.accentColor)
    }

    @Test
    fun observeFeedVisualFlow_usesDirectFeedArtworkAndReactsToFeedUpdates() = runBlocking {
        val source = FakeCacheVisualSource()
        val feed = RssFeedEntity(
            id = "feed-1",
            url = "https://feeds.example.com/rss.xml",
            title = "Example Feed",
            iconUrl = "https://cdn.example.com/icon.png",
            siteUrl = "https://example.com"
        )
        val feedKey = source.feedIconKey(feed)
        val directBitmap = FakeImageBitmap("feed-direct")
        val updatedBitmap = FakeImageBitmap("feed-updated")
        source.iconBitmaps[feedKey] = directBitmap
        source.dominantColors[feedKey] = Color.Green

        val results = mutableListOf<FeedIconVisual>()
        val secondReady = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Unconfined) {
            observeFeedVisualFlow(
                feed = feed,
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(2).toList(results)
            secondReady.complete(Unit)
        }

        yield()
        assertEquals(0, source.feedIconLoadCalls)

        source.iconBitmaps[feedKey] = updatedBitmap
        source.dominantColors[feedKey] = Color.Red
        source.iconUpdates.emit(feedKey)

        withTimeout(2000) { secondReady.await() }
        job.join()

        assertEquals(2, results.size)
        assertSame(directBitmap, results[0].bitmap)
        assertEquals(Color.Green, results[0].color)
        assertSame(updatedBitmap, results[1].bitmap)
        assertEquals(Color.Red, results[1].color)
    }

    @Test
    fun observeFeedVisualFlow_fallsBackToPublisherArtworkAndReactsToPublisherUpdates() = runBlocking {
        val source = FakeCacheVisualSource()
        val feed = RssFeedEntity(
            id = "feed-2",
            url = "https://publisher.org/feed.xml",
            title = "Publisher Feed",
            iconUrl = "https://publisher.org/missing-icon.png",
            siteUrl = "https://publisher.org"
        )
        val publisherBitmap = FakeImageBitmap("publisher-icon")
        val updatedPublisherBitmap = FakeImageBitmap("publisher-updated")
        source.onLoadOrFetchFeedIcon = {
            source.iconBitmaps["publisher.org"] = publisherBitmap
            source.dominantColors["publisher.org"] = Color.Blue
            publisherBitmap
        }

        val results = mutableListOf<FeedIconVisual>()
        val secondReady = CompletableDeferred<Unit>()

        val job = launch(Dispatchers.Unconfined) {
            observeFeedVisualFlow(
                feed = feed,
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(2).toList(results)
            secondReady.complete(Unit)
        }

        withTimeout(2000) {
            while (results.isEmpty()) yield()
        }
        assertEquals(1, source.feedIconLoadCalls)

        source.iconBitmaps["publisher.org"] = updatedPublisherBitmap
        source.dominantColors["publisher.org"] = Color.Magenta
        source.iconUpdates.emit("publisher.org")

        withTimeout(2000) { secondReady.await() }
        job.join()

        assertEquals(2, results.size)
        assertSame(publisherBitmap, results[0].bitmap)
        assertEquals(Color.Blue, results[0].color)
        assertSame(updatedPublisherBitmap, results[1].bitmap)
        assertEquals(Color.Magenta, results[1].color)
    }

    @Test
    fun observeOgImageStateFlow_memoryHitSkipsFetchAndMarksResolved() = runBlocking {
        val source = FakeCacheVisualSource()
        val cachedOg = FakeImageBitmap("og-cached")
        source.ogBitmaps["example.com|key-1"] = cachedOg

        val result = withTimeout(2000) {
            observeOgImageStateFlow(
                url = "https://example.com/post",
                domain = "example.com",
                ogImageUrl = "https://cdn.example.com/og.png",
                ogFilename = "key-1",
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(1).toList().single()
        }

        assertEquals(0, source.ogLoadCalls)
        assertSame(cachedOg, result.bitmap)
        assertEquals(true, result.resolved)
    }

    @Test
    fun observeOgImageStateFlow_transitionsPendingToResolvedAndCapturesInFlightExactUpdate() = runBlocking {
        val source = FakeCacheVisualSource()
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val fetchedBitmap = FakeImageBitmap("og-fetched")
        val updatedBitmap = FakeImageBitmap("og-invalidated-update")
        var callCount = 0

        source.onLoadOrFetchOgImage = { _, domain, _ ->
            callCount++
            if (callCount == 1) {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                fetchedBitmap
            } else {
                source.ogBitmaps["$domain|key-2"] = updatedBitmap
                updatedBitmap
            }
        }

        val states = mutableListOf<OgImageState>()
        val job = launch(Dispatchers.Unconfined) {
            observeOgImageStateFlow(
                url = "https://example.com/article",
                domain = "example.com",
                ogImageUrl = "https://cdn.example.com/a.jpg",
                ogFilename = "key-2",
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(3).toList(states)
        }

        withTimeout(2000) { fetchStarted.await() }
        // Emit in-flight exact update BEFORE initial load completes (verifies UNDISPATCHED subscription).
        source.ogBitmaps["example.com|key-2"] = updatedBitmap
        source.exactImageUpdates.emit("key-2")
        releaseFetch.complete(Unit)

        withTimeout(2000) {
            while (states.size < 2) yield()
        }
        job.cancel()

        assertEquals(OgImageState(bitmap = null, resolved = false), states.first())
        assertEquals(true, states.last().resolved)
        assertSame(updatedBitmap, states.last().bitmap)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cacheGenerationReset_clearsCachedVisualsAndReEvaluatesProducer() = runBlocking {
        val source = FakeCacheVisualSource()
        val domain = "reset.example"
        val beforeBitmap = FakeImageBitmap("before-clear")
        val recoveredBitmap = FakeImageBitmap("after-clear-recovered")
        source.iconBitmaps[domain] = beforeBitmap
        source.dominantColors[domain] = Color.Red
        source.onLoadOrFetchIcon = { d, _ ->
            source.iconBitmaps[d] = recoveredBitmap
            source.dominantColors[d] = Color.Green
            recoveredBitmap
        }

        val emitted = mutableListOf<DomainIconVisual>()
        val twoReady = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            source.cacheGenerationState
                .flatMapLatest {
                    observeDomainVisualFlow(
                        domain = domain,
                        pageUrl = "https://$domain",
                        allowNetwork = true,
                        context = null,
                        source = source,
                        ioDispatcher = Dispatchers.Unconfined
                    )
                }
                .take(2)
                .toList(emitted)
            twoReady.complete(Unit)
        }

        yield()
        assertEquals(0, source.networkIconLoadCalls)

        source.iconBitmaps.clear()
        source.dominantColors.clear()
        source.generation.value = source.generation.value + 1L

        withTimeout(2000) { twoReady.await() }
        job.join()

        assertEquals(1, source.networkIconLoadCalls)
        assertEquals(2, emitted.size)
        assertSame(beforeBitmap, emitted[0].bitmap)
        assertEquals(Color.Red, emitted[0].accentColor)
        assertSame(recoveredBitmap, emitted[1].bitmap)
        assertEquals(Color.Green, emitted[1].accentColor)
    }

    @Test
    fun observeDomainVisualFlow_invalidationFallsBackToDiskAndRetainsBitmapOnEvictionMiss() = runBlocking {
        val source = FakeCacheVisualSource()
        val domain = "evict.example"
        val initialBitmap = FakeImageBitmap("initial")
        val diskRecoveredBitmap = FakeImageBitmap("disk-recovered")
        source.iconBitmaps[domain] = initialBitmap
        source.dominantColors[domain] = Color.Cyan

        val results = mutableListOf<DomainIconVisual>()
        val threeReady = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Unconfined) {
            observeDomainVisualFlow(
                domain = domain,
                pageUrl = null,
                allowNetwork = false,
                context = null,
                source = source,
                ioDispatcher = Dispatchers.Unconfined
            ).take(3).toList(results)
            threeReady.complete(Unit)
        }

        yield()

        // 1st invalidation: memory evicted, disk restores a fresh bitmap and color.
        source.iconBitmaps.remove(domain)
        source.dominantColors[domain] = Color.Yellow
        source.onLoadIconFromDisk = { diskRecoveredBitmap }
        source.iconUpdates.emit(domain)

        // 2nd invalidation: memory + disk both miss, previously loaded bitmap must be retained.
        source.onLoadIconFromDisk = { null }
        source.dominantColors[domain] = Color.Red
        source.iconUpdates.emit(domain)

        withTimeout(2000) { threeReady.await() }
        job.join()

        assertEquals(3, results.size)
        assertSame(initialBitmap, results[0].bitmap)
        assertSame(diskRecoveredBitmap, results[1].bitmap)
        assertEquals(Color.Yellow, results[1].accentColor)
        assertSame(diskRecoveredBitmap, results[2].bitmap)
        assertEquals(Color.Red, results[2].accentColor)
    }
}
