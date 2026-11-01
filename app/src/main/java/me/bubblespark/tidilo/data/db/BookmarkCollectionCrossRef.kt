package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "bookmark_collection_cross_ref",
    primaryKeys = ["bookmarkId", "collectionId"],
    indices = [Index("bookmarkId"), Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmarkId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class BookmarkCollectionCrossRef(val bookmarkId: String, val collectionId: String)
