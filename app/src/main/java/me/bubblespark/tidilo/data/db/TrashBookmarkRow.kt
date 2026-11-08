package me.bubblespark.tidilo.data.db

import androidx.room.Embedded

data class TrashBookmarkRow(@Embedded val item: TrashedBookmarkEntity, val duplicateInLibrary: Boolean)
