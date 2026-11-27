package me.bubblespark.tidilo.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

class ChromePerformanceFixtureTest {
    private fun withIsolatedFixtureEnv(block: suspend (Context, AppDatabase, File, String) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val tempDir = File(context.cacheDir, "chrome-fixture-test-${UUID.randomUUID()}").apply { mkdirs() }
        val journalFile = File(tempDir, "chrome-performance-owner.json")
        val prefsName = "chrome-fixture-prefs-${UUID.randomUUID()}"
        try {
            block(context, db, journalFile, prefsName)
        } finally {
            db.close()
            context.deleteSharedPreferences(prefsName)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun interruptedJournalRestoresPreferencesAndOnlyOwnedRecords() = withIsolatedFixtureEnv { context, db, journalFile, prefsName ->
        val journal = ChromeFixtureJournal(context, db, journalFile, prefsName)
        journal.recover()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val keys = listOf("library_filter_mode", "library_view_mode")
        val original = keys.associateWith { prefs.getString(it, null) }
        val prefix = "chrome-${UUID.randomUUID()}"
        val ids = (0 until 400).map { "$prefix-$it" }
        val sentinel = UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(
                ids[1],
                "Chrome fixture article 1",
                "https://chrome-frame-fixture.invalid/1",
                "Deterministic local summary 1",
                createdAt = 1001,
                updatedAt = 1
            )
        )
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(
                sentinel,
                "Unrelated sentinel",
                "https://unrelated.invalid/$sentinel",
                "Sentinel",
                createdAt = 1,
                updatedAt = 1
            )
        )
        journal.save(prefix, ids, original, pid = -1)
        check(prefs.edit().putString(keys[0], "DOMAIN").putString(keys[1], "LIST").commit())
        journal.recover()
        assertNull(db.bookmarkDao().getBookmarkById(ids[1]))
        assertNotNull(db.bookmarkDao().getBookmarkById(sentinel))
        assertEquals(original, keys.associateWith { prefs.getString(it, null) })
    }

    @Test
    fun recoveryScenarioPreservesAnEarlierPendingJournal() = withIsolatedFixtureEnv { context, db, journalFile, prefsName ->
        val journal1 = ChromeFixtureJournal(context, db, journalFile, prefsName)
        journal1.recover()
        val dao = db.bookmarkDao()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val keys = listOf("library_filter_mode", "library_view_mode")
        val original = keys.associateWith { prefs.getString(it, null) }
        val prefix1 = "chrome-${UUID.randomUUID()}"
        val ids1 = (0 until 400).map { "$prefix1-$it" }
        dao.insertOrUpdate(
            BookmarkEntity(
                ids1[1],
                "Chrome fixture article 1",
                "https://chrome-frame-fixture.invalid/1",
                "Deterministic local summary 1",
                createdAt = 1001,
                updatedAt = 1
            )
        )
        journal1.save(prefix1, ids1, original, pid = -1)

        // A subsequent journal recovers the earlier pending journal before saving and recovering its own.
        val journal2 = ChromeFixtureJournal(context, db, journalFile, prefsName)
        journal2.recover()
        assertNull("The earlier journal must be recovered before replacement", dao.getBookmarkById(ids1[1]))

        val prefix2 = "chrome-${UUID.randomUUID()}"
        val ids2 = (0 until 400).map { "$prefix2-$it" }
        dao.insertOrUpdate(
            BookmarkEntity(
                ids2[1],
                "Chrome fixture article 1",
                "https://chrome-frame-fixture.invalid/1",
                "Deterministic local summary 1",
                createdAt = 1001,
                updatedAt = 1
            )
        )
        journal2.save(prefix2, ids2, original, pid = -1)
        journal2.recover()
        assertNull("The replacement journal must also recover its own records", dao.getBookmarkById(ids2[1]))
    }

    @Test
    fun visibleDomainIsStableAcrossIndependentFixtures() = withIsolatedFixtureEnv { context, db, journalFile, prefsName ->
        val first = ChromePerformanceFixture(context, db, journalFile, prefsName)
        val second = ChromePerformanceFixture(context, db, journalFile, prefsName)
        assertEquals("chrome-frame-fixture.invalid", first.domain)
        assertEquals(first.domain, second.domain)
    }

    @Test
    fun existingDomainIsNeverMixedWithOrDeletedByFixture() = withIsolatedFixtureEnv { context, db, journalFile, prefsName ->
        val fixture = ChromePerformanceFixture(context, db, journalFile, prefsName)
        val id = UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(
                id,
                "Collision sentinel",
                "https://chrome-frame-fixture.invalid/sentinel",
                "Sentinel",
                createdAt = 1,
                updatedAt = 1
            )
        )
        try {
            fixture.prepare()
            fail("A populated fixture domain must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(requireNotNull(expected.message).contains("already populated"))
        } finally {
            fixture.close()
        }
        assertNotNull(db.bookmarkDao().getBookmarkById(id))
    }
}
