package me.bubblespark.tidilo.data.cache

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.CoalescedFetchCoordinator
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.data.db.RssFeedEntity
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for physical cache directories, filename conventions, URL key computation,
 * atomic file writes, and storage/maintenance layout classification under a [cacheRootDir].
 */
internal class MediaCacheLayout(
    cacheRootDir: File,
    legacyFilesDir: File? = null
) {
    enum class CacheBucket {
        DOMAIN_ICONS,
        OG_IMAGES,
        OTHER
    }

    data class CacheEntryClassification(
        val bucket: CacheBucket,
        val isPrimaryAsset: Boolean
    )

    @Volatile
    private var currentCacheRoot: File = cacheRootDir

    @Volatile
    private var currentLegacyFilesRoot: File? = legacyFilesDir

    private val pendingContext = AtomicReference<WeakReference<Context>?>(null)
    private val lastResolvedContext = AtomicReference<WeakReference<Context>?>(null)
    private val rootUpdateLock = Any()

    val cacheRootDir: File
        get() {
            resolvePendingContext()
            return currentCacheRoot
        }

    val legacyFilesDir: File?
        get() {
            resolvePendingContext()
            return currentLegacyFilesRoot
        }

    internal fun bindContext(context: Context) {
        val target = runCatching { context.applicationContext }.getOrNull() ?: context
        if (pendingContext.get() == null && lastResolvedContext.get()?.get() === target) {
            return
        }
        pendingContext.set(WeakReference(target))
    }

    private fun resolvePendingContext() {
        val ref = pendingContext.get() ?: return
        pendingContext.compareAndSet(ref, null)
        val ctx = ref.get() ?: return
        val cacheDir = runCatching { ctx.cacheDir }.getOrNull() ?: return
        val filesDir = runCatching { ctx.filesDir }.getOrNull()
        synchronized(rootUpdateLock) {
            if (pendingContext.get() == null) {
                updateRoots(cacheDir, filesDir)
                lastResolvedContext.set(ref)
            }
        }
    }

    internal fun updateRoots(cacheRoot: File, legacyFiles: File?) {
        if (currentCacheRoot != cacheRoot) {
            currentCacheRoot = cacheRoot
            currentLegacyFilesRoot = legacyFiles
        } else if (legacyFiles != null) {
            currentLegacyFilesRoot = legacyFiles
        }
    }

    fun ogImagesDir(createIfMissing: Boolean = true): File {
        resolvePendingContext()
        val dir = File(currentCacheRoot, OG_IMAGES_DIR_NAME)
        if (createIfMissing && !dir.exists()) dir.mkdirs()
        return dir
    }

    fun domainIconsDir(createIfMissing: Boolean = true): File {
        resolvePendingContext()
        val dir = File(currentCacheRoot, DOMAIN_ICONS_DIR_NAME)
        if (createIfMissing && !dir.exists()) dir.mkdirs()
        return dir
    }

    fun legacyDomainIconsDir(): File? =
        legacyFilesDir?.let { File(it, DOMAIN_ICONS_DIR_NAME) }

    fun ogImageFile(imageKey: String): File =
        File(ogImagesDir(createIfMissing = true), imageKey)

    fun domainLatestOgImageFileName(domain: String): String {
        val safeDomain = domain.replace(SAFE_DOMAIN_REGEX, "_")
        return "$DOMAIN_LATEST_PREFIX$safeDomain$OG_IMAGE_EXTENSION"
    }

    fun domainLatestOgImageFile(domain: String): File =
        File(ogImagesDir(createIfMissing = true), domainLatestOgImageFileName(domain))

    private fun sanitizeIconKey(domain: String): String =
        domain.replace(SAFE_DOMAIN_REGEX, "_")

    fun domainIconFile(domain: String): File =
        File(domainIconsDir(createIfMissing = true), "${sanitizeIconKey(domain)}$DOMAIN_ICON_EXTENSION")

    fun legacyDomainIconFile(domain: String): File? =
        legacyDomainIconsDir()?.let { File(it, "${sanitizeIconKey(domain)}$DOMAIN_ICON_EXTENSION") }

    fun negativeMarkerFile(domain: String): File =
        File(domainIconsDir(createIfMissing = true), "${sanitizeIconKey(domain)}$NEGATIVE_MARKER_EXTENSION")

    fun isPrunableOgImageFileName(fileName: String): Boolean =
        OG_IMAGE_FILE_REGEX.matches(fileName) || isDomainLatestOgImageFileName(fileName)

    fun isDomainLatestOgImageFileName(fileName: String): Boolean =
        fileName.startsWith(DOMAIN_LATEST_PREFIX) && fileName.endsWith(OG_IMAGE_EXTENSION)

    fun extractDomainFromLatestFileName(fileName: String): String =
        fileName.removePrefix(DOMAIN_LATEST_PREFIX).removeSuffix(OG_IMAGE_EXTENSION)

    internal fun writeAtomically(tempPrefix: String, target: File, bytes: ByteArray) {
        target.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        val temporary = File.createTempFile(tempPrefix, ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val OG_IMAGES_DIR_NAME = "og_images"
        const val DOMAIN_ICONS_DIR_NAME = "domain_icons"
        const val OG_IMAGE_EXTENSION = ".webp"
        const val DOMAIN_ICON_EXTENSION = ".png"
        const val NEGATIVE_MARKER_EXTENSION = ".none"
        const val DOMAIN_LATEST_PREFIX = "latest_"
        const val FEED_ICON_PREFIX = "rss_"

        private val OG_IMAGE_FILE_REGEX = Regex("[a-f0-9]{1,64}\\.webp")
        private val SAFE_DOMAIN_REGEX = Regex("[^a-zA-Z0-9.-]")

        fun normalizeDomain(domain: String): String = domain.trim().lowercase()

        fun extractDomain(url: String): String? {
            return try {
                val trimmed = url.trim()
                val uri = URI(
                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                        "https://$trimmed"
                    } else {
                        trimmed
                    }
                )
                val host = uri.host ?: return null
                host.removePrefix("www.").lowercase().substringBefore(':')
            } catch (_: Exception) {
                null
            }
        }

        fun resolveImageUrl(pageUrl: String, imageUrl: String): String {
            val trimmed = imageUrl.trim()
            return try {
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    trimmed
                } else {
                    URI(pageUrl).resolve(trimmed).toString()
                }
            } catch (_: Exception) {
                trimmed
            }
        }

        fun computeImageKey(resolvedImageUrl: String): String {
            val normalized = resolvedImageUrl.trim().substringBefore('#')
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
                val hex = hash.joinToString("") { "%02x".format(it) }
                "$hex$OG_IMAGE_EXTENSION"
            } catch (_: Exception) {
                "${normalized.hashCode().toUInt().toString(16)}$OG_IMAGE_EXTENSION"
            }
        }

        fun feedIconKey(feed: RssFeedEntity): String {
            val identity = listOf(
                feed.id,
                feed.iconUrl.orEmpty(),
                feed.siteUrl.orEmpty(),
                feed.url
            ).joinToString("\n")
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
            return FEED_ICON_PREFIX + hash.joinToString("") { "%02x".format(it) }
        }

        fun feedPublisherDomain(feed: RssFeedEntity): String? =
            feed.siteUrl?.let(::extractDomain) ?: extractDomain(feed.url)

        fun ogCacheKey(domain: String, imageKey: String): String = "$domain/$imageKey"

        fun ogFallbackCacheKey(domain: String): String = "$domain/__latest__"

        /**
         * Classifies a file path relative to a cache root without creating missing directories.
         */
        fun classifyRelativePath(relativePath: Path): CacheEntryClassification {
            val fileName = relativePath.fileName?.toString().orEmpty()
            return when (relativePath.takeIf { it.nameCount > 1 }?.getName(0)?.toString()) {
                DOMAIN_ICONS_DIR_NAME -> CacheEntryClassification(
                    bucket = CacheBucket.DOMAIN_ICONS,
                    isPrimaryAsset = fileName.endsWith(DOMAIN_ICON_EXTENSION, ignoreCase = true)
                )
                OG_IMAGES_DIR_NAME -> CacheEntryClassification(
                    bucket = CacheBucket.OG_IMAGES,
                    isPrimaryAsset = fileName.endsWith(OG_IMAGE_EXTENSION, ignoreCase = true)
                )
                else -> CacheEntryClassification(bucket = CacheBucket.OTHER, isPrimaryAsset = false)
            }
        }
    }
}

