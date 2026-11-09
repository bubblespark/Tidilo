package me.bubblespark.tidilo.data

import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChromePerformanceFixtureTest {
    @Test fun interruptedJournalRestoresPreferencesAndOnlyOwnedRecords() = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val journal = ChromeFixtureJournal(host.context)
        journal.recover()
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val keys = listOf("library_filter_mode", "library_view_mode")
        val original = keys.associateWith { prefs.getString(it, null) }
        val prefix = "chrome-${UUID.randomUUID()}"
        val ids = (0 until 400).map { "$prefix-$it" }
        val sentinel = UUID.randomUUID().toString()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(ids[1], "Chrome fixture article 1", "https://chrome-frame-fixture.invalid/1", "Deterministic local summary 1", createdAt = 1001, updatedAt = 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(sentinel, "Unrelated sentinel", "https://unrelated.invalid/$sentinel", "Sentinel", createdAt = 1, updatedAt = 1))
            journal.save(prefix, ids, original, pid = -1)
            check(prefs.edit().putString(keys[0], "DOMAIN").putString(keys[1], "LIST").commit())
            journal.recover()
            assertNull(db.bookmarkDao().getBookmarkById(ids[1]))
            assertNotNull(db.bookmarkDao().getBookmarkById(sentinel))
            assertEquals(original, keys.associateWith { prefs.getString(it, null) })
        } finally {
            db.bookmarkDao().deleteById(ids[1]); db.bookmarkDao().deleteById(sentinel)
            journal.restorePreferences(original); journal.clear()
        }
    }

    @Test fun recoveryScenarioPreservesAnEarlierPendingJournal() = runBlocking {
        val host = HeaderTestHost()
        val journal = ChromeFixtureJournal(host.context)
        journal.recover()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val prefs = host.context.getSharedPreferences("tidilo_library_prefs", android.content.Context.MODE_PRIVATE)
        val original = listOf("library_filter_mode", "library_view_mode").associateWith { prefs.getString(it, null) }
        val prefix = "chrome-${UUID.randomUUID()}"
        val ids = (0 until 400).map { "$prefix-$it" }
        try {
            dao.insertOrUpdate(BookmarkEntity(ids[1], "Chrome fixture article 1", "https://chrome-frame-fixture.invalid/1", "Deterministic local summary 1", createdAt = 1001, updatedAt = 1))
            journal.save(prefix, ids, original, pid = -1)
            interruptedJournalRestoresPreferencesAndOnlyOwnedRecords()
            assertNull("The earlier journal must be recovered before replacement", dao.getBookmarkById(ids[1]))
        } finally {
            dao.deleteById(ids[1]); journal.restorePreferences(original); journal.clear()
        }
    }

    @Test fun visibleDomainIsStableAcrossIndependentFixtures() {
        val host = HeaderTestHost()
        val first = ChromePerformanceFixture(host)
        val second = ChromePerformanceFixture(host)
        assertEquals("chrome-frame-fixture.invalid", first.domain)
        assertEquals(first.domain, second.domain)
    }

    @Test fun existingDomainIsNeverMixedWithOrDeletedByFixture() = runBlocking {
        val host = HeaderTestHost()
        val fixture = ChromePerformanceFixture(host)
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Collision sentinel", "https://chrome-frame-fixture.invalid/sentinel", "Sentinel", createdAt = 1, updatedAt = 1))
        try {
            try {
                fixture.prepare()
                fail("A populated fixture domain must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(requireNotNull(expected.message).contains("already populated"))
            } finally { fixture.close() }
            assertNotNull(db.bookmarkDao().getBookmarkById(id))
        } finally { db.bookmarkDao().deleteById(id) }
    }
}
