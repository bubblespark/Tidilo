package me.bubblespark.tidilo.data.db

import androidx.room.withTransaction
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem

/** Transfers the durable row and enforces capacity atomically, independently of loaded pages. */
internal suspend fun AppDatabase.moveBookmarkToTrash(id: String, now: Long, capacity: Int): Boolean = withTransaction {
    val bookmark = bookmarkDao().getBookmarkById(id) ?: return@withTransaction false
    trashDao().insert(TrashedBookmarkEntity.fromTrashedBookmarkItem(TrashedBookmarkItem(bookmark.toBookmarkItem(), now)))
    bookmarkDao().deleteById(id)
    trashDao().enforceCapacity(capacity)
    true
}
