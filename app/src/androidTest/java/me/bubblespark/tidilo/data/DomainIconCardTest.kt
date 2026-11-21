package me.bubblespark.tidilo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.cache.IconDownloadResult
import me.bubblespark.tidilo.data.cache.IconHtmlDiscoveryResult
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.cache.MediaColorPalette
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.RssFeedGroup
import me.bubblespark.tidilo.ui.components.CacheVisualSource
import me.bubblespark.tidilo.ui.components.LocalCacheVisualSource
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.DomainCard
import me.bubblespark.tidilo.ui.screens.library.DomainGroupItem
import me.bubblespark.tidilo.ui.screens.library.RssFeedCard
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DomainIconCardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val domain = "icon-ui.example"
    private val faviconUrl = "https://$domain/favicon.png"
    private val ogUrl = "https://$domain/og.png"

    @Test
    fun feedCardRecoversDeclaredIconAndColorAgainWhileVisibleAfterClear() = runBlocking {
        val iconRequests = ConcurrentLinkedQueue<String>()
        val discoveryRequests = ConcurrentLinkedQueue<String>()
        val iconUrl = "https://images-ui.example/logo.png"
        val feed = RssFeedEntity(
            id = "ui-feed",
            url = "https://feed-ui.example/xml",
            title = "UI Feed",
            iconUrl = iconUrl,
            siteUrl = "https://publisher-ui.example/"
        )

        screen(
            iconDownloader = recordIconDownloads(iconRequests),
            htmlDiscoverer = { url ->
                discoveryRequests.add(url)
                IconHtmlDiscoveryResult(emptyList(), hasServerContact = true)
            },
            content = { RssFeedCard(RssFeedGroup(feed, 3), {}) }
        ) { store ->
            val key = MediaCacheLayout.feedIconKey(feed)
            awaitIcon(store, key)
            assertEquals(listOf(iconUrl), iconRequests.toList())

            store.domainIcons.clearCache()
            awaitIcon(store, key)
            assertEquals(listOf(iconUrl, iconUrl), iconRequests.toList())
            assertRecoveredColor(store, key)
            assertEquals(emptyList<String>(), discoveryRequests.toList())
        }
    }

    @Test
    fun gridWaitsForOgSuccessWithoutIconNetwork() = runBlocking {
        val iconRequests = ConcurrentLinkedQueue<String>()
        val ogStarted = CompletableDeferred<String>()
        val releaseOg = CompletableDeferred<Unit>()
        val ogBytes = pngBytes()
        val bookmark = item(image = true)

        screen(
            iconDownloader = recordIconDownloads(iconRequests),
            ogDownloader = { url ->
                ogStarted.complete(url)
                releaseOg.await()
                ogBytes
            },
            content = { BookmarkGridCardFixture(bookmark) }
        ) { store ->
            assertEquals(ogUrl, withTimeout(5_000) { ogStarted.await() })
            frames()
            assertEquals(emptyList<String>(), iconRequests.toList())

            releaseOg.complete(Unit)
            withTimeout(5_000) {
                while (store.ogImages.getCachedOgImageBitmap(domain, bookmark.ogImageFilename) == null) delay(10)
            }
            frames()
            assertEquals(emptyList<String>(), iconRequests.toList())
        }
    }

    @Test
    fun gridRecoversIconOnlyAfterOgFailure() = runBlocking {
        val iconRequests = ConcurrentLinkedQueue<String>()
        val ogStarted = CompletableDeferred<String>()
        val releaseOg = CompletableDeferred<Unit>()

        screen(
            iconDownloader = recordIconDownloads(iconRequests),
            ogDownloader = { url ->
                ogStarted.complete(url)
                releaseOg.await()
                null
            },
            content = { BookmarkGridCardFixture(item(image = true)) }
        ) { store ->
            assertEquals(ogUrl, withTimeout(5_000) { ogStarted.await() })
            frames()
            assertEquals(emptyList<String>(), iconRequests.toList())

            releaseOg.complete(Unit)
            awaitIcon(store, domain)
            assertEquals(listOf(faviconUrl), iconRequests.toList())
            assertRecoveredColor(store, domain)
        }
    }

    @Test
    fun listAndDomainCardsShareRecoveryAndReconnectAfterClear() = runBlocking {
        val iconRequests = ConcurrentLinkedQueue<String>()

        screen(
            iconDownloader = recordIconDownloads(iconRequests),
            content = {
                Column {
                    BookmarkItemCard(item())
                    DomainCard(DomainGroupItem(domain, "Test domain", 1), {})
                }
            }
        ) { store ->
            awaitIcon(store, domain)
            assertEquals(listOf(faviconUrl), iconRequests.toList())

            store.domainIcons.clearCache()
            awaitIcon(store, domain)
            assertEquals(listOf(faviconUrl, faviconUrl), iconRequests.toList())
            assertRecoveredColor(store, domain)
        }
    }

    private fun item(image: Boolean = false) = BookmarkItem(
        "icon-test", "Icon test", "https://$domain/a", "",
        Tag.create("Article", "Tech"),
        ogImageUrl = if (image) ogUrl else null
    )

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888)
        return try {
            val paint = Paint().apply { color = android.graphics.Color.BLUE }
            Canvas(bitmap).drawCircle(90f, 90f, 70f, paint)
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun recordIconDownloads(requests: ConcurrentLinkedQueue<String>): (String) -> IconDownloadResult {
        val bytes = pngBytes()
        return { url ->
            requests.add(url)
            IconDownloadResult(bytes, hasServerContact = true, hasTransientFailure = false)
        }
    }

    private suspend fun awaitIcon(store: MediaCacheStore, key: String) {
        withTimeout(5_000) {
            while (store.domainIcons.getCachedIconBitmap(key) == null) delay(10)
        }
        frames()
    }

    private fun assertRecoveredColor(store: MediaCacheStore, key: String) {
        val color = requireNotNull(store.domainIcons.getCachedDominantColor(key))
        assertNotEquals(MediaColorPalette.generateDomainAccentColor(key), color)
    }

    private suspend fun frames() {
        repeat(3) {
            val frame = CompletableDeferred<Unit>()
            instrumentation.runOnMainSync {
                Choreographer.getInstance().postFrameCallback { frame.complete(Unit) }
            }
            withTimeout(5_000) { frame.await() }
            instrumentation.waitForIdleSync()
        }
    }

    private suspend fun screen(
        iconDownloader: (String) -> IconDownloadResult,
        htmlDiscoverer: (String) -> IconHtmlDiscoveryResult = {
            IconHtmlDiscoveryResult(listOf(faviconUrl), hasServerContact = true)
        },
        ogDownloader: suspend (String) -> ByteArray? = { null },
        content: @Composable () -> Unit,
        check: suspend (MediaCacheStore) -> Unit
    ) {
        val root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "icon-ui-").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = MediaCacheStore(
            cacheRootDir = root,
            ogImageDownloader = ogDownloader,
            iconHttpDownloader = iconDownloader,
            iconHtmlDiscoverer = htmlDiscoverer,
            emissionScope = scope
        )
        val source = CardCacheVisualSource(store)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        CompositionLocalProvider(LocalCacheVisualSource provides source) {
                            MaterialTheme { content() }
                        }
                    }
                }
                check(store)
            }
        } finally {
            try {
                withContext(NonCancellable) { store.clearCache() }
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
    }

    private class CardCacheVisualSource(private val store: MediaCacheStore) : CacheVisualSource {
        override val cacheGenerationState get() = store.revisionBus.cacheGenerationState

        override fun extractDomain(url: String) = MediaCacheLayout.extractDomain(url)
        override fun getCachedIconBitmap(domain: String) = store.domainIcons.getCachedIconBitmap(domain)
        override fun getCachedDominantColor(domain: String) = store.domainIcons.getCachedDominantColor(domain)
        override fun generateDomainAccentColor(domain: String) = MediaColorPalette.generateDomainAccentColor(domain)
        override suspend fun getDominantColor(context: Context?, domain: String) =
            withContext(Dispatchers.IO) { store.domainIcons.getDominantColor(domain) }
        override suspend fun loadIconFromDisk(context: Context?, domain: String) =
            withContext(Dispatchers.IO) { store.domainIcons.loadIconFromDisk(domain) }
        override suspend fun loadOrFetchIcon(context: Context?, domain: String, pageUrl: String?) =
            store.domainIcons.loadOrFetchIcon(domain, pageUrl)
        override fun observeIconUpdates(domain: String?) = store.revisionBus.observeIconUpdates(domain)

        override fun feedIconKey(feed: RssFeedEntity) = MediaCacheLayout.feedIconKey(feed)
        override fun feedPublisherDomain(feed: RssFeedEntity) = MediaCacheLayout.feedPublisherDomain(feed)
        override suspend fun loadOrFetchFeedIcon(context: Context?, feed: RssFeedEntity) =
            store.domainIcons.loadOrFetchFeedIcon(feed)

        override fun getCachedOgImageBitmap(domain: String, imageKey: String?) =
            store.ogImages.getCachedOgImageBitmap(domain, imageKey)
        override suspend fun loadOrFetchOgImage(context: Context?, pageUrl: String, domain: String, ogImageUrl: String?) =
            store.ogImages.loadOrFetchOgImage(pageUrl, domain, ogImageUrl)
        override fun observeExactImageUpdates(imageKey: String) = store.revisionBus.observeExactImageUpdates(imageKey)
        override suspend fun getDetailHeroPalette(domain: String, imageKey: String?, bitmap: ImageBitmap) =
            store.ogImages.getDetailHeroPalette(domain, imageKey, bitmap)
    }
}
