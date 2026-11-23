package me.bubblespark.tidilo.data.discover.engine

import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.discover.TopicAxis

internal const val HERO_MIN_LIBRARY = 50
internal const val MAP_MIN_LIBRARY = 30
internal const val ON_THIS_DAY_FALLBACK_DAYS = 1L
internal const val SLEEPING_MIN_AGE_DAYS = 30L
internal const val SLEEPING_COUNT = 5
internal const val RISING_RECENT_DAYS = 14L
internal const val RISING_BASELINE_DAYS = 60L
internal const val RISING_MIN_RECENT = 3
internal const val RISING_MAX_GROUPS = 2
internal const val RISING_ITEMS = 6
internal const val CONNECTION_MIN_DF = 2
internal const val CONNECTION_MAX_DF = 5
internal const val MAP_SIZE = 20
internal const val DECK_SIZE = 30
internal const val DECK_WEIGHTED = 6

/** Shared state for one computation: visible candidates, scores, and the ids already placed. */
internal class ShelfContext(val input: DiscoverInput, val seed: Long, privateVisible: Boolean) {
    val visible: List<CandidateRow> = input.candidates.filter { privateVisible || !it.isPrivate }
    val scoring = InterestScoring(input.copy(candidates = visible))
    val eligible: List<CandidateRow> = visible.filter { it.id !in input.suppressedIds && scoring.eligible(it.id) }
    val eligibleIds: Set<String> = eligible.mapTo(HashSet()) { it.id }
    val used = HashSet<String>()
    private val scores = HashMap<String, Double>()

    fun score(id: String): Double = scores.getOrPut(id) { scoring.score(id, seed) }
    fun available(): Sequence<CandidateRow> = eligible.asSequence().filter { it.id !in used }
    fun daysSince(at: Long): Int = ((input.now - at).coerceAtLeast(0L) / DAY_MS).toInt()
    fun localDate(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(input.zone).toLocalDate()
}

private val byScoreThenId = compareByDescending<Pair<CandidateRow, Double>> { it.second }.thenBy { it.first.id }

/** Today's pick: top rediscovery score with an image or poster; never Private; not a recent hero. */
internal fun pickHero(ctx: ShelfContext): CandidateRow? {
    if (ctx.visible.size < HERO_MIN_LIBRARY) return null
    return ctx.available()
        .filter { !it.isPrivate && (it.hasOgImage || it.domain != null) && it.id !in ctx.input.history.heroIds }
        .map { it to ctx.score(it.id) }
        .sortedWith(byScoreThenId).firstOrNull()?.first
        ?.also { ctx.used += it.id }
}

/** At most one item per period, saved on the matching calendar day of [today]. */
internal fun pickOnThisDay(ctx: ShelfContext, today: LocalDate): List<Pair<CandidateRow, OnThisDayPeriod>> {
    val byDate = ctx.available().groupBy { ctx.localDate(it.createdAt) }
    fun best(date: LocalDate) = byDate[date].orEmpty().filter { it.id !in ctx.used }
        .map { it to ctx.score(it.id) }.sortedWith(byScoreThenId).firstOrNull()?.first
    val picks = mutableListOf<Pair<CandidateRow, OnThisDayPeriod>>()
    for (period in OnThisDayPeriod.entries) {
        val target = when (period) {
            OnThisDayPeriod.ONE_YEAR -> today.minusYears(1)
            OnThisDayPeriod.SIX_MONTHS -> today.minusMonths(6)
            OnThisDayPeriod.ONE_MONTH -> today.minusMonths(1)
        }
        val pick = best(target) ?: if (period == OnThisDayPeriod.ONE_YEAR) {
            listOfNotNull(best(target.minusDays(ON_THIS_DAY_FALLBACK_DAYS)), best(target.plusDays(ON_THIS_DAY_FALLBACK_DAYS)))
                .map { it to ctx.score(it.id) }.sortedWith(byScoreThenId).firstOrNull()?.first
        } else null
        if (pick != null) {
            ctx.used += pick.id
            picks += pick to period
        }
    }
    return picks
}

/** Saved at least 30 days ago and never opened; top 5 by affinity × daily jitter. */
internal fun pickSleepingGems(ctx: ShelfContext): List<CandidateRow> =
    ctx.available()
        .filter { ctx.input.now - it.createdAt >= SLEEPING_MIN_AGE_DAYS * DAY_MS && !ctx.scoring.everOpened(it.id) }
        .map { it to ctx.scoring.affinity(it.id) * jitter(ctx.seed, it.id) }
        .sortedWith(byScoreThenId).take(SLEEPING_COUNT).map { it.first }.toList()
        .also { picks -> ctx.used += picks.map { it.id } }

internal data class RisingPick(val skeletonKey: String, val recentCount: Int, val items: List<CandidateRow>)

/**
 * Entities whose manual saves surged: ≥3 in the last 14 days and a weekly rate at least twice the
 * rate of the 60 days before that window. Top 2 by ratio, 6 newest items each.
 */
internal fun pickRising(ctx: ShelfContext): List<RisingPick> {
    val now = ctx.input.now
    val recentStart = now - RISING_RECENT_DAYS * DAY_MS
    val baselineStart = recentStart - RISING_BASELINE_DAYS * DAY_MS
    val recent = HashMap<String, Int>()
    val baseline = HashMap<String, Int>()
    for (row in ctx.visible) {
        if (row.rssFeedId != null) continue
        val target = when {
            row.createdAt > recentStart -> recent
            row.createdAt > baselineStart -> baseline
            else -> continue
        }
        for (key in row.entities.mapTo(HashSet()) { it.skeletonKey }) target.merge(key, 1, Int::plus)
    }
    val recentWeeks = RISING_RECENT_DAYS / 7.0
    val baselineWeeks = RISING_BASELINE_DAYS / 7.0
    val ranked = recent.filter { (key, count) ->
        count >= RISING_MIN_RECENT && (TopicAxis.ENTITY to key) !in ctx.input.mutes &&
            count / recentWeeks >= 2 * ((baseline[key] ?: 0) / baselineWeeks)
    }.entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> { (key, count) ->
            val base = baseline[key] ?: 0
            if (base == 0) Double.MAX_VALUE else (count / recentWeeks) / (base / baselineWeeks)
        }.thenByDescending { it.value }.thenBy { it.key }
    )
    val groups = mutableListOf<RisingPick>()
    for ((key, count) in ranked) {
        if (groups.size == RISING_MAX_GROUPS) break
        val items = ctx.available().filter { it.rssFeedId == null && it.entities.any { e -> e.skeletonKey == key } }
            .sortedWith(compareByDescending<CandidateRow> { it.createdAt }.thenBy { it.id })
            .take(RISING_ITEMS).toList()
        if (items.isEmpty()) continue
        ctx.used += items.map { it.id }
        groups += RisingPick(key, count, items)
    }
    return groups
}

