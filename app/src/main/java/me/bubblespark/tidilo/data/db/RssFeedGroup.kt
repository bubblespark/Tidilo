package me.bubblespark.tidilo.data.db

import androidx.room.Embedded

/** Includes saved articles of disabled and soft-deleted subscriptions. */
data class RssFeedGroup(@Embedded val feed: RssFeedEntity, val count: Int)
