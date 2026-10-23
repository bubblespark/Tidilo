package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import org.junit.Assert.*
import org.junit.Test

class RssSourceMappingTest {
    @Test fun bookmarkAndTrashRoundTripsPreserveFeedOwnershipAndPublication() {
        val item = BookmarkItem("id", "Title", "https://example.com/post", "Body", Tag.createEmpty(),
            isAiSkipped = true, rssFeedId = "feed", rssPublishedAt = 42)
        val row = BookmarkEntity.fromBookmarkItem(item)
        assertEquals("feed", row.rssFeedId)
        assertEquals(42L, row.rssPublishedAt)
        assertEquals(item, row.toBookmarkItem())
        val trash = TrashedBookmarkItem(item, 90)
        val trashRow = TrashedBookmarkEntity.fromTrashedBookmarkItem(trash)
        assertEquals("feed", trashRow.rssFeedId)
        assertEquals(42L, trashRow.rssPublishedAt)
        assertEquals(trash, trashRow.toTrashedBookmarkItem())
    }
    @Test fun importTaskRoundTripPreservesFeedOwnershipAndPublication() {
        val task = ImportTask(url = "https://example.com/post", title = "Post", status = TaskStatus.READY,
            statusText = "Ready", rssFeedId = "feed", rssPublishedAt = 42)
        val row = ImportTaskEntity.fromImportTask(task)
        assertEquals("feed", row.rssFeedId)
        assertEquals(42L, row.rssPublishedAt)
        assertEquals(task, row.toImportTask())
    }
}
