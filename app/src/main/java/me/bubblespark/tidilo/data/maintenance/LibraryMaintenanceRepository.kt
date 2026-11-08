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
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.OgImageManager
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssImageCleanupEntity

internal class LibraryMaintenanceRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun cleanOgImages(now: Long) = withContext(Dispatchers.IO) {
        val files = OgImageManager.getOgImageDir(context).listFiles()
            ?: throw IOException("Could not list OG image cache")
        val candidates = files.filter {
            (it.name.matches(Regex("[a-f0-9]{1,64}\\.webp")) ||
                it.name.startsWith("latest_") && it.name.endsWith(".webp")) &&
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                ogImageExpired(it.lastModified(), null, now)
        }.map { it to it.lastModified() }
        var failure: IOException? = null
        for (batch in candidates.chunked(64)) {
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                // One narrow projection per batch, under the same write lock as eligibility and eviction.
                val references = database.rssDao().imageRetentionReferences()
                val latest = mutableMapOf<String, Long>()
                val domains = mutableMapOf<String, MutableSet<String>>()
                for (reference in references) {
                    val domain = DomainIconManager.extractDomain(reference.url)
                    val names = listOfNotNull(reference.imageKey,
                        domain?.let { OgImageManager.getDomainLatestImageFile(context, it).name })
                    for (name in names) {
                        latest[name] = maxOf(latest[name] ?: Long.MIN_VALUE, reference.lastAt)
                        if (domain != null) domains.getOrPut(name) { mutableSetOf() }.add(domain)
                    }
                }
                for ((file, modifiedAt) in batch) {
                    currentCoroutineContext().ensureActive()
                    if (!ogImageExpired(modifiedAt, latest[file.name], now)) continue
                    try {
                        OgImageManager.evictExpiredImage(context, file.name, modifiedAt, domains[file.name].orEmpty())
                    } catch (e: IOException) { failure = e }
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
            OgImageManager.computeImageKey(OgImageManager.resolveImageUrl(it.articleUrl, it.imageUrl))
        }
        for ((key, items) in groups) {
            try {
                database.withTransaction {
                    if (!dao.imageReferenced(key)) {
                        val domains = items.mapNotNull { DomainIconManager.extractDomain(it.articleUrl) }.toSet()
                        val unreferencedDomains = domains.filter { !dao.domainReferenced(it, "%$it%") }.toSet()
                        OgImageManager.deleteUnreferencedImage(context, domains, key, unreferencedDomains)
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
            val removed = database.withTransaction {
                // Selection and deletion share the write transaction with engagement/membership edits.
                val rows = database.rssDao().expiredRssBookmarks(
                    now - 10 * RETENTION_DAY_MS, now - 30 * RETENTION_DAY_MS)
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
