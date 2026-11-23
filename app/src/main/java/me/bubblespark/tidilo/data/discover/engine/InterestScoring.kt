package me.bubblespark.tidilo.data.discover.engine

import kotlin.math.ln
import kotlin.math.pow
import me.bubblespark.tidilo.data.discover.TopicAxis
import me.bubblespark.tidilo.data.engagement.ENGAGEMENT_RETENTION_MS
import me.bubblespark.tidilo.data.engagement.EngagementType

internal const val DAY_MS = 86_400_000L
private const val HALF_LIFE_DAYS = 30.0
private const val FATIGUE_WINDOW_MS = 14 * DAY_MS
private const val FATIGUE_FACTOR = 0.7
private const val NEVER_OPENED_BOOST = 1.2
private val DORMANCY_SCALE = ln(181.0)

/** Interest weight of one event (§5.3). Negative signals and impressions do not move topics. */
internal fun eventWeight(type: EngagementType): Double = when (type) {
    EngagementType.PEEK -> 1.0
    EngagementType.DETAIL -> 2.0
    EngagementType.BROWSER_OPEN, EngagementType.NOTE_WRITTEN, EngagementType.COLLECTION_ADD -> 3.0
    EngagementType.LIKE -> 4.0
    EngagementType.UNLIKE -> -4.0
    EngagementType.IMPRESSION, EngagementType.DECK_FLICK, EngagementType.SNOOZE, EngagementType.NEVER_AGAIN -> 0.0
}

private const val SAVE_WEIGHT = 1.0

internal fun isOpen(type: EngagementType) = type == EngagementType.DETAIL || type == EngagementType.BROWSER_OPEN

/** Deterministic multiplier in [0.85, 1.15] for [id] under [seed] (SplitMix64). */
internal fun jitter(seed: Long, id: String): Double = 0.85 + 0.3 * unitHash(seed, id)

/** Deterministic value in [0, 1) for [id] under [seed]. */
internal fun unitHash(seed: Long, id: String): Double {
    var z = seed xor (id.hashCode().toLong() * -0x61c8864680b583ebL)
    z += -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    return (z ushr 11).toDouble() / (1L shl 53).toDouble()
}

/**
 * Interest model over one [DiscoverInput] (§5.3): per-axis topic affinity with a 30-day half-life,
 * item affinity, dormancy, and impression fatigue. Built once per computation; every lookup is O(1)
 * or O(entities of the item).
 */
internal class InterestScoring(private val input: DiscoverInput) {
    private val now = input.now
    private val byId = input.candidates.associateBy { it.id }
    private val lastOpenAt = HashMap<String, Long>()
    private val impressions = HashMap<String, MutableList<Long>>()
    private val axes: Map<TopicAxis, Map<String, Double>>

    init {
        val interest = HashMap<String, Double>()
        for (candidate in input.candidates) interest[candidate.id] = SAVE_WEIGHT * decay(candidate.createdAt)
        val retentionCutoff = now - ENGAGEMENT_RETENTION_MS
        for (event in input.events) {
            if (event.bookmarkId !in byId) continue
            if (isOpen(event.type)) lastOpenAt.merge(event.bookmarkId, event.at, ::maxOf)
            if (event.type == EngagementType.IMPRESSION) impressions.getOrPut(event.bookmarkId) { mutableListOf() }.add(event.at)
            val weight = eventWeight(event.type)
            if (weight != 0.0 && event.at >= retentionCutoff) {
                interest.merge(event.bookmarkId, weight * decay(event.at), Double::plus)
            }
        }
        val raw = TopicAxis.entries.associateWith { HashMap<String, Double>() }
        for (candidate in input.candidates) {
            val value = interest.getValue(candidate.id)
            for (key in candidate.entities.mapTo(HashSet()) { it.skeletonKey }) raw.getValue(TopicAxis.ENTITY).merge(key, value, Double::plus)
            candidate.subcategory?.let { raw.getValue(TopicAxis.SUBCATEGORY).merge(it, value, Double::plus) }
            candidate.domain?.let { raw.getValue(TopicAxis.DOMAIN).merge(it, value, Double::plus) }
        }
        axes = raw.mapValues { (_, sums) ->
            val max = sums.values.maxOrNull() ?: 0.0
            if (max <= 0.0) sums.mapValues { 0.0 } else sums.mapValues { (it.value / max).coerceIn(0.0, 1.0) }
        }
    }

    private fun decay(at: Long): Double {
        val ageDays = (now - at).coerceAtLeast(0L).toDouble() / DAY_MS
        return 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    private fun muted(axis: TopicAxis, key: String) = (axis to key) in input.mutes

    /** Normalized affinity (0..1) of one topic, or 0 when muted or unknown. */
    fun topicAffinity(axis: TopicAxis, key: String?): Double =
        if (key == null || muted(axis, key)) 0.0 else axes.getValue(axis)[key] ?: 0.0

    /** Normalized entity affinities for every unmuted entity key. */
    fun entityAffinities(): Map<String, Double> = axes.getValue(TopicAxis.ENTITY).filterKeys { !muted(TopicAxis.ENTITY, it) }

    /** False when every entity of the item is muted (an item without entities stays eligible). */
    fun eligible(id: String): Boolean {
        val entities = byId[id]?.entities ?: return false
        return entities.isEmpty() || entities.any { !muted(TopicAxis.ENTITY, it.skeletonKey) }
    }

    fun affinity(id: String): Double {
        val candidate = byId[id] ?: return 0.0
        val entity = candidate.entities.maxOfOrNull { topicAffinity(TopicAxis.ENTITY, it.skeletonKey) } ?: 0.0
        return 0.5 * entity +
            0.3 * topicAffinity(TopicAxis.SUBCATEGORY, candidate.subcategory) +
            0.2 * topicAffinity(TopicAxis.DOMAIN, candidate.domain)
    }

    /** Display value of the item's highest-affinity unmuted entity. */
    fun topEntity(id: String): String? = byId[id]?.entities
        ?.filter { !muted(TopicAxis.ENTITY, it.skeletonKey) }
        ?.maxByOrNull { topicAffinity(TopicAxis.ENTITY, it.skeletonKey) }?.value

    /** True when a Detail view or browser open was ever recorded (the latest open survives pruning). */
    fun everOpened(id: String): Boolean = id in lastOpenAt

    fun dormancy(id: String): Double {
        val candidate = byId[id] ?: return 0.0
        val lastTouch = candidate.lastInteractedAt ?: candidate.createdAt
        val days = (now - lastTouch).coerceAtLeast(0L).toDouble() / DAY_MS
        val base = (ln(1 + days) / DORMANCY_SCALE).coerceIn(0.0, 1.0)
        return if (everOpened(id)) base else base * NEVER_OPENED_BOOST
    }

    fun fatigue(id: String): Double {
        val since = maxOf(now - FATIGUE_WINDOW_MS, (lastOpenAt[id] ?: Long.MIN_VALUE) + 1)
        val count = impressions[id]?.count { it >= since } ?: 0
        return FATIGUE_FACTOR.pow(count)
    }

    fun score(id: String, seed: Long): Double = affinity(id) * dormancy(id) * fatigue(id) * jitter(seed, id)
}
