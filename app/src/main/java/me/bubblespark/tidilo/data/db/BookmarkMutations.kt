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

/** A failed policy write rolls back each row's original flag without a full-library snapshot. */
internal suspend fun AppDatabase.updateDomainNsfwWithPolicy(
    domain: String, isNsfw: Boolean, persistPolicy: suspend () -> Unit
) = withTransaction {
    bookmarkDao().updateDomainNsfw(domain, isNsfw)
    persistPolicy()
}
