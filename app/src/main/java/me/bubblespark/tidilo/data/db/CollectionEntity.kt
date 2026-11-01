package me.bubblespark.tidilo.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val presetIconId: String?,
    val customSymbol: String?,
    val colorIndex: Int,
    val createdAt: Long,
    val updatedAt: Long
)

data class CollectionWithCount(@Embedded val collection: CollectionEntity, val count: Int)
