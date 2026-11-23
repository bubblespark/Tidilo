package me.bubblespark.tidilo.data.discover.engine

import java.time.ZoneId
import me.bubblespark.tidilo.data.discover.TopicAxis
import me.bubblespark.tidilo.data.engagement.EngagementType

/** One active bookmark as the engine sees it. [isProtected] = liked or in a collection. */
data class CandidateRow(
    val id: String,
    val createdAt: Long,
    val lastInteractedAt: Long?,
    val isPrivate: Boolean,
    val hasOgImage: Boolean,
    val domain: String?,
    val subcategory: String?,
    val rssFeedId: String?,
    val isProtected: Boolean,
    val entities: List<EntityRef>
)

data class EntityRef(val skeletonKey: String, val value: String)

data class EventRow(val bookmarkId: String, val type: EngagementType, val at: Long)

/** Picks stored on earlier days (today excluded) that rules must not repeat. */
data class SnapshotHistory(
    val heroIds: Set<String> = emptySet(),
    val connectionPairs: Set<Set<String>> = emptySet()
)

/** Everything the engine reads. Built by the repository; pure data so the engine runs on the JVM. */
data class DiscoverInput(
    val now: Long,
    val zone: ZoneId,
    val candidates: List<CandidateRow>,
    val events: List<EventRow>,
    val suppressedIds: Set<String> = emptySet(),
    val mutes: Set<Pair<TopicAxis, String>> = emptySet(),
    val history: SnapshotHistory = SnapshotHistory()
)
