package me.bubblespark.tidilo.data.cache

import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.bubblespark.tidilo.data.CacheStorageRepository
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.ResolvedDomainIcon
import me.bubblespark.tidilo.data.directTransactionRunner
import me.bubblespark.tidilo.data.fakeAppDatabase
import me.bubblespark.tidilo.data.maintenance.LibraryMaintenanceRepository
import me.bubblespark.tidilo.data.maintenance.RETENTION_DAY_MS
import me.bubblespark.tidilo.data.unusedDaoProxy
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.OgCacheReference
import me.bubblespark.tidilo.data.db.RssDao
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.db.RssImageCleanupEntity
import me.bubblespark.tidilo.parser.ArticleNetwork
import me.bubblespark.tidilo.parser.ArticleResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaCacheStoreTest {

    private class FakeDecodedIcon(
        override val width: Int,
        override val height: Int,
        val bytes: ByteArray
    ) : DecodedIconData {
        override fun recycle() = Unit
    }

    private class TestImageBitmap(
        override val width: Int = 128,
        override val height: Int = 128,
        val tag: String = "img"
    ) : ImageBitmap {
        override val hasAlpha: Boolean get() = true
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace
            get() = throw UnsupportedOperationException("Not needed in JVM cache tests")

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

    private fun fakeOgCodec(accent: Color = Color.Cyan): OgImageCodec = object : OgImageCodec {
        override fun decodeFile(file: File): ImageBitmap? =
            file.takeIf { it.isFile && it.length() > 0L }?.let {
                TestImageBitmap(tag = String(it.readBytes()))
            }

        override fun decodeCompressedBytes(bytes: ByteArray): ImageBitmap? =
            bytes.takeIf { it.isNotEmpty() }?.let { TestImageBitmap(tag = String(it)) }

        override fun compressToWebp(rawBytes: ByteArray, maxDimension: Int, maxBytes: Int): CompressedOgImage? =
            if (rawBytes.isEmpty()) null else CompressedOgImage(rawBytes, 640, 360)

        override fun bitmapIdentity(bitmap: ImageBitmap): String =
            (bitmap as? TestImageBitmap)?.tag ?: bitmap.hashCode().toString()

        override fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color = accent

        override fun extractDetailHeroPalette(bitmap: ImageBitmap, domain: String): DetailHeroPalette =
            DetailHeroPalette(accent, accent, accent)
    }

    private fun fakeIconCodec(accent: Color = Color.Magenta): DomainIconCodec = object : DomainIconCodec {
        override fun decodeFile(file: File, domain: String): Pair<ImageBitmap, Color>? =
            file.takeIf { it.isFile && it.length() > 0L }?.let {
                TestImageBitmap(tag = "$domain:${file.name}") to accent
            }

        override fun decodeBytesForResolve(
            bytes: ByteArray,
            minSize: Int,
            maxSize: Int
        ): ResolvedDomainIcon<DecodedIconData>? {
            val size = 192
            if (size < minSize) return null
            return ResolvedDomainIcon(FakeDecodedIcon(size, size, bytes), size, size)
        }

        override fun encodeAndExtract(
            domain: String,
            decoded: DecodedIconData,
            maxSize: Int
        ): EncodedIconAsset {
            val bytes = (decoded as FakeDecodedIcon).bytes
            return EncodedIconAsset(
                pngBytes = bytes,
                imageBitmap = TestImageBitmap(decoded.width, decoded.height, tag = domain),
                dominantColor = accent,
                discard = {}
            )
        }

        override fun extractDominantColor(bitmap: ImageBitmap, domain: String): Color = accent
    }

    @Test
    fun ogImageStore_coalescesConcurrentFetchesAndNotifiesExactUpdates() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_og_coalesce").toFile()
        try {
            val downloads = AtomicInteger(0)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = MediaCacheStore(
                cacheRootDir = tempRoot,
                ogImageDownloader = {
                    downloads.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    "webp-bytes".toByteArray()
                },
                ogImageCodec = fakeOgCodec()
            )
            val imageUrl = "https://cdn.example.com/hero.png?v=1#frag"
            val imageKey = MediaCacheLayout.computeImageKey(imageUrl)
            val exactEvents = mutableListOf<String>()
            val domainEvents = mutableListOf<Long>()
            val exactJob = launch { store.revisionBus.observeExactImageUpdates(imageKey).collect { exactEvents.add(it) } }
            val domainJob = launch { store.revisionBus.observeOgImageUpdates("example.com").collect { domainEvents.add(it) } }
            delay(30)

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                store.ogImages.loadOrFetchOgImage("https://example.com/post", "example.com", imageUrl)
            }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                store.ogImages.loadOrFetchOgImage("https://example.com/post", "example.com", imageUrl)
            }
            release.complete(Unit)

            val b1 = first.await()
            val b2 = second.await()
            delay(30)

            assertNotNull(b1)
            assertSame(b1, b2)
            assertEquals(1, downloads.get())
            assertTrue(store.layout.ogImageFile(imageKey).isFile)
            assertTrue(store.layout.domainLatestOgImageFile("example.com").isFile)
            assertSame(b1, store.ogImages.getCachedOgImageBitmap("example.com", imageKey))
            assertEquals(listOf(imageKey), exactEvents)
            assertTrue(domainEvents.size >= 2)

            exactJob.cancel()
            domainJob.cancel()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun clearCache_advancesGenerationAndRejectsStaleInFlightWrites() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_clear_fence").toFile()
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = MediaCacheStore(
                cacheRootDir = tempRoot,
                ogImageDownloader = {
                    entered.complete(Unit)
                    release.await()
                    "stale-bytes".toByteArray()
                },
                ogImageCodec = fakeOgCodec()
            )
            val clearedEvents = mutableListOf<Long>()
            val clearJob = launch { store.revisionBus.cacheClearedEvent.collect { clearedEvents.add(it) } }
            delay(20)

            val initialGen = store.revisionBus.getCurrentGeneration()
            val imageUrl = "https://stale.example/hero.jpg"
            val imageKey = MediaCacheLayout.computeImageKey(imageUrl)

            val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
                store.ogImages.loadOrFetchOgImage("https://stale.example/a", "stale.example", imageUrl)
            }
            entered.await()

            store.clearCache()
            release.complete(Unit)

            assertNull(inFlight.await())
            delay(20)
            assertEquals(initialGen + 1, store.revisionBus.getCurrentGeneration())
            assertEquals(initialGen + 1, store.revisionBus.cacheGenerationState.value)
            assertEquals(listOf(initialGen + 1), clearedEvents)
            assertFalse(store.layout.ogImageFile(imageKey).exists())
            assertNull(store.ogImages.getCachedOgImageBitmap("stale.example", imageKey))

            clearJob.cancel()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun domainIconStore_reportsSiteNameViaCallbackWithoutMutatingPolicyManager() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_icon_site_name").toFile()
        val domain = "isolated-policy-${System.nanoTime()}.example"
        try {
            val discoveredSites = mutableListOf<Pair<String, String>>()
            val discoverCalls = AtomicInteger(0)
            val downloadCalls = AtomicInteger(0)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val store = MediaCacheStore(
                cacheRootDir = tempRoot,
                iconHtmlDiscoverer = {
                    discoverCalls.incrementAndGet()
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    IconHtmlDiscoveryResult(
                        iconUrls = listOf("https://$domain/icon.png"),
                        hasServerContact = true,
                        hasTransientFailure = false,
                        discoveredSiteName = "Isolated Publisher"
                    )
                },
                iconHttpDownloader = {
                    downloadCalls.incrementAndGet()
                    IconDownloadResult("png-bytes".toByteArray(), hasServerContact = true, hasTransientFailure = false)
                },
                onSiteDisplayNameDiscovered = { d, name -> discoveredSites.add(d to name) },
                domainIconCodec = fakeIconCodec(Color.Green)
            )

            val f1 = async(start = CoroutineStart.UNDISPATCHED) {
                store.domainIcons.fetchAndSaveDomainIcon("https://$domain/article")
            }
            entered.await()
            val f2 = async(start = CoroutineStart.UNDISPATCHED) {
                store.domainIcons.fetchAndSaveDomainIcon("https://$domain/article")
            }
            release.countDown()

            val r1 = f1.await()
            val r2 = f2.await()

            assertTrue(r1.success)
            assertTrue(r2.success)
            assertEquals("Isolated Publisher", r1.discoveredSiteName)
            assertEquals(1, discoverCalls.get())
            assertEquals(1, downloadCalls.get())
            assertEquals(listOf(domain to "Isolated Publisher"), discoveredSites)
            assertEquals("", DomainPolicyManager.getDomainDisplayName(domain))
            assertEquals(Color.Green, store.domainIcons.getDominantColor(domain))
            assertTrue(store.layout.domainIconFile(domain).isFile)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun domainIconPermitsStayIndependentOfBlockedOgDownloads() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_permits").toFile()
        try {
            val ogEntered = Channel<Unit>(Channel.UNLIMITED)
            val ogRelease = CompletableDeferred<Unit>()
            val store = MediaCacheStore(
                cacheRootDir = tempRoot,
                ogImageDownloader = {
                    ogEntered.send(Unit)
                    ogRelease.await()
                    "og".toByteArray()
                },
                iconHtmlDiscoverer = {
                    IconHtmlDiscoveryResult(
                        iconUrls = listOf("https://fast-icon.example/icon.png"),
                        hasServerContact = true,
                        hasTransientFailure = false
                    )
                },
                iconHttpDownloader = {
                    IconDownloadResult("icon".toByteArray(), hasServerContact = true, hasTransientFailure = false)
                },
                ogImageCodec = fakeOgCodec(),
                domainIconCodec = fakeIconCodec()
            )

            val blockedOg = (0 until 4).map { idx ->
                async {
                    store.ogImages.loadOrFetchOgImage(
                        "https://og$idx.example/post",
                        "og$idx.example",
                        "https://og$idx.example/hero.webp"
                    )
                }
            }
            withTimeout(5000) { repeat(4) { ogEntered.receive() } }

            val iconResult = withTimeout(5000) {
                store.domainIcons.fetchAndSaveDomainIcon("https://fast-icon.example/")
            }
            assertTrue(iconResult.success)

            ogRelease.complete(Unit)
            assertEquals(4, blockedOg.awaitAll().count { it != null })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun legacyIconMigrationFeedIconAndStorageScanFollowMediaCacheLayout() = runBlocking {
        val tempDir = Files.createTempDirectory("media_store_layout_scan").toFile()
        val cacheRoot = File(tempDir, "cache")
        val legacyFiles = File(tempDir, "files")
        try {
            val store = MediaCacheStore(
                cacheRootDir = cacheRoot,
                legacyFilesDir = legacyFiles,
                iconHttpDownloader = {
                    IconDownloadResult("feed-icon".toByteArray(), hasServerContact = true, hasTransientFailure = false)
                },
                ogImageDownloader = { "12345678".toByteArray() },
                ogImageCodec = fakeOgCodec(),
                domainIconCodec = fakeIconCodec(Color.Yellow)
            )
            // Missing directories must not be created when scanning before cache population.
            assertEquals(0L, CacheStorageRepository.scan(cacheRoot).totalBytes)
            assertFalse(File(cacheRoot, MediaCacheLayout.OG_IMAGES_DIR_NAME).exists())

            val legacyFile = store.layout.legacyDomainIconFile("legacy.example")!!
            legacyFile.parentFile!!.mkdirs()
            legacyFile.writeBytes(ByteArray(40) { 7 })

            val loaded = store.domainIcons.loadIconFromDisk("legacy.example")
            assertNotNull(loaded)
            assertFalse(legacyFile.exists())
            assertTrue(store.layout.domainIconFile("legacy.example").isFile)

            val feed = RssFeedEntity(
                id = "feed-1",
                url = "https://pub.example/feed.xml",
                title = "Pub",
                iconUrl = "https://cdn.pub.example/avatar.png",
                siteUrl = "https://pub.example"
            )
            val feedBitmap = store.domainIcons.loadOrFetchFeedIcon(feed)
            assertNotNull(feedBitmap)
            assertTrue(store.layout.domainIconFile(MediaCacheLayout.feedIconKey(feed)).isFile)

            store.ogImages.loadOrFetchOgImage(
                "https://article.example/1",
                "article.example",
                "https://cdn.article.example/img.webp"
            )
            store.domainIcons.writeNegativeMarker("missing.example")
            File(cacheRoot, "other/extra.bin").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(13))
            }

            val snapshot = CacheStorageRepository.scan(cacheRoot)
            assertEquals(2, snapshot.iconFiles)
            assertEquals(2, snapshot.ogFiles)
            assertEquals(13L, snapshot.otherBytes)
            assertTrue(snapshot.iconBytes >= 40L)
            assertTrue(snapshot.ogBytes >= 16L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun libraryMaintenancePrunesExpiredAndUnreferencedOgImagesViaMediaCacheStore() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_maintenance").toFile()
        try {
            val store = MediaCacheStore(cacheRootDir = tempRoot, ogImageCodec = fakeOgCodec())
            val now = 100L * RETENTION_DAY_MS
            val staleKey = MediaCacheLayout.computeImageKey("https://stale.example/old.jpg")
            val keptKey = MediaCacheLayout.computeImageKey("https://kept.example/new.jpg")
            val drainedKey = MediaCacheLayout.computeImageKey("https://drained.example/gone.jpg")

            val staleFile = store.layout.ogImageFile(staleKey).apply {
                writeBytes(ByteArray(32))
                setLastModified(now - 10L * RETENTION_DAY_MS)
            }
            val staleLatest = store.layout.domainLatestOgImageFile("stale.example").apply {
                writeBytes(ByteArray(32))
                setLastModified(now - 10L * RETENTION_DAY_MS)
            }
            val keptFile = store.layout.ogImageFile(keptKey).apply {
                writeBytes(ByteArray(32))
                setLastModified(now - 10L * RETENTION_DAY_MS)
            }
            val drainedBytes = ByteArray(24) { 3 }
            val drainedFile = store.layout.ogImageFile(drainedKey).apply {
                writeBytes(drainedBytes)
                setLastModified(now)
            }
            val drainedLatest = store.layout.domainLatestOgImageFile("drained.example").apply {
                writeBytes(drainedBytes)
                setLastModified(now)
            }

            // Warm memory cache for drainedKey so we verify eviction clears it too.
            assertNotNull(store.ogImages.loadOgImage("drained.example", drainedKey))

            val cleanupOutbox = mutableListOf(
                RssImageCleanupEntity("https://drained.example/gone.jpg", "https://drained.example/post")
            )
            val rssDao: RssDao = object : RssDao by unusedDaoProxy() {
                override suspend fun imageRetentionReferences(): List<OgCacheReference> =
                    listOf(OgCacheReference(keptKey, "https://kept.example/post", now - RETENTION_DAY_MS))

                override suspend fun cleanupItems(): List<RssImageCleanupEntity> =
                    cleanupOutbox.toList()

                override suspend fun imageReferenced(key: String): Boolean = false

                override suspend fun domainReferenced(domain: String, pattern: String): Boolean = false

                override suspend fun removeCleanup(url: String, articleUrl: String) {
                    cleanupOutbox.removeAll { it.imageUrl == url && it.articleUrl == articleUrl }
                }
            }
            val baseDb = fakeAppDatabase()
            val db = object : AppDatabase() {
                override fun collectionDao() = baseDb.collectionDao()
                override fun rssDao(): RssDao = rssDao
                override fun bookmarkDao() = baseDb.bookmarkDao()
                override fun trashDao() = baseDb.trashDao()
                override fun importTaskDao() = baseDb.importTaskDao()
                override fun importNotificationDao() = baseDb.importNotificationDao()
                override fun rssNotificationDao() = baseDb.rssNotificationDao()
                override fun domainPolicyDao() = baseDb.domainPolicyDao()
                override fun createInvalidationTracker(): InvalidationTracker =
                    InvalidationTracker(this, emptyMap(), emptyMap(), "bookmarks")
                @Deprecated("Deprecated in Java")
                override fun createOpenHelper(config: androidx.room.DatabaseConfiguration) = error("unused")
                @Deprecated("Deprecated in Java")
                override fun clearAllTables() = Unit
            }

            val repo = LibraryMaintenanceRepository(
                context = ContextWrapper(null),
                database = db,
                nowMillis = { now },
                mediaCacheStore = store,
                transactionRunner = directTransactionRunner()
            )
            repo.cleanOgImages(now)
            repo.drainImageCleanup()

            assertFalse(staleFile.exists())
            assertFalse(staleLatest.exists())
            assertTrue(keptFile.exists())
            assertFalse(drainedFile.exists())
            assertFalse(drainedLatest.exists())
            assertNull(store.ogImages.getCachedOgImageBitmap("drained.example", drainedKey))
            assertTrue(cleanupOutbox.isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun clearingOgImagesLeavesInFlightIconFetchAndSeparateStoresIntact() = runBlocking {
        val rootA = Files.createTempDirectory("media_store_isolated_a").toFile()
        val rootB = Files.createTempDirectory("media_store_isolated_b").toFile()
        try {
            val discoverEntered = CompletableDeferred<Unit>()
            val releaseIcon = CountDownLatch(1)
            val storeA = MediaCacheStore(
                cacheRootDir = rootA,
                iconHtmlDiscoverer = {
                    discoverEntered.complete(Unit)
                    check(releaseIcon.await(5, TimeUnit.SECONDS))
                    IconHtmlDiscoveryResult(
                        iconUrls = listOf("https://kept-icon.example/icon.png"),
                        hasServerContact = true,
                        hasTransientFailure = false
                    )
                },
                iconHttpDownloader = {
                    IconDownloadResult("icon-a".toByteArray(), hasServerContact = true, hasTransientFailure = false)
                },
                ogImageDownloader = { "og-a".toByteArray() },
                ogImageCodec = fakeOgCodec(Color.Cyan),
                domainIconCodec = fakeIconCodec(Color.Green)
            )
            val storeB = MediaCacheStore(
                cacheRootDir = rootB,
                ogImageDownloader = { "og-b".toByteArray() },
                iconHtmlDiscoverer = {
                    IconHtmlDiscoveryResult(
                        iconUrls = listOf("https://b.example/icon.png"),
                        hasServerContact = true,
                        hasTransientFailure = false
                    )
                },
                iconHttpDownloader = {
                    IconDownloadResult("icon-b".toByteArray(), hasServerContact = true, hasTransientFailure = false)
                },
                ogImageCodec = fakeOgCodec(Color.Red),
                domainIconCodec = fakeIconCodec(Color.Blue)
            )

            val initialOgGenA = storeA.revisionBus.getOgGeneration()
            val initialIconGenA = storeA.revisionBus.getIconGeneration()

            val inFlightIcon = async(start = CoroutineStart.UNDISPATCHED) {
                storeA.domainIcons.loadOrFetchIcon("kept-icon.example")
            }
            discoverEntered.await()

            assertNotNull(storeB.ogImages.loadOrFetchOgImage("https://b.example/p", "b.example", "https://b.example/og.png"))
            val ogKeyB = MediaCacheLayout.computeImageKey("https://b.example/og.png")

            storeA.ogImages.clearImageCache()

            assertEquals(initialOgGenA + 1, storeA.revisionBus.getOgGeneration())
            assertEquals(initialIconGenA, storeA.revisionBus.getIconGeneration())

            releaseIcon.countDown()
            assertNotNull(inFlightIcon.await())
            assertTrue(storeA.layout.domainIconFile("kept-icon.example").isFile)

            assertNotNull(storeB.ogImages.getCachedOgImageBitmap("b.example", ogKeyB))
            assertTrue(storeB.layout.ogImageFile(ogKeyB).isFile)
            assertEquals(0L, storeB.revisionBus.getCurrentGeneration())
        } finally {
            rootA.deleteRecursively()
            rootB.deleteRecursively()
        }
    }

    @Test
    fun articleNetworkDiscoveryReportsSiteNameAndLayoutSanitizesIconAndLazyContext() = runBlocking {
        val tempRoot = Files.createTempDirectory("media_store_network_site").toFile()
        val initialRoot = File(tempRoot, "initial")
        val boundRoot = File(tempRoot, "bound")
        try {
            val discoveredSites = mutableListOf<Pair<String, String>>()
            val store = MediaCacheStore(
                cacheRootDir = boundRoot,
                onSiteDisplayNameDiscovered = { d, name -> discoveredSites.add(d to name) },
                domainIconCodec = fakeIconCodec(Color.Yellow)
            )
            val network = object : ArticleNetwork {
                override val scopeKey: String = "rss-feed"
                override suspend fun fetch(url: String): ArticleResponse = when {
                    url.endsWith(".png") -> ArticleResponse(
                        url = url,
                        status = 200,
                        bytes = "png-bytes".toByteArray(),
                        contentType = "image/png"
                    )
                    else -> ArticleResponse(
                        url = url,
                        status = 200,
                        bytes = """
                            <html><head>
                              <meta property="og:site_name" content="Network Publisher" />
                              <link rel="icon" href="/favicon.png" />
                            </head></html>
                        """.trimIndent().toByteArray(),
                        contentType = "text/html"
                    )
                }
            }

            val result = store.domainIcons.fetchAndSaveDomainIcon(
                url = "https://net-icon.example/article",
                network = network
            )
            assertTrue(result.success)
            assertEquals("Network Publisher", result.discoveredSiteName)
            assertEquals(listOf("net-icon.example" to "Network Publisher"), discoveredSites)

            val sanitizedIcon = store.layout.domainIconFile("../escape/host")
            assertEquals(store.layout.domainIconsDir(), sanitizedIcon.parentFile)
            assertFalse(sanitizedIcon.name.contains("/"))

            var cacheDirReads = 0
            val countingContext = object : ContextWrapper(null) {
                override fun getCacheDir(): File {
                    cacheDirReads++
                    return boundRoot
                }

                override fun getFilesDir(): File = File(tempRoot, "files")
            }
            val lazyLayout = MediaCacheLayout(initialRoot)
            lazyLayout.bindContext(countingContext)
            assertEquals(0, cacheDirReads)
            assertEquals(File(boundRoot, MediaCacheLayout.OG_IMAGES_DIR_NAME), lazyLayout.ogImagesDir())
            assertEquals(1, cacheDirReads)
            lazyLayout.bindContext(countingContext)
            lazyLayout.domainIconsDir()
            assertEquals(1, cacheDirReads)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
