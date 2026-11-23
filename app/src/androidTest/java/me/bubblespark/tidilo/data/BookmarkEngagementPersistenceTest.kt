package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.EngagementEventEntity
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkEngagementPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val repository = BookmarkRepository.getInstance(context)

    private suspend fun cleanUp(vararg ids: String) {
        ids.forEach { db.bookmarkDao().deleteById(it); db.trashDao().deleteById(it) }
        db.engagementDao().deleteOrphans()
    }

    @Test fun editsPreserveEngagementAndTrashClearsLike(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        val url = "https://example.com/$id"
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Saved", url, "Summary",
                createdAt = 1, updatedAt = 1, rssFeedId = "feed", isLiked = true, lastInteractedAt = 1234))
            repeat(3) { db.engagementDao().insert(EngagementEventEntity(bookmarkId = id, type = EngagementType.DETAIL.code,
                surface = EngagementSurface.DETAIL.code, at = 100L + it)) }
            fun check(b: BookmarkItem) {
                assertTrue(b.isLiked)
                assertEquals(1234L, b.lastInteractedAt)
            }
            repository.saveNote(id, "Edited")
            repository.updateBookmarkTags(id, Tag.createEmpty(), false)
            repository.applyAiProposal(id, description = "Updated")
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            repository.addBookmarkAndAwait(BookmarkItem(id = "new-$id", title = "Promoted", url = url,
                description = "Promoted", tag = Tag.createEmpty()))
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            repository.addBookmarkAndAwait(BookmarkItem(id = id, title = "Replaced", url = "$url-new",
                description = "Replaced", tag = Tag.createEmpty()))
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            assertTrue(repository.moveToTrash(id))
            val trash = db.trashDao().getAllTrash().single { it.id == id }
            assertTrue(!trash.isLiked)
            check(trash.toTrashedBookmarkItem().bookmark.copy(isLiked = true))
            db.engagementDao().deleteOrphans()
            assertEquals("Events outlive Trash", 3,
                db.engagementDao().eventsFor(id).count { it.type == EngagementType.DETAIL.code })
        } finally {
            cleanUp(id, "new-$id")
        }
    }

    @Test fun recordsTypedEventsWithSurface(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Saved", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            repository.recordDetailView(id, 10, EngagementSurface.DETAIL)
            repository.recordBrowserOpen(id, 11, EngagementSurface.DETAIL)
            repository.recordSummaryPeek(id, 12, EngagementSurface.LIBRARY)
            assertEquals(
                listOf(Triple(EngagementType.DETAIL.code, EngagementSurface.DETAIL.code, 10L),
                    Triple(EngagementType.BROWSER_OPEN.code, EngagementSurface.DETAIL.code, 11L),
                    Triple(EngagementType.PEEK.code, EngagementSurface.LIBRARY.code, 12L)),
                db.engagementDao().eventsFor(id).map { Triple(it.type, it.surface, it.at) })
            assertEquals(12L, db.bookmarkDao().getBookmarkById(id)!!.lastInteractedAt)
        } finally {
            cleanUp(id)
        }
    }

    @Test fun likeThenUnlikeRecordsBothAndKeepsTimestamp(): Unit = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Saved", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            repository.toggleLike(id, 10, EngagementSurface.LIBRARY)
            repository.toggleLike(id, 20, EngagementSurface.LIBRARY)
            assertEquals(listOf(EngagementType.LIKE.code to 10L, EngagementType.UNLIKE.code to 20L),
                db.engagementDao().eventsFor(id).map { it.type to it.at })
            val row = db.bookmarkDao().getBookmarkById(id)!!
            assertFalse(row.isLiked)
            assertEquals(10L, row.lastInteractedAt)
        } finally {
            cleanUp(id)
        }
    }

    @Test fun missingBookmarkWritesNothing(): Unit = runBlocking {
        val id = "missing-${UUID.randomUUID()}"
        repository.recordDetailView(id, 1)
        repository.recordBrowserOpen(id, 1)
        repository.recordSummaryPeek(id, 1)
        repository.toggleLike(id, 1)
        assertTrue(db.engagementDao().eventsFor(id).isEmpty())
    }
}
