package me.bubblespark.tidilo.data

import android.content.Context
import androidx.activity.compose.setContent
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.data.maintenance.LibraryMaintenanceRepository
import org.junit.Assert.*
import org.junit.Test

class OgImageRetentionTest {
    private val day = 86_400_000L
    private val now = 100 * day

    private suspend fun fixture(body: suspend (Context, AppDatabase, LibraryMaintenanceRepository) -> Unit) {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(real.cacheDir.toPath(), "retention-test-").toFile()
        var db: AppDatabase? = null
        var failure: Throwable? = null
        try {
            val context = object : ContextWrapper(real) { override fun getCacheDir(): File = dir }
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            db = database
            body(context, database, LibraryMaintenanceRepository(context, database) { now })
        } catch (error: Throwable) { failure = error }
        finally {
            finishFixture(failure, { db?.close() },
                { check(dir.deleteRecursively()) { "Could not delete fixture directory" } },
                { OgImageManager.clearMemoryCache() })
        }
    }

    private fun finishFixture(originalFailure: Throwable?, vararg cleanups: () -> Unit) {
        var failure = originalFailure
        for (cleanup in cleanups) {
            try { cleanup() }
            catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Test fun fixtureCleanupAttemptsEveryStepAndPreservesOriginalFailure() {
        val body = IllegalStateException("body")
        val close = IllegalStateException("close")
        var cleared = false
        try {
            finishFixture(body, { throw close }, { cleared = true })
            fail("Original failure must propagate")
        } catch (error: IllegalStateException) {
            assertSame(body, error)
            assertEquals(listOf(close), error.suppressed.toList())
        }
        assertTrue(cleared)
        cleared = false
        try {
            finishFixture(null, { throw close }, { cleared = true })
            fail("Cleanup failure must propagate")
        } catch (error: IllegalStateException) { assertSame(close, error) }
        assertTrue(cleared)
    }

    private fun bookmark(id: String, age: Int, image: String = "/shared.webp") = BookmarkEntity(
        id, id, "https://cache-fixture.invalid/$id", "", createdAt = now - age * day,
        updatedAt = now, ogImageUrl = image)

    private fun image(context: Context, url: String, age: Int): File =
        OgImageManager.getOgImageFile(context, OgImageManager.computeImageKey(url)).apply {
            writeText("fixture-image")
            assertTrue(setLastModified(now - age * day))
        }

    @Test fun sharedRecentAndUnreadReferencesProtectOldFiles() = runBlocking { fixture { context, db, repo ->
        val url = "https://cache-fixture.invalid/shared.webp"
        val file = image(context, url, 8)
        db.bookmarkDao().insertAll(listOf(bookmark("old", 20), bookmark("new", 6, url)))
        repo.cleanOgImages(now)
        assertTrue(file.exists())
        db.bookmarkDao().deleteById("new")
        db.bookmarkDao().recordSummaryPeek("old", now - 6 * day)
        repo.cleanOgImages(now)
        assertTrue(file.exists())
        repo.cleanOgImages(now + day)
        assertFalse(file.exists())
    } }

    @Test fun renewedFileAndOrphansUseFileAgeRatherThanArticleAge() = runBlocking { fixture { context, db, repo ->
        val url = "https://cache-fixture.invalid/shared.webp"
        val file = image(context, url, 1)
        db.bookmarkDao().insertOrUpdate(bookmark("old", 40))
        repo.cleanOgImages(now)
        assertTrue(file.exists())
        val orphan = image(context, "https://cache-fixture.invalid/orphan.webp", 7)
        repo.cleanOgImages(now)
        assertFalse(orphan.exists())
        assertTrue(file.exists())
        repo.cleanOgImages(now + 6 * day)
        assertFalse(file.exists())
    } }

    @Test fun articleDeletionImmediatelyReclaimsOnlyUnreferencedImages() = runBlocking { fixture { context, db, repo ->
        val url = "https://cache-fixture.invalid/shared.webp"
        val file = image(context, url, 1)
        db.bookmarkDao().insertOrUpdate(bookmark("expired", 10).copy(rssFeedId = "feed"))
        repo.run()
        assertNull(db.bookmarkDao().getBookmarkById("expired"))
        assertFalse(file.exists())
        assertTrue(db.rssDao().cleanupItems().isEmpty())
    } }

    @Test fun identicalRelativeUrlsAcrossDomainsKeepBothDeletionJobs() = runBlocking { fixture { context, db, repo ->
        val files = listOf("first.invalid", "second.invalid").map { domain ->
            val url = "https://$domain/image.webp"
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(domain, domain, "https://$domain/article", "",
                createdAt = now - 10 * day, updatedAt = now, rssFeedId = "feed", ogImageUrl = "/image.webp"))
            image(context, url, 1)
        }
        repo.run()
        files.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(db.rssDao().cleanupItems().isEmpty())
    } }

    @Test fun sharedExactImageRemovesAllUnreferencedDomainCopies() = runBlocking { fixture { context, db, repo ->
        val url = "https://images.invalid/shared.webp"
        val file = image(context, url, 1)
        val copies = listOf("first.invalid", "second.invalid").map { domain ->
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(domain, domain, "https://$domain/article", "",
                createdAt = now - 10 * day, updatedAt = now, rssFeedId = "feed", ogImageUrl = url))
            OgImageManager.getDomainLatestImageFile(context, domain).apply {
                writeBytes(file.readBytes()); assertTrue(setLastModified(now - day))
            }
        }
        repo.run()
        assertFalse(file.exists())
        copies.forEach { assertFalse(it.name, it.exists()) }
    } }

