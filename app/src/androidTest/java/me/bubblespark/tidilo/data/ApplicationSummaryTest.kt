package me.bubblespark.tidilo.data

import android.content.Context
import android.system.Os
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.moveBookmarkToTrash
import org.junit.Assert.*
import org.junit.Test

class ApplicationSummaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preferencesRecoverPendingAndPreserveFirstQuota() = runBlocking {
        val name = "summary-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val storage = object : AiUsageStorage {
            override fun read() = preferences.getString("snapshot", null)
            override fun write(value: String) = preferences.edit().putString("snapshot", value).commit()
        }
        try {
            val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
            val repository = AiCoreUsageRepository(storage, clock) { ZoneOffset.UTC }
            val first = repository.begin(AiUsageSource.NORMAL)
            repository.complete(first, AiUsageOutcome.FAILED, true)
            repository.begin(AiUsageSource.DEBUG)
            val reopened = AiCoreUsageRepository(storage, clock) { ZoneOffset.UTC }
            reopened.initialize()
            val snapshot = (reopened.state.value as AiUsageState.Ready).snapshot
            val day = snapshot.days.getValue(LocalDate.parse("2026-10-23"))
            assertEquals(2L, day.total)
            assertEquals(1L, day.failed)
            assertEquals(1L, day.interrupted)
            assertEquals(0L, day.pending)
            assertEquals(1L, snapshot.lastQuota!!.calls)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun collectionCountsObserveBothTablesAndIncludeNsfw() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.bookmarkDao()
            assertEquals(CollectionCounts(0, 0), dao.observeCollectionCounts().first())
            dao.insertAll(listOf(
                BookmarkEntity("public", "Public", "https://summary.example/1", "", createdAt = 1, updatedAt = 1),
                BookmarkEntity("adult", "Adult", "https://summary.example/2", "", createdAt = 2, updatedAt = 2, isNsfw = true)))
            assertEquals(CollectionCounts(2, 0), dao.observeCollectionCounts().first())
            coroutineScope {
                val changed = async {
                    withTimeout(5000) { dao.observeCollectionCounts().first { it == CollectionCounts(1, 1) } }
                }
                database.moveBookmarkToTrash("adult", 3, 100)
                assertEquals(CollectionCounts(1, 1), changed.await())
            }
            database.trashDao().deleteById("adult")
            assertEquals(CollectionCounts(1, 0), dao.observeCollectionCounts().first())
        } finally { database.close() }
    }

    @Test fun cacheScannerExcludesSymlinksAndHandlesMissingRoot() = runBlocking {
        val root = File(context.cacheDir, "summary-test-${UUID.randomUUID()}").apply { mkdirs() }
        val external = File(context.cacheDir, "summary-external-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            File(root, "domain_icons").mkdir()
            File(root, "og_images").mkdir()
            File(root, "domain_icons/a.png").writeBytes(ByteArray(12))
            File(root, "domain_icons/marker.none").writeBytes(ByteArray(3))
            File(root, "og_images/a.webp").writeBytes(ByteArray(20))
            File(root, "og_images/latest_domain.webp").writeBytes(ByteArray(20))
            val outside = File(external, "outside.png").apply { writeBytes(ByteArray(100)) }
            Os.symlink(outside.absolutePath, File(root, "domain_icons/link.png").absolutePath)
            Os.symlink(external.absolutePath, File(root, "directory-link").absolutePath)
            val snapshot = CacheStorageRepository.scan(root)
            assertEquals(1, snapshot.iconFiles)
            assertEquals(2, snapshot.ogFiles)
            assertEquals(15L, snapshot.iconBytes)
            assertEquals(55L, snapshot.totalBytes)
            assertEquals(0L, CacheStorageRepository.scan(File(root, "absent")).totalBytes)
            assertTrue(outside.exists())
        } finally { root.deleteRecursively(); external.deleteRecursively() }
    }
}
