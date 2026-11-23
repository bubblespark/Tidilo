package me.bubblespark.tidilo.data.discover

import java.time.LocalDateTime
import java.time.ZoneId
import me.bubblespark.tidilo.data.discover.engine.CandidateRow
import me.bubblespark.tidilo.data.discover.engine.DiscoverInput
import me.bubblespark.tidilo.data.discover.engine.EntityRef
import me.bubblespark.tidilo.data.discover.engine.EventRow
import me.bubblespark.tidilo.data.engagement.EngagementType

internal const val DAY = 86_400_000L
internal val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

/** 2026-11-23 12:00 in Tokyo. */
internal val NOW: Long = LocalDateTime.of(2026, 11, 23, 12, 0).atZone(TOKYO).toInstant().toEpochMilli()

internal fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    LocalDateTime.of(year, month, day, hour, 0).atZone(TOKYO).toInstant().toEpochMilli()

internal fun candidate(
    id: String,
    createdAt: Long = NOW - 100 * DAY,
    lastInteractedAt: Long? = null,
    isPrivate: Boolean = false,
    hasOgImage: Boolean = true,
    domain: String? = "example.com",
    subcategory: String? = null,
    rssFeedId: String? = null,
    isProtected: Boolean = false,
    entities: List<String> = emptyList()
) = CandidateRow(id, createdAt, lastInteractedAt, isPrivate, hasOgImage, domain, subcategory, rssFeedId, isProtected,
    entities.map { EntityRef(it.lowercase(), it) })

internal fun event(id: String, type: EngagementType, at: Long) = EventRow(id, type, at)

internal fun input(
    candidates: List<CandidateRow>,
    events: List<EventRow> = emptyList(),
    now: Long = NOW,
    configure: DiscoverInput.() -> DiscoverInput = { this }
) = DiscoverInput(now, TOKYO, candidates, events).configure()
