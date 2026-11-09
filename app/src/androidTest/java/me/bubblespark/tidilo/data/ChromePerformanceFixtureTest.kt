package me.bubblespark.tidilo.data

import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChromePerformanceFixtureTest {
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
