package me.bubblespark.tidilo.data.db

import androidx.room.withTransaction

enum class TrashMutationResult { Restored, Deleted, DuplicateInLibrary, ImportPending, Missing, IdentityConflict }

/** Never replace an active URL owner; the snapshot and its removal commit together. */
internal suspend fun AppDatabase.restoreTrashedBookmark(id: String): TrashMutationResult = withTransaction {
    val snapshot = trashDao().getById(id) ?: return@withTransaction TrashMutationResult.Missing
    if (bookmarkDao().getBookmarkByUrl(snapshot.url) != null) return@withTransaction TrashMutationResult.DuplicateInLibrary
    if (bookmarkDao().getBookmarkById(id) != null) return@withTransaction TrashMutationResult.IdentityConflict
    if (trashDao().hasConflictingImport(snapshot.url)) return@withTransaction TrashMutationResult.ImportPending
    val bookmark = snapshot.toTrashedBookmarkItem().bookmark.copy(isLiked = false)
    bookmarkDao().insertOrUpdate(BookmarkEntity.fromBookmarkItem(bookmark))
    trashDao().deleteById(id)
    TrashMutationResult.Restored
}

/** A stale Trash action cannot remove the restored bookmark or shared image files. */
internal suspend fun AppDatabase.deleteTrashedBookmark(id: String): TrashMutationResult = withTransaction {
    if (trashDao().deleteByIdCounted(id) == 0) TrashMutationResult.Missing else TrashMutationResult.Deleted
}
