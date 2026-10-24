package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Soft-deleted subscriptions retain the provenance of existing RSS bookmarks. */
@Entity(tableName = "rss_feeds", indices = [Index(value = ["url"], unique = true)])
data class RssFeedEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val mode: String = "DIRECT",
    val retentionLimit: Int? = 30,
    val deleted: Boolean = false,
    val revision: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastAttemptAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val error: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val acquisitionId: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val acquisitionMobileOverride: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val acquisitionXmlDone: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val acquisitionRequestedAt: Long = 0
)

/** Durable receipts prevent retained-out and rolled-out entries from reappearing. */
@Entity(tableName = "rss_seen_items", primaryKeys = ["feedId", "itemKey"],
    indices = [Index(value = ["url"])])
data class RssSeenItemEntity(val feedId: String, val itemKey: String, val url: String)

/** An outbox keeps failed post-commit image removal retryable. */
@Entity(tableName = "rss_image_cleanup")
data class RssImageCleanupEntity(@PrimaryKey val imageUrl: String, val articleUrl: String)
