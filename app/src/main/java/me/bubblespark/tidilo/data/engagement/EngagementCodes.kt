package me.bubblespark.tidilo.data.engagement

/**
 * Kind of user interest recorded in `engagement_events`.
 *
 * [code] is persisted. Codes are append-only: never renumber or reuse one.
 */
enum class EngagementType(val code: Int) {
    PEEK(1),
    DETAIL(2),
    BROWSER_OPEN(3),
    NOTE_WRITTEN(4),
    COLLECTION_ADD(5),
    LIKE(6),
    UNLIKE(7),
    IMPRESSION(8),
    DECK_FLICK(9),
    SNOOZE(10),
    NEVER_AGAIN(11);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): EngagementType? = byCode[code]
    }
}

/**
 * Screen where an engagement event happened.
 *
 * [code] is persisted and append-only. [MIGRATED] marks events converted from the former counters.
 * Discover surfaces name the shelf the card sat on; [DISCOVER_PEEK] covers Peek-sheet actions.
 */
enum class EngagementSurface(val code: Int) {
    UNKNOWN(0),
    LIBRARY(1),
    SEARCH(2),
    DETAIL(3),
    MIGRATED(4),
    DISCOVER_HERO(5),
    DISCOVER_ON_THIS_DAY(6),
    DISCOVER_JUST_SAVED(7),
    DISCOVER_DECK(8),
    DISCOVER_SLEEPING_GEMS(9),
    DISCOVER_RISING(10),
    DISCOVER_FRESH_RSS(11),
    DISCOVER_CONNECTIONS(12),
    DISCOVER_PEEK(13);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): EngagementSurface? = byCode[code]
    }
}

/** Engagement events older than this are pruned by library maintenance. */
const val ENGAGEMENT_RETENTION_MS: Long = 90L * 24 * 60 * 60 * 1000

/** Oldest event timestamp kept at [now]. */
fun engagementCutoff(now: Long): Long = now - ENGAGEMENT_RETENTION_MS
