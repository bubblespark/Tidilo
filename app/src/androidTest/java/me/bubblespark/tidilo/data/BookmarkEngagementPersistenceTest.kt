package me.bubblespark.tidilo.data

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkEngagementPersistenceTest {
    @Test fun editsPromotionReplacementAndTrashPreserveEngagement(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val repository = BookmarkRepository.getInstance(context)
        val id = UUID.randomUUID().toString()
        val url = "https://example.com/$id"
        try {
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Saved", url, "Summary",
                createdAt = 1, updatedAt = 1, rssFeedId = "feed",
                isLiked = true, browserOpenCount = 2, detailViewCount = 3,
                summaryPeekCount = 4, lastInteractedAt = 1234))
            fun check(b: BookmarkItem) {
                assertTrue(b.isLiked)
                assertEquals(2, b.browserOpenCount)
                assertEquals(3, b.detailViewCount)
                assertEquals(4, b.summaryPeekCount)
                assertEquals(1234L, b.lastInteractedAt)
            }
            repository.updateBookmarkDetails(id, "Edited", Tag.createEmpty(), false)
            repository.updateBookmarkFields(id, description = "Updated")
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            repository.addBookmarkAndAwait(BookmarkItem(id = "new-$id", title = "Promoted", url = url,
                description = "Promoted", tag = Tag.createEmpty()))
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            repository.addBookmarkAndAwait(BookmarkItem(id = id, title = "Replaced", url = "$url-new",
                description = "Replaced", tag = Tag.createEmpty()))
            check(db.bookmarkDao().getBookmarkById(id)!!.toBookmarkItem())
            assertTrue(repository.moveToTrash(id))
            check(db.trashDao().getAllTrash().single { it.id == id }.toTrashedBookmarkItem().bookmark)
        } finally {
            db.bookmarkDao().deleteById(id)
            db.trashDao().deleteById(id)
        }
    }
}
