package me.bubblespark.tidilo.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.*
import org.junit.Test

class EntityCanonicalizationRoomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun row(id: String, vararg entities: String) = BookmarkEntity(id, id, "https://example.com/$id", "Description", entities = entities.toList(), createdAt = 1, updatedAt = 1)
    private fun assertStored(db: AppDatabase, id: String, expected: List<String>) = runBlocking {
        assertEquals(expected, db.bookmarkDao().getBookmarkById(id)?.entities)
        db.openHelper.readableDatabase.query("SELECT value, skeletonKey FROM bookmark_entities WHERE bookmarkId = ? ORDER BY position", arrayOf(id)).use { cursor ->
            val values = mutableListOf<String>()
            while (cursor.moveToNext()) {
                values += cursor.getString(0)
                assertTrue(cursor.getString(1).isNotEmpty())
            }
            assertEquals(expected, values)
        }
    }

    @Test fun indexedReplacementRefreshesLiteralSearchCountsAndVisibleAggregates() = runBlocking {
        val db = database()
        try {
            val dao = db.bookmarkDao()
            dao.insertAll(listOf(row("current", "GitHub"), row("hidden", "GitHub").copy(isNsfw = true)))
            val sql = db.openHelper.readableDatabase
            for (query in listOf(
                "SELECT value FROM bookmark_entities WHERE skeletonKey = 'github' ORDER BY bookmarkId, position LIMIT 1",
                "SELECT id, entities FROM bookmarks WHERE id IN (SELECT bookmarkId FROM bookmark_entities WHERE skeletonKey = 'github') AND (NULL IS NULL OR id > NULL) ORDER BY id LIMIT 500"
            )) {
                val plan = sql.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString("; ")
                }
                assertTrue(plan, plan.contains("index_bookmark_entities_skeletonKey"))
            }
            assertEquals(2, dao.observeCount(BookmarkQueries.count("#GitHub", true)).first())
            assertEquals(0, dao.observeCount(BookmarkQueries.count("#git-hub", true)).first())
            assertEquals(listOf(TagCount("GitHub", 2)), dao.tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.ENTITY)).first())
            db.updateBookmarkDetails("current", "", Tag("Tool", "Tech", listOf("GitHub")), false,
                mapOf("github" to "git-hub"), 2)
            for (showNsfw in listOf(false, true)) {
                val expected = if (showNsfw) 2 else 1
                for ((query, count) in listOf("#GitHub" to 0, "#git-hub" to expected)) {
                    val results = BookmarkQueries.results(query, showNsfw)
                    sql.query(results).use { assertEquals(count, it.count) }
                    assertEquals(count, dao.observeCount(BookmarkQueries.count(query, showNsfw)).first())
                }
                val tags = dao.tagCounts(BookmarkQueries.tags(emptySet(), showNsfw, TagKind.ENTITY)).first()
                assertEquals(listOf(TagCount("git-hub", expected)), tags)
                assertEquals(expected, dao.tagCounts(BookmarkQueries.tags(setOf("git-hub"), showNsfw, TagKind.ENTITY)).first().sumOf { it.count })
                assertTrue(dao.tagCounts(BookmarkQueries.tags(setOf("GitHub"), showNsfw, TagKind.ENTITY)).first().isEmpty())
            }
        } finally { db.close() }
    }

    @Test fun explicitReplacementSurvivesRemovalOfLastOccurrenceBeforeStaleDraftSave() = runBlocking {
        val db = database()
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(row("current", "Before", "GitHub", "# C", "C", "After"))
            val draft = requireNotNull(dao.getBookmarkById("current")).toBookmarkItem().tag
            dao.updateNoteAndTag("current", "Concurrent edit", draft.category, draft.subcategory, emptyList(), false, 2)
            assertNull(dao.canonicalEntityValue("github"))
            assertNull(dao.canonicalEntityValue("#c"))

            db.updateBookmarkDetails("current", "Saved draft", draft, false,
                mapOf("github" to "git-hub", "#c" to "#C"), 3)

            assertStored(db, "current", listOf("Before", "git-hub", "#C", "C", "After"))
            assertEquals("Saved draft", dao.getBookmarkById("current")?.note)
        } finally { db.close() }
    }

    @Test fun historicalFourColumnRebuildStillCleansRawValuesExactlyOnce() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertOrUpdate(row("legacy"))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DROP TABLE bookmark_entities")
            sql.execSQL("CREATE TABLE bookmark_entities (bookmarkId TEXT NOT NULL, position INTEGER NOT NULL, value TEXT NOT NULL, searchValue TEXT NOT NULL, PRIMARY KEY(bookmarkId, position))")
            sql.execSQL("UPDATE bookmarks SET entities = ? WHERE id = 'legacy'", arrayOf("[\"##C\",\"C\"]"))
            rebuildBookmarkDerivedData(sql)
            sql.query("SELECT value FROM bookmark_entities ORDER BY position").use { cursor ->
                val values = mutableListOf<String>()
                while (cursor.moveToNext()) values += cursor.getString(0)
                assertEquals(listOf("#C", "C"), values)
            }
        } finally { db.close() }
    }

    @Test fun sameUrlReplacementResolvesBeforeDeletingItsOnlyOccurrence() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertOrUpdate(row("old", "GitHub"))
            db.bookmarkDao().insertReplacingUrl(row("new", "git-hub").copy(url = "https://example.com/old"))
            assertNull(db.bookmarkDao().getBookmarkById("old"))
            assertStored(db, "new", listOf("GitHub"))
        } finally { db.close() }
    }

    @Test fun detailSaveReplacesHiddenRowsAndPreservesUnrelatedFieldsAndOrder() = runBlocking {
        val db = database()
        try {
            val hidden = row("hidden", "Before", "GitHub", "After").copy(isNsfw = true, note = "Keep note", rssFeedId = "feed", rssPublishedAt = 77)
            db.bookmarkDao().insertAll(listOf(row("current", "GitHub", "# C", "C"), hidden, row("unrelated", "Other")))
            db.updateBookmarkDetails("current", "Saved note", Tag("Tool", "Tech", listOf("git-hub", "GIT_HUB", "# C", "C")), false,
                mapOf("github" to "git-hub", "#c" to requireNotNull(me.bubblespark.tidilo.data.EntityTagPolicy.clean("##C"))), 5)
            assertStored(db, "current", listOf("git-hub", "#C", "C"))
            assertStored(db, "hidden", listOf("Before", "git-hub", "After"))
            assertEquals(hidden.copy(entities = listOf("Before", "git-hub", "After"), updatedAt = 5).withSearchFields(), db.bookmarkDao().getBookmarkById("hidden"))
            assertEquals(1L, db.bookmarkDao().getBookmarkById("unrelated")?.updatedAt)
            assertEquals("Saved note", db.bookmarkDao().getBookmarkById("current")?.note)
        } finally { db.close() }
    }

    @Test fun failingOuterTransactionRollsBackEveryReplacementAndOccurrence() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertAll(listOf(row("current", "GitHub"), row("hidden", "GitHub").copy(isNsfw = true)))
            try {
                db.withTransaction {
                    db.updateBookmarkDetails("current", "Changed", Tag("Tool", "Tech", listOf("GitHub")), true, mapOf("github" to "git-hub"), 9)
                    error("Forced failure after replacement")
                }
                fail("Expected failure")
            } catch (expected: IllegalStateException) {
                assertEquals("Forced failure after replacement", expected.message)
            }
            assertStored(db, "current", listOf("GitHub"))
            assertStored(db, "hidden", listOf("GitHub"))
            assertEquals("", db.bookmarkDao().getBookmarkById("current")?.note)
            assertEquals(1L, db.bookmarkDao().getBookmarkById("hidden")?.updatedAt)
        } finally { db.close() }
    }

    @Test fun deletedTargetAndInvalidReplacementCannotRenameOtherRows() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertOrUpdate(row("kept", "GitHub"))
            try {
                db.updateBookmarkDetails("deleted", "", Tag(), false, mapOf("github" to "git-hub"), 4)
                fail("Missing target must fail")
            } catch (_: IllegalArgumentException) { }
            try {
                db.updateBookmarkDetails("kept", "", Tag(), false, mapOf("github" to "Different"), 4)
                fail("Mismatched key must fail")
            } catch (_: IllegalArgumentException) { }
            assertStored(db, "kept", listOf("GitHub"))
        } finally { db.close() }
    }

    @Test fun staleEditorResolvesLatestSpellingAndLastExplicitChoiceWins() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertAll(listOf(row("first", "GitHub"), row("stale", "GitHub")))
            val draft = requireNotNull(db.bookmarkDao().getBookmarkById("stale")).toBookmarkItem().tag
            db.updateBookmarkDetails("first", "", draft, false, mapOf("github" to "git-hub"), 2)
            db.updateBookmarkDetails("stale", "Stale saved", draft, false, emptyMap(), 3)
            assertStored(db, "stale", listOf("git-hub"))
            val choices = linkedMapOf("github" to "Git Hub").apply { put("github", "GIT_HUB") }
            db.updateBookmarkDetails("stale", "", draft, false, choices, 4)
            assertStored(db, "first", listOf("GIT_HUB"))
            assertStored(db, "stale", listOf("GIT_HUB"))
        } finally { db.close() }
    }

    @Test fun importBeforeSaveAndSaveBeforeImportSerializeAtTransactionBarrier() = runBlocking {
        for (importFirst in listOf(true, false)) {
            val db = database()
            try {
                db.bookmarkDao().insertOrUpdate(row("current", "GitHub"))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val secondStarted = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) {
                    db.withTransaction {
                        if (importFirst) db.bookmarkDao().insertOrUpdate(row("import", "git.hub"))
                        else db.updateBookmarkDetails("current", "", Tag("Tool", "Tech", listOf("GitHub")), false, mapOf("github" to "git-hub"), 2)
                        entered.complete(Unit)
                        release.await()
                    }
                }
                entered.await()
                val second = async(Dispatchers.IO) {
                    secondStarted.complete(Unit)
                    if (importFirst) db.updateBookmarkDetails("current", "", Tag("Tool", "Tech", listOf("GitHub")), false, mapOf("github" to "git-hub"), 3)
                    else db.bookmarkDao().insertOrUpdate(row("import", "git.hub"))
                }
                secondStarted.await()
                assertFalse(second.isCompleted)
                release.complete(Unit)
                first.await()
                second.await()
                assertStored(db, "current", listOf("git-hub"))
                assertStored(db, "import", listOf("git-hub"))
            } finally { db.close() }
        }
    }

    @Test fun bulkReplacementPagesBeyondSqliteBindLimit() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertAll((0..1001).map { row("id$it", "GitHub", "Other") })
            db.updateBookmarkDetails("id0", "", Tag("Tool", "Tech", listOf("GitHub", "Other")), false, mapOf("github" to "git-hub"), 8)
            for (id in listOf("id0", "id499", "id500", "id999", "id1001")) assertStored(db, id, listOf("git-hub", "Other"))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM bookmark_entities WHERE skeletonKey = 'github' AND value = 'git-hub'").use {
                assertTrue(it.moveToFirst()); assertEquals(1002, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun insertsAndAiReviewReuseExistingSpellingAndPreserveHashOnMetadataUpdate() = runBlocking {
        val db = database()
        try {
            val dao = db.bookmarkDao()
            dao.insertOrUpdate(row("first", "GitHub", "#C", "C"))
            dao.insertOrUpdate(row("second", "git-hub", "GIT_HUB", "Other"))
            assertStored(db, "second", listOf("GitHub", "Other"))
            dao.updateFields("second", "AI summary", "Tool", "Tech", listOf("git.hub", "New"), 2)
            assertStored(db, "second", listOf("GitHub", "New"))
            val item = requireNotNull(dao.getBookmarkById("first")).toBookmarkItem()
            dao.update(BookmarkEntity.fromBookmarkItem(item.copy(note = "Edited")))
            assertStored(db, "first", listOf("GitHub", "#C", "C"))
        } finally { db.close() }
    }

    @Test fun batchesResolveEncounterOrderAcrossChunksBeforeApplyingLimit() = runBlocking {
        val db = database()
        try {
            db.bookmarkDao().insertAll(listOf(row("first", "GitHub")) + (1..499).map { row("filler$it") } + row("last", *(List(12) { "git-hub" } + (1..10).map { "Entity $it" }).toTypedArray()))
            assertStored(db, "last", listOf("GitHub") + (1..9).map { "Entity $it" })
        } finally { db.close() }
    }
}
