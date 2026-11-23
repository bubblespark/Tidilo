package me.bubblespark.tidilo.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One daily Discover pick. [shelf] and [reasonCode] hold `DiscoverShelf.code` / `DiscoverReasonCode.code`.
 * [topicKey] is the entity skeleton key of a Rising group or a Connection. No foreign key: rows age out
 * through maintenance, and reads join `bookmarks` so Trash and purge take effect at once.
 */
@Entity(
    tableName = "discover_snapshot",
    primaryKeys = ["day", "shelf", "position"],
    indices = [Index("bookmarkId")]
)
data class DiscoverSnapshotEntity(
    val day: Long,
    val shelf: Int,
    val position: Int,
    val bookmarkId: String,
    val reasonCode: Int?,
    val reasonArg: String?,
    val topicKey: String?
)

/** One "Your map" bubble of the week starting on epoch day [week]. */
@Entity(tableName = "discover_map", primaryKeys = ["week", "position"])
data class DiscoverMapEntity(
    val week: Long,
    val position: Int,
    val skeletonKey: String,
    val value: String,
    val weight: Double
)

/**
 * When a snapshot was computed and for which inputs. [scope] is [SCOPE_DAILY] (keyed by Discover day)
 * or [SCOPE_WEEKLY_MAP] (keyed by week start). [dealCount] is the number of re-deals that day.
 */
@Entity(tableName = "discover_snapshot_meta", primaryKeys = ["scope", "day"])
data class DiscoverSnapshotMetaEntity(
    val scope: Int,
    val day: Long,
    val dealCount: Int,
    val privateVisible: Boolean,
    val computedAt: Long
) {
    companion object {
        const val SCOPE_DAILY = 0
        const val SCOPE_WEEKLY_MAP = 1
    }
}

/** Keeps a bookmark out of rediscovery until [until], or forever when [permanent]. */
@Entity(tableName = "rediscovery_suppressions")
data class RediscoverySuppressionEntity(
    @PrimaryKey val bookmarkId: String,
    val until: Long?,
    val permanent: Boolean
)

/** A topic the user muted for rediscovery. [axis] holds `TopicAxis.code`. */
@Entity(tableName = "rediscovery_topic_mutes", primaryKeys = ["axis", "key"])
data class RediscoveryTopicMuteEntity(
    val axis: Int,
    val key: String
)

data class DiscoverCandidateProjection(
    val id: String,
    val createdAt: Long,
    val lastInteractedAt: Long?,
    val isPrivate: Boolean,
    val hasOgImage: Boolean,
    val domain: String?,
    val subcategory: String?,
    val rssFeedId: String?,
    val isProtected: Boolean
)

data class DiscoverEntityProjection(val bookmarkId: String, val skeletonKey: String, val value: String)

data class DiscoverEventProjection(val bookmarkId: String, val type: Int, val at: Long)

/** An unread RSS bookmark and whether retention protects it (liked or collected). */
data class FreshRssRow(
    @Embedded val bookmark: BookmarkEntity,
    @ColumnInfo(name = "isProtected") val isProtected: Boolean
)
