package me.bubblespark.tidilo.data.maintenance

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import me.bubblespark.tidilo.data.TransactionRunner
import me.bubblespark.tidilo.data.cache.MediaCacheLayout
import me.bubblespark.tidilo.data.cache.MediaCacheStore
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssImageCleanupEntity

private fun defaultMaintenanceTransactionRunner(database: AppDatabase): TransactionRunner =
    object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R =
            database.withTransaction { block() }
    }

internal class LibraryMaintenanceRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val mediaCacheStore: MediaCacheStore = MediaCacheStore.getInstance(context),
    private val transactionRunner: TransactionRunner = defaultMaintenanceTransactionRunner(database),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun cleanOgImages(now: Long) = withContext(Dispatchers.IO) {
        val layout = mediaCacheStore.layout
        val files = layout.ogImagesDir().listFiles()
            ?: throw IOException("Could not list OG image cache")
        val candidates = files.filter {
            layout.isPrunableOgImageFileName(it.name) &&
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                ogImageExpired(it.lastModified(), null, now)
        }.map { it to it.lastModified() }
        var failure: IOException? = null
        for (batch in candidates.chunked(64)) {
            currentCoroutineContext().ensureActive()
            transactionRunner.run {
                // One narrow projection per batch, under the same write lock as eligibility and eviction.
                val references = database.rssDao().imageRetentionReferences()
                val latest = mutableMapOf<String, Long>()
                val domains = mutableMapOf<String, MutableSet<String>>()
                for (reference in references) {
                    val domain = MediaCacheLayout.extractDomain(reference.url)
                    val names = listOfNotNull(
                        reference.imageKey,
                        domain?.let(layout::domainLatestOgImageFileName)
                    )
                    for (name in names) {
                        latest[name] = maxOf(latest[name] ?: Long.MIN_VALUE, reference.lastAt)
                        if (domain != null) domains.getOrPut(name) { mutableSetOf() }.add(domain)
                    }
                }
                for ((file, modifiedAt) in batch) {
                    currentCoroutineContext().ensureActive()
                    if (!ogImageExpired(modifiedAt, latest[file.name], now)) continue
                    try {
                        mediaCacheStore.ogImages.evictExpiredImage(
                            filename = file.name,
                            expectedModifiedAt = modifiedAt,
                            domains = domains[file.name].orEmpty()
                        )
                    } catch (e: IOException) {
                        failure = e
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    suspend fun run() {
        val now = nowMillis()
        expireRssBookmarks(now)
        var failure: IOException? = null
        try { drainImageCleanup() } catch (e: IOException) { failure = e }
        cleanOgImages(now)
        failure?.let { throw it }
    }

    suspend fun drainImageCleanup() = withContext(Dispatchers.IO) {
        val dao = database.rssDao()
        var failure: IOException? = null
        val groups = dao.cleanupItems().groupBy {
            MediaCacheLayout.computeImageKey(MediaCacheLayout.resolveImageUrl(it.articleUrl, it.imageUrl))
        }
        for ((key, items) in groups) {
            try {
                transactionRunner.run {
                    if (!dao.imageReferenced(key)) {
                        val domains = items.mapNotNull { MediaCacheLayout.extractDomain(it.articleUrl) }.toSet()
                        val unreferencedDomains = domains.filter { !dao.domainReferenced(it, "%$it%") }.toSet()
                        mediaCacheStore.ogImages.deleteUnreferencedImage(
                            domains = domains,
                            imageKey = key,
                            removeLatestDomains = unreferencedDomains
                        )
                    }
                    for (item in items) dao.removeCleanup(item.imageUrl, item.articleUrl)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: IOException) { failure = e }
        }
        failure?.let { throw it }
    }

    suspend fun expireRssBookmarks(now: Long) = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val removed = transactionRunner.run {
                // Selection and deletion share the write transaction with engagement/membership edits.
                val rows = database.rssDao().expiredRssBookmarks(
                    now - RSS_UNREAD_DAYS * RETENTION_DAY_MS, now - RSS_READ_DAYS * RETENTION_DAY_MS)
                for (row in rows) {
                    row.ogImageUrl?.let { database.rssDao().enqueueCleanup(RssImageCleanupEntity(it, row.url)) }
                    database.bookmarkDao().deleteById(row.id)
                }
                rows.size
            }
            if (removed == 0) break
        }
    }
}