internal data class ConnectionPick(val entity: EntityRef, val first: CandidateRow, val second: CandidateRow)

/** One pair sharing a rare entity (document frequency 2–5), not shown together in the last 30 days. */
internal fun pickConnection(ctx: ShelfContext): ConnectionPick? {
    val df = HashMap<String, MutableList<CandidateRow>>()
    for (row in ctx.visible) for (key in row.entities.mapTo(HashSet()) { it.skeletonKey }) df.getOrPut(key) { mutableListOf() }.add(row)
    val keys = df.filter { (key, rows) ->
        rows.size in CONNECTION_MIN_DF..CONNECTION_MAX_DF && (TopicAxis.ENTITY to key) !in ctx.input.mutes
    }.entries.sortedWith(compareBy<Map.Entry<String, MutableList<CandidateRow>>> { it.value.size }.thenBy { it.key })
    for ((key, rows) in keys) {
        val pool = rows.filter { row -> row.id !in ctx.used && row.id in ctx.eligibleIds }.sortedBy { it.id }
        var best: Pair<Pair<CandidateRow, CandidateRow>, Double>? = null
        for (i in pool.indices) for (j in i + 1 until pool.size) {
            val pair = setOf(pool[i].id, pool[j].id)
            if (pair in ctx.input.history.connectionPairs) continue
            val sum = ctx.score(pool[i].id) + ctx.score(pool[j].id)
            if (best == null || sum > best.second) best = (pool[i] to pool[j]) to sum
        }
        val (first, second) = best?.first ?: continue
        ctx.used += listOf(first.id, second.id)
        val entity = first.entities.first { it.skeletonKey == key }
        return ConnectionPick(entity, first, second)
    }
    return null
}

internal data class MapEntry(val skeletonKey: String, val value: String, val weight: Double)

/** Top 20 unmuted entities by affinity; display value is the most common spelling. */
internal fun pickMap(ctx: ShelfContext): List<MapEntry> {
    if (ctx.visible.size < MAP_MIN_LIBRARY) return emptyList()
    val spellings = HashMap<String, HashMap<String, Int>>()
    for (row in ctx.visible) for (entity in row.entities) spellings.getOrPut(entity.skeletonKey) { HashMap() }.merge(entity.value, 1, Int::plus)
    return ctx.scoring.entityAffinities().entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .take(MAP_SIZE)
        .map { (key, weight) ->
            val value = spellings[key].orEmpty().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
            MapEntry(key, value, weight)
        }
}

/** 24 uniform + 6 affinity-weighted cards, excluding recently flicked ones, shuffled together. */
internal fun drawDeckIds(ctx: ShelfContext, flicked: Set<String>): List<String> {
    val random = Random(ctx.seed)
    val pool = ctx.eligible.filter { it.id !in flicked }.sortedBy { it.id }
    if (pool.size <= DECK_SIZE) return pool.map { it.id }.shuffled(random)
    val uniform = pool.shuffled(random).take(DECK_SIZE - DECK_WEIGHTED)
    val taken = uniform.mapTo(HashSet()) { it.id }
    // Weighted sampling without replacement (Efraimidis–Spirakis): key = u^(1/w).
    val weighted = pool.filter { it.id !in taken }
        .map { it to Math.pow(random.nextDouble(), 1.0 / maxOf(ctx.scoring.affinity(it.id), 1e-6)) }
        .sortedByDescending { it.second }
        .take(DECK_WEIGHTED).map { it.first }
    return (uniform + weighted).map { it.id }.shuffled(random)
}
