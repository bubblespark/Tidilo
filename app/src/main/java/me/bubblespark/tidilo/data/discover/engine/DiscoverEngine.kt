package me.bubblespark.tidilo.data.discover.engine

import java.time.LocalDate
import me.bubblespark.tidilo.data.discover.DiscoverReasonCode
import me.bubblespark.tidilo.data.discover.DiscoverShelf

/**
 * One stored pick. [topicKey] is the entity skeleton key for Rising (group) and Connections.
 * Rising rows use `position = group * 10 + index`.
 */
data class SnapshotRow(
    val shelf: DiscoverShelf,
    val position: Int,
    val bookmarkId: String,
    val reasonCode: DiscoverReasonCode?,
    val reasonArg: String?,
    val topicKey: String? = null
)

/** One "Your map" entry ranked by [weight] (normalized entity affinity). */
data class MapRow(val skeletonKey: String, val value: String, val weight: Double)

internal const val RISING_GROUP_STRIDE = 10

/** Daily snapshot seed: re-deal `n` of [day] never collides with another day's picks. */
fun discoverSeed(day: Long, dealCount: Int): Long = day * 1_000 + dealCount

/**
 * Computes the daily shelves (Today's pick, On this day, Sleeping gems, Rising, Connections) in that
 * order. An item placed on an earlier shelf is not placed again.
 */
fun computeDailySnapshot(input: DiscoverInput, day: Long, seed: Long, privateVisible: Boolean): List<SnapshotRow> {
    val ctx = ShelfContext(input, seed, privateVisible)
    val rows = mutableListOf<SnapshotRow>()
    pickHero(ctx)?.let { hero ->
        val reason = if (!ctx.scoring.everOpened(hero.id)) {
            DiscoverReasonCode.DORMANT_NEVER_OPENED to ctx.daysSince(hero.createdAt).toString()
        } else {
            ctx.scoring.topEntity(hero.id)?.let { DiscoverReasonCode.AFFINITY_ENTITY to it }
        }
        rows += SnapshotRow(DiscoverShelf.HERO, 0, hero.id, reason?.first, reason?.second)
    }
    pickOnThisDay(ctx, LocalDate.ofEpochDay(day)).forEachIndexed { index, (row, period) ->
        rows += SnapshotRow(DiscoverShelf.ON_THIS_DAY, index, row.id, DiscoverReasonCode.ON_THIS_DAY, period.name)
    }
    pickSleepingGems(ctx).forEachIndexed { index, row ->
        rows += SnapshotRow(DiscoverShelf.SLEEPING_GEMS, index, row.id,
            DiscoverReasonCode.DORMANT_NEVER_OPENED, ctx.daysSince(row.createdAt).toString())
    }
    pickRising(ctx).forEachIndexed { group, rising ->
        rising.items.forEachIndexed { index, row ->
            rows += SnapshotRow(DiscoverShelf.RISING, group * RISING_GROUP_STRIDE + index, row.id,
                DiscoverReasonCode.RISING, rising.recentCount.toString(), rising.skeletonKey)
        }
    }
    pickConnection(ctx)?.let { pick ->
        listOf(pick.first, pick.second).forEachIndexed { index, row ->
            rows += SnapshotRow(DiscoverShelf.CONNECTIONS, index, row.id,
                DiscoverReasonCode.CONNECTION, pick.entity.value, pick.entity.skeletonKey)
        }
    }
    return rows
}

/** Computes "Your map" (weekly). Empty below the 30-item threshold. */
fun computeWeeklyMap(input: DiscoverInput, privateVisible: Boolean): List<MapRow> =
    pickMap(ShelfContext(input, seed = 0, privateVisible = privateVisible)).map { MapRow(it.skeletonKey, it.value, it.weight) }

/** Draws the shuffle deck: 30 ids (fewer when the pool is smaller), excluding [flicked]. */
fun drawDeck(input: DiscoverInput, seed: Long, privateVisible: Boolean, flicked: Set<String>): List<String> =
    drawDeckIds(ShelfContext(input, seed, privateVisible), flicked)