    @Test fun persistedCleanupResumesAfterFilesWereRemovedBeforeCommit() = runBlocking { fixture { context, _, _ ->
        val path = File(context.cacheDir, "cleanup-restart.db").absolutePath
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, path).build()
        var db = open()
        try {
            val url = "https://images.invalid/restart.webp"
            val exact = image(context, url, 1)
            val copies = listOf("first.invalid", "second.invalid").map { domain ->
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(domain, domain, "https://$domain/article", "",
                    createdAt = now - 10 * day, updatedAt = now, rssFeedId = "feed", ogImageUrl = url))
                OgImageManager.getDomainLatestImageFile(context, domain).apply { writeBytes(exact.readBytes()) }
            }
            LibraryMaintenanceRepository(context, db).expireRssBookmarks(now)
            db.close(); db = open()
            assertEquals(2, db.rssDao().cleanupItems().size)
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER interrupt_cleanup BEFORE DELETE ON rss_image_cleanup
                BEGIN SELECT RAISE(ABORT, 'interrupted commit'); END""")
            try {
                LibraryMaintenanceRepository(context, db).drainImageCleanup()
                fail("Queue commit must fail after file removal")
            } catch (_: android.database.sqlite.SQLiteException) { }
            assertFalse(exact.exists())
            copies.forEach { assertFalse(it.exists()) }
            db.close(); db = open()
            assertEquals(2, db.rssDao().cleanupItems().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_cleanup")
            LibraryMaintenanceRepository(context, db).drainImageCleanup()
            assertTrue(db.rssDao().cleanupItems().isEmpty())
        } finally { db.close() }
    } }

    @Test fun visibleImageStateReleasesEvictedBitmapAndRecovers() = runBlocking { fixture { context, db, repo ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val real = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(real, me.bubblespark.tidilo.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as androidx.activity.ComponentActivity
        val observed = java.util.concurrent.atomic.AtomicReference<me.bubblespark.tidilo.ui.components.OgImageState>()
        val previous = OgImageManager.imageDownloader
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val url = "https://cache-fixture.invalid/shared.webp"
        val key = OgImageManager.computeImageKey(url)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray()
        }
        try {
            OgImageManager.imageDownloader = { bytes }
            val initial = OgImageManager.loadOrFetchOgImage(context, "https://cache-fixture.invalid/old",
                "cache-fixture.invalid", url)
            assertNotNull(initial)
            assertTrue(OgImageManager.getOgImageFile(context, key).setLastModified(now - 8 * day))
            db.bookmarkDao().insertOrUpdate(bookmark("old", 20))
            instrumentation.runOnMainSync {
                activity.setContent {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context) {
                        val state = me.bubblespark.tidilo.ui.components.rememberOgImage(
                            "https://cache-fixture.invalid/old", "cache-fixture.invalid", url, key).value
                        androidx.compose.runtime.SideEffect { observed.set(state) }
                    }
                }
            }
            withTimeout(5000) { while (observed.get()?.bitmap == null) kotlinx.coroutines.delay(10) }
            instrumentation.waitForIdleSync()
            OgImageManager.imageDownloader = { entered.complete(Unit); release.await(); bytes }
            repo.cleanOgImages(now)
            withTimeout(5000) { entered.await() }
            withTimeout(5000) { while (observed.get()?.bitmap != null) kotlinx.coroutines.delay(10) }
            release.complete(Unit)
            withTimeout(5000) { while (observed.get()?.bitmap == null) kotlinx.coroutines.delay(10) }
            assertNotSame(initial, observed.get().bitmap)
            assertNull(db.bookmarkDao().getBookmarkById("old")!!.lastInteractedAt)
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { activity.finish() }
            OgImageManager.imageDownloader = previous
        }
    } }

    @Test fun queuedTasksProtectExactAndFallbackImages() = runBlocking { fixture { context, db, repo ->
        val url = "https://cache-fixture.invalid/shared.webp"
        val file = image(context, url, 8)
        val latest = OgImageManager.getDomainLatestImageFile(context, "cache-fixture.invalid")
        latest.writeText("fallback"); assertTrue(latest.setLastModified(now - 8 * day))
        db.importTaskDao().insertOrUpdate(ImportTaskEntity("pending", "https://cache-fixture.invalid/article",
            "Pending", "ERROR", "", createdAt = now - 20 * day, ogImageUrl = url))
        repo.cleanOgImages(now)
        assertTrue(file.exists()); assertTrue(latest.exists())
        db.importTaskDao().deleteById("pending")
        repo.cleanOgImages(now)
        assertFalse(file.exists()); assertFalse(latest.exists())
    } }

    @Test fun fallbackWithoutExactImageStillRespectsRecentDomainReference() = runBlocking { fixture { context, db, repo ->
        val latest = OgImageManager.getDomainLatestImageFile(context, "cache-fixture.invalid")
        latest.writeText("fallback"); assertTrue(latest.setLastModified(now - 8 * day))
        db.bookmarkDao().insertOrUpdate(bookmark("no-image", 1).copy(ogImageUrl = null, ogImageKey = null))
        repo.cleanOgImages(now)
        assertTrue(latest.exists())
    } }

    @Test fun staleFileSnapshotCannotDeleteReplacement() = runBlocking { fixture { context, _, _ ->
        val file = image(context, "https://cache-fixture.invalid/shared.webp", 8)
        val oldTime = file.lastModified()
        assertTrue(file.setLastModified(now))
        assertFalse(OgImageManager.evictExpiredImage(context, file.name, oldTime, emptySet()))
        assertTrue(file.exists())
    } }

    @Test fun trashReferencesAndTemporaryFilesArePreserved() = runBlocking { fixture { context, db, repo ->
        val file = image(context, "https://cache-fixture.invalid/shared.webp", 8)
        db.bookmarkDao().insertOrUpdate(bookmark("trash", 20))
        BookmarkRepository(db, autoInitialize = false).moveToTrash("trash", now, 100)
        val temporary = File(file.parentFile, "og-fixture.tmp").apply {
            writeText("temporary"); setLastModified(now - 8 * day)
        }
        repo.cleanOgImages(now)
        assertTrue(file.exists()); assertTrue(temporary.exists())
        db.trashDao().deleteById("trash")
        repo.cleanOgImages(now)
        assertFalse(file.exists()); assertTrue(temporary.exists())
    } }

    @Test fun failedFileDeletionRetainsOutboxForRetry() = runBlocking { fixture { context, db, repo ->
        val url = "https://cache-fixture.invalid/shared.webp"
        val file = OgImageManager.getOgImageFile(context, OgImageManager.computeImageKey(url))
        assertTrue(file.mkdir())
        val child = File(file, "fixture").apply { writeText("blocks directory removal") }
        db.rssDao().enqueueCleanup(RssImageCleanupEntity(url, "https://cache-fixture.invalid/article"))
        try { repo.drainImageCleanup(); fail("Removal must fail") } catch (_: java.io.IOException) { }
        assertEquals(1, db.rssDao().cleanupItems().size)
        assertTrue(child.delete()); assertTrue(file.delete())
        file.writeText("retry")
        repo.drainImageCleanup()
        assertFalse(file.exists()); assertTrue(db.rssDao().cleanupItems().isEmpty())
    } }

    @Test fun evictionRejectsOldDownloadAndNextRequestRecoversWithoutEngagementWrites() = runBlocking {
        fixture { context, db, repo ->
            val url = "https://cache-fixture.invalid/shared.webp"
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray()
            }
            val previous = OgImageManager.imageDownloader
            var calls = 0
            OgImageManager.imageDownloader = { calls++; entered.complete(Unit); release.await(); bytes }
            val pending = async { OgImageManager.loadOrFetchOgImage(context,
                "https://cache-fixture.invalid/old", "cache-fixture.invalid", url) }
            try {
                withTimeout(5000) { entered.await() }
                val file = image(context, url, 8)
                db.bookmarkDao().insertOrUpdate(bookmark("old", 20))
                repo.cleanOgImages(now)
                release.complete(Unit)
                assertNull(withTimeout(5000) { pending.await() })
                assertFalse(file.exists())
                OgImageManager.imageDownloader = { calls++; bytes }
                assertNotNull(OgImageManager.loadOrFetchOgImage(context,
                    "https://cache-fixture.invalid/old", "cache-fixture.invalid", url))
                assertEquals(2, calls)
                assertNull(db.bookmarkDao().getBookmarkById("old")!!.lastInteractedAt)
                repo.cleanOgImages(now + day)
                assertTrue(file.exists())
            } finally {
                release.complete(Unit); pending.cancel(); pending.join()
                OgImageManager.imageDownloader = previous
            }
        }
    }
}
