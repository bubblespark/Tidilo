package me.bubblespark.tidilo.data.discover

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem

/** Discover shelves in display order. [code] is persisted in `discover_snapshot`; append-only. */
enum class DiscoverShelf(val code: Int) {
    HERO(1),
    ON_THIS_DAY(2),
    JUST_SAVED(3),
    DECK(4),
    SLEEPING_GEMS(5),
    RISING(6),
    FRESH_RSS(7),
    CONNECTIONS(8),
    MAP(9);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): DiscoverShelf? = byCode[code]
    }
}

/** Rule that explains a pick. [code] is persisted; append-only. */
enum class DiscoverReasonCode(val code: Int) {
    DORMANT_NEVER_OPENED(1),
    AFFINITY_ENTITY(2),
    ON_THIS_DAY(3),
    RSS_EXPIRING(4),
    CONNECTION(5),
    RISING(6);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): DiscoverReasonCode? = byCode[code]
    }
}

/** Topic axis used by affinity and mutes. [code] is persisted; append-only. */
enum class TopicAxis(val code: Int) {
    ENTITY(1),
    SUBCATEGORY(2),
    DOMAIN(3);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): TopicAxis? = byCode[code]
    }
}

/** Distance back to the matching calendar day for "On this day". Persisted by [name]. */
enum class OnThisDayPeriod { ONE_YEAR, SIX_MONTHS, ONE_MONTH }

/** Typed reason label; the UI formats it with localized plurals. */
sealed interface DiscoverReason {
    data class DormantNeverOpened(val days: Int) : DiscoverReason
    data class AffinityEntity(val entity: String) : DiscoverReason
    data class OnThisDay(val period: OnThisDayPeriod) : DiscoverReason
    data class RssExpiring(val days: Int) : DiscoverReason
    data class Connection(val entity: String) : DiscoverReason
    data class Rising(val count: Int) : DiscoverReason
}

data class DiscoverPick(val item: BookmarkItem, val reason: DiscoverReason?)

data class RisingGroup(val entity: String, val skeletonKey: String, val picks: List<DiscoverPick>)

/** One "Your map" bubble; [weight] is the entity's normalized affinity (0..1). */
data class MapBubble(val entity: String, val skeletonKey: String, val weight: Float)

/** The day's Discover picks after read-time filtering (Trash, suppressions, visibility, mutes). */
data class DiscoverSnapshot(
    val day: Long,
    val libraryCount: Int,
    val hero: DiscoverPick?,
    val onThisDay: List<DiscoverPick>,
    val sleepingGems: List<DiscoverPick>,
    val rising: List<RisingGroup>,
    val connection: Pair<DiscoverPick, DiscoverPick>?,
    val connectionEntity: String?,
    val map: List<MapBubble>
)

/** Hour at which the Discover day rolls over. */
const val DISCOVER_DAY_START_HOUR = 4

/**
 * Epoch day of the Discover day containing [now]; the day starts at 04:00 local wall-clock time.
 * Compares the local hour rather than subtracting elapsed hours, so a DST jump before 04:00 does not move the boundary.
 */
fun discoverDay(now: Long, zone: ZoneId): Long {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    val date = if (local.hour < DISCOVER_DAY_START_HOUR) local.toLocalDate().minusDays(1) else local.toLocalDate()
    return date.toEpochDay()
}

/** Epoch millis at which Discover day [day] starts. */
fun discoverDayStart(day: Long, zone: ZoneId): Long =
    LocalDate.ofEpochDay(day).atTime(DISCOVER_DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

/** Epoch day of the Monday that starts the week containing [day]. */
fun weekStartDay(day: Long): Long =
    LocalDate.ofEpochDay(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
