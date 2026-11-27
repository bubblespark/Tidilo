package me.bubblespark.tidilo.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.engagement.engagementCutoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun memory() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun bookmark(id: String, entities: List<String> = emptyList(), isLiked: Boolean = false, isPrivate: Boolean = false) =
        BookmarkEntity(id, id, "https://fixture.invalid/$id", "", entities = entities, createdAt = day, updatedAt = day,
            isLiked = isLiked, isPrivate = isPrivate)
    private fun event(id: String, type: EngagementType, at: Long) =
        EngagementEventEntity(bookmarkId = id, type = type.code, surface = EngagementSurface.LIBRARY.code, at = at)

    @Test fun migration23To24CreatesEmptyTablesAndKeepsData() = runBlocking {
        val name = "discover-migration-${UUID.randomUUID()}.db"
        try {
            val before = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                before.bookmarkDao().insertOrUpdate(bookmark("kept", listOf("Kotlin")))
                before.engagementDao().insert(event("kept", EngagementType.DETAIL, now))
                val sql = before.openHelper.writableDatabase
                dropDiscoverTables(sql::execSQL)
                sql.version = 23
            } finally {
                before.close()
            }
            val after = openMigratedDatabase(context, name)
            try {
                val db = after.openHelper.readableDatabase
                assertEquals(24, db.version)
                assertEquals("kept", after.bookmarkDao().getBookmarkById("kept")?.id)
                assertEquals(1, after.engagementDao().eventsFor("kept").size)
                for (table in listOf("discover_snapshot", "discover_map", "discover_snapshot_meta", "rediscovery_suppressions", "rediscovery_topic_mutes")) {
                    assertEquals(table, 0, db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) })
                }
                assertEquals(0, db.query("PRAGMA foreign_key_check").use { it.count })
            } finally { after.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pruneKeepsOnlyTheLatestOpenPastRetention() = runBlocking {
        val db = memory()
        try {
            db.bookmarkDao().insertAll(listOf(bookmark("a"), bookmark("b")))
            val events = db.engagementDao()
            events.insert(event("a", EngagementType.DETAIL, now - 200 * day))
            events.insert(event("a", EngagementType.BROWSER_OPEN, now - 150 * day))
            events.insert(event("a", EngagementType.PEEK, now - 120 * day))
            events.insert(event("b", EngagementType.LIKE, now - 100 * day))
            events.insert(event("b", EngagementType.DETAIL, now - 1 * day))
            events.deleteOlderThan(engagementCutoff(now))
            assertEquals(listOf(EngagementType.BROWSER_OPEN.code), events.eventsFor("a").map { it.type })
            assertEquals(listOf(EngagementType.DETAIL.code), events.eventsFor("b").map { it.type })
            val engine = db.discoverDao().events(engagementCutoff(now)).map { it.bookmarkId to it.type }.toSet()
            assertEquals(setOf("a" to EngagementType.BROWSER_OPEN.code, "b" to EngagementType.DETAIL.code), engine)
        } finally { db.close() }
    }

    @Test fun candidatesReflectProtectionEntitiesAndTrash() = runBlocking {
        val db = memory()
        try {
            db.bookmarkDao().insertAll(listOf(bookmark("liked", isLiked = true), bookmark("collected"), bookmark("plain", listOf("Open AI", "Kotlin"))))
            db.collectionDao().insert(CollectionEntity("c", "C", "", "folder", null, 0, day, day))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("collected", "c"))
            db.trashDao().insert(TrashedBookmarkEntity("trashed", "T", "https://fixture.invalid/t", "", createdAt = day,
                updatedAt = day, note = "", isPrivate = false, trashedAt = day))
            val candidates = db.discoverDao().candidates().associateBy { it.id }
            assertEquals(setOf("liked", "collected", "plain"), candidates.keys)
            assertTrue(candidates.getValue("liked").isProtected)
            assertTrue(candidates.getValue("collected").isProtected)
            assertFalse(candidates.getValue("plain").isProtected)
            assertEquals(listOf("Open AI", "Kotlin"), db.discoverDao().entityRefs().filter { it.bookmarkId == "plain" }.map { it.value })
            assertTrue(db.discoverDao().entityRefs().all { it.skeletonKey.isNotEmpty() })
        } finally { db.close() }
    }

    @Test fun suppressionsExpireAndOrphansArePruned() = runBlocking {
        val db = memory()
        try {
            val dao = db.discoverDao()
            db.bookmarkDao().insertAll(listOf(bookmark("snoozed"), bookmark("never")))
            dao.upsertSuppression(RediscoverySuppressionEntity("snoozed", until = now + day, permanent = false))
            dao.upsertSuppression(RediscoverySuppressionEntity("never", until = null, permanent = true))
            dao.upsertSuppression(RediscoverySuppressionEntity("gone", until = null, permanent = true))
            assertEquals(setOf("snoozed", "never", "gone"), dao.activeSuppressedIds(now).toSet())
            assertEquals(setOf("never", "gone"), dao.activeSuppressedIds(now + 2 * day).toSet())
            assertEquals(1, dao.deleteExpiredSuppressions(now + 2 * day))
            assertEquals(1, dao.deleteOrphanSuppressions())
            assertEquals(listOf("never"), dao.observeSuppressions().first().map { it.bookmarkId })
        } finally { db.close() }
    }

    @Test fun snapshotBookmarksFollowTrash() = runBlocking {
        val db = memory()
        try {
            val dao = db.discoverDao()
            db.bookmarkDao().insertAll(listOf(bookmark("a"), bookmark("b")))
            dao.insertSnapshot(listOf(
                DiscoverSnapshotEntity(5, 1, 0, "a", null, null, null),
                DiscoverSnapshotEntity(5, 5, 0, "b", 1, "40", null)
            ))
            assertEquals(setOf("a", "b"), dao.observeSnapshotBookmarks(5).first().map { it.id }.toSet())
            db.bookmarkDao().deleteById("a")
            assertEquals(listOf("b"), dao.observeSnapshotBookmarks(5).first().map { it.id })
            assertEquals(2, dao.observeSnapshot(5).first().size)
        } finally { db.close() }
    }
}