/**
 * Shared generation counter, clear event broadcaster, and per-domain / exact-image update bus
 * for OG images and domain icons.
 */
internal class MediaCacheRevisionBus(
    val generation: AtomicLong = AtomicLong(0L),
    private val emissionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    internal val ogGeneration: AtomicLong = AtomicLong(generation.get())
    internal val iconGeneration: AtomicLong = AtomicLong(generation.get())

    private val _cacheGenerationState = MutableStateFlow(generation.get())
    val cacheGenerationState: StateFlow<Long> = _cacheGenerationState.asStateFlow()

    private val _cacheClearedEvent = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val cacheClearedEvent: SharedFlow<Long> = _cacheClearedEvent.asSharedFlow()
    private val clearBroadcastLock = Any()

    private val ogDomainRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val _ogImageUpdatedDomain = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val ogImageUpdatedDomain: SharedFlow<Pair<String, Long>> = _ogImageUpdatedDomain.asSharedFlow()

    private val _exactImageUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val pendingExactKeys = ConcurrentHashMap.newKeySet<String>()
    private val isDrainingExactKeys = AtomicBoolean(false)

    private val iconDomainRevisions = ConcurrentHashMap<String, AtomicLong>()
    private val _iconUpdatedDomain = MutableSharedFlow<Pair<String, Long>>(extraBufferCapacity = 64)
    val iconUpdatedDomain: SharedFlow<Pair<String, Long>> = _iconUpdatedDomain.asSharedFlow()

    fun getCurrentGeneration(): Long = generation.get()

    fun getOgGeneration(): Long = ogGeneration.get()

    fun getIconGeneration(): Long = iconGeneration.get()

    fun getOgDomainRevision(domain: String): Long =
        getDomainRevision(ogDomainRevisions, domain, ogGeneration)

    fun notifyOgImageUpdated(domain: String) {
        notifyDomainChannel(domain, ogDomainRevisions, _ogImageUpdatedDomain, ogGeneration)
    }

    fun notifyExactImageUpdated(domain: String, imageKey: String) {
        if (!_exactImageUpdates.tryEmit(imageKey)) {
            if (pendingExactKeys.size >= MAX_PENDING_FALLBACK_KEYS) {
                pendingExactKeys.clear()
                pendingExactKeys.add(RECONCILE_ALL_KEY)
            } else if (!pendingExactKeys.contains(RECONCILE_ALL_KEY)) {
                pendingExactKeys.add(imageKey)
            }
            drainPendingExactKeys()
        }
        notifyOgImageUpdated(domain)
    }

    fun observeExactImageUpdates(imageKey: String): Flow<String> =
        _exactImageUpdates.filter { it == imageKey || it == RECONCILE_ALL_KEY }

    fun observeOgImageUpdates(domain: String?): Flow<Long> =
        observeDomainChannel(domain, ::getOgDomainRevision, ogImageUpdatedDomain)

    fun getIconDomainRevision(domain: String): Long =
        getDomainRevision(iconDomainRevisions, domain, iconGeneration)

    fun notifyIconUpdated(domain: String) {
        notifyDomainChannel(domain, iconDomainRevisions, _iconUpdatedDomain, iconGeneration)
    }

    fun observeIconUpdates(domain: String?): Flow<Long> =
        observeDomainChannel(domain, ::getIconDomainRevision, iconUpdatedDomain)

    internal fun onOgImagesCleared(advanceSharedGeneration: Boolean = true) {
        ogDomainRevisions.clear()
        pendingExactKeys.clear()
        if (advanceSharedGeneration) {
            advanceAndBroadcastGenerationCleared()
        }
    }

    internal fun onDomainIconsCleared(advanceSharedGeneration: Boolean = true) {
        iconDomainRevisions.clear()
        if (advanceSharedGeneration) {
            advanceAndBroadcastGenerationCleared()
        }
    }

    internal fun advanceAndBroadcastGenerationCleared() {
        synchronized(clearBroadcastLock) {
            val current = generation.incrementAndGet()
            _cacheGenerationState.update { prev -> maxOf(prev, current) }
            _cacheClearedEvent.tryEmit(_cacheGenerationState.value)
        }
    }

    private fun getDomainRevision(
        revisions: ConcurrentHashMap<String, AtomicLong>,
        domain: String,
        storeGeneration: AtomicLong
    ): Long {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        val rev = revisions[normalized]?.get() ?: 0L
        return rev + (storeGeneration.get() * GENERATION_STRIDE)
    }

    private fun notifyDomainChannel(
        domain: String,
        revisions: ConcurrentHashMap<String, AtomicLong>,
        sink: MutableSharedFlow<Pair<String, Long>>,
        storeGeneration: AtomicLong
    ) {
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        val rev = revisions.computeIfAbsent(normalized) { AtomicLong(0L) }.incrementAndGet()
        val currentRev = rev + (storeGeneration.get() * GENERATION_STRIDE)
        sink.tryEmit(Pair(normalized, currentRev))
    }

    private fun observeDomainChannel(
        domain: String?,
        revisionProvider: (String) -> Long,
        updates: SharedFlow<Pair<String, Long>>
    ): Flow<Long> {
        if (domain.isNullOrBlank()) return emptyFlow()
        val normalized = MediaCacheLayout.normalizeDomain(domain)
        return merge(
            updates
                .onSubscription { emit(normalized to revisionProvider(normalized)) }
                .filter { it.first == normalized }
                .map { it.second },
            cacheClearedEvent
                .onSubscription { emit(revisionProvider(normalized)) }
                .map { revisionProvider(normalized) }
        ).distinctUntilChanged()
    }

    private fun drainPendingExactKeys() {
        if (isDrainingExactKeys.compareAndSet(false, true)) {
            emissionScope.launch {
                try {
                    while (pendingExactKeys.isNotEmpty()) {
                        val iterator = pendingExactKeys.iterator()
                        if (iterator.hasNext()) {
                            val key = iterator.next()
                            iterator.remove()
                            _exactImageUpdates.emit(key)
                        }
                    }
                } finally {
                    isDrainingExactKeys.set(false)
                    if (pendingExactKeys.isNotEmpty()) {
                        drainPendingExactKeys()
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_PENDING_FALLBACK_KEYS = 128
        const val RECONCILE_ALL_KEY = "__reconcile_all__"
        private const val GENERATION_STRIDE = 1_000_000_000L
    }
}

/**
 * File-rooted owner of Tidilo's media cache subsystem ([OgImageStore], [DomainIconStore],
 * [MediaCacheLayout], and [MediaCacheRevisionBus]).
 */
internal class MediaCacheStore private constructor(
    cacheRootDir: File,
    legacyFilesDir: File? = null,
    ogImageDownloader: (suspend (String) -> ByteArray?)? = null,
    iconHttpDownloader: ((String) -> IconDownloadResult)? = null,
    iconHtmlDiscoverer: ((String) -> IconHtmlDiscoveryResult)? = null,
    onSiteDisplayNameDiscovered: ((String, String) -> Unit)? = null,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    wallClockMillis: () -> Long = System::currentTimeMillis,
    ogImageCodec: OgImageCodec = AndroidOgImageCodec,
    domainIconCodec: DomainIconCodec = AndroidDomainIconCodec,
    emissionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    compatOgImageDownloaderProvider: (() -> (suspend (String) -> ByteArray?)?)? = null,
    compatIconHttpDownloaderProvider: (() -> ((String) -> IconDownloadResult)?)? = null,
    compatIconHtmlDiscovererProvider: (() -> ((String) -> IconHtmlDiscoveryResult)?)? = null
) {
    constructor(
        cacheRootDir: File,
        legacyFilesDir: File? = null,
        ogImageDownloader: (suspend (String) -> ByteArray?)? = null,
        iconHttpDownloader: ((String) -> IconDownloadResult)? = null,
        iconHtmlDiscoverer: ((String) -> IconHtmlDiscoveryResult)? = null,
        onSiteDisplayNameDiscovered: ((String, String) -> Unit)? = null,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        wallClockMillis: () -> Long = System::currentTimeMillis,
        ogImageCodec: OgImageCodec = AndroidOgImageCodec,
        domainIconCodec: DomainIconCodec = AndroidDomainIconCodec,
        emissionScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        cacheRootDir = cacheRootDir,
        legacyFilesDir = legacyFilesDir,
        ogImageDownloader = ogImageDownloader,
        iconHttpDownloader = iconHttpDownloader,
        iconHtmlDiscoverer = iconHtmlDiscoverer,
        onSiteDisplayNameDiscovered = onSiteDisplayNameDiscovered,
        nowMillis = nowMillis,
        wallClockMillis = wallClockMillis,
        ogImageCodec = ogImageCodec,
        domainIconCodec = domainIconCodec,
        emissionScope = emissionScope,
        compatOgImageDownloaderProvider = null,
        compatIconHttpDownloaderProvider = null,
        compatIconHtmlDiscovererProvider = null
    )

    val layout: MediaCacheLayout = MediaCacheLayout(cacheRootDir, legacyFilesDir)
    val revisionBus: MediaCacheRevisionBus = MediaCacheRevisionBus(emissionScope = emissionScope)

    val ogImages: OgImageStore = OgImageStore(
        layout = layout,
        revisionBus = revisionBus,
        coordinator = CoalescedFetchCoordinator(nowMillis = nowMillis, generation = revisionBus.ogGeneration),
        imageDownloader = ogImageDownloader,
        compatImageDownloaderProvider = compatOgImageDownloaderProvider,
        codec = ogImageCodec
    )

    val domainIcons: DomainIconStore = DomainIconStore(
        layout = layout,
        revisionBus = revisionBus,
        coordinator = CoalescedFetchCoordinator(nowMillis = nowMillis, generation = revisionBus.iconGeneration),
        httpDownloader = iconHttpDownloader,
        htmlDiscoverer = iconHtmlDiscoverer,
        compatHttpDownloaderProvider = compatIconHttpDownloaderProvider,
        compatHtmlDiscovererProvider = compatIconHtmlDiscovererProvider,
        onSiteDisplayNameDiscovered = onSiteDisplayNameDiscovered,
        wallClockMillis = wallClockMillis,
        codec = domainIconCodec
    )

    suspend fun clearCache() {
        ogImages.clearImageCache(broadcastClear = false)
        domainIcons.clearCache(broadcastClear = false)
        revisionBus.advanceAndBroadcastGenerationCleared()
    }

    companion object {
        private val defaultRoot: File
            get() = File(System.getProperty("java.io.tmpdir") ?: ".", "tidilo_media_cache")

        @Volatile
        private var instance: MediaCacheStore? = null

        internal val shared: MediaCacheStore
            get() = instance ?: synchronized(this) {
                instance ?: buildCompatStore(defaultRoot, null).also { instance = it }
            }

        fun getInstance(context: Context): MediaCacheStore {
            val current = instance
            if (current != null) {
                current.layout.bindContext(context)
                return current
            }
            return synchronized(this) {
                val created = instance ?: buildCompatStore(defaultRoot, null).also { instance = it }
                created.layout.bindContext(context)
                created
            }
        }

        private fun buildCompatStore(cacheRootDir: File, legacyFilesDir: File?): MediaCacheStore =
            MediaCacheStore(
                cacheRootDir = cacheRootDir,
                legacyFilesDir = legacyFilesDir,
                onSiteDisplayNameDiscovered = DomainPolicyManager::setDomainDisplayName,
                compatOgImageDownloaderProvider = { OgImageManager.imageDownloader },
                compatIconHttpDownloaderProvider = { DomainIconManager.httpDownloader },
                compatIconHtmlDiscovererProvider = { DomainIconManager.htmlDiscoverer }
            )
    }
}
