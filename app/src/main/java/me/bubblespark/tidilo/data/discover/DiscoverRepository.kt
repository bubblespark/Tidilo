package me.bubblespark.tidilo.data.discover

import android.content.Context
import androidx.room.withTransaction
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.TransactionRunner
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.DiscoverMapEntity
import me.bubblespark.tidilo.data.db.DiscoverSnapshotEntity
import me.bubblespark.tidilo.data.db.DiscoverSnapshotMetaEntity
import me.bubblespark.tidilo.data.db.DiscoverSnapshotMetaEntity.Companion.SCOPE_DAILY
import me.bubblespark.tidilo.data.db.DiscoverSnapshotMetaEntity.Companion.SCOPE_WEEKLY_MAP
import me.bubblespark.tidilo.data.db.EngagementEventEntity
import me.bubblespark.tidilo.data.db.FreshRssRow
import me.bubblespark.tidilo.data.db.RediscoverySuppressionEntity
import me.bubblespark.tidilo.data.db.RediscoveryTopicMuteEntity
import me.bubblespark.tidilo.data.discover.engine.CandidateRow
import me.bubblespark.tidilo.data.discover.engine.DiscoverInput
import me.bubblespark.tidilo.data.discover.engine.EntityRef
import me.bubblespark.tidilo.data.discover.engine.EventRow
import me.bubblespark.tidilo.data.discover.engine.RISING_GROUP_STRIDE
import me.bubblespark.tidilo.data.discover.engine.SnapshotHistory
import me.bubblespark.tidilo.data.discover.engine.computeDailySnapshot
import me.bubblespark.tidilo.data.discover.engine.computeWeeklyMap
import me.bubblespark.tidilo.data.discover.engine.discoverSeed
import me.bubblespark.tidilo.data.discover.engine.drawDeck
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.data.engagement.EngagementType
import me.bubblespark.tidilo.data.engagement.engagementCutoff
import me.bubblespark.tidilo.data.entitySkeletonKey
import me.bubblespark.tidilo.data.maintenance.RETENTION_DAY_MS
import me.bubblespark.tidilo.data.maintenance.RSS_UNREAD_DAYS

private const val HISTORY_DAYS = 30L
private const val SNOOZE_MS = 30 * RETENTION_DAY_MS
private const val DECK_COOLDOWN_MS = 7 * RETENTION_DAY_MS
private const val JUST_SAVED_WINDOW_MS = 48 * 60 * 60 * 1000L
private const val JUST_SAVED_LIMIT = 12
private const val FRESH_RSS_LIMIT = 10
private const val RSS_EXPIRY_LABEL_DAYS = 3

/**
 * Serves Discover (design spec §4–§5): one snapshot per Discover day for the daily shelves, one map per
 * week, live "Just saved" and "Fresh from RSS" shelves, the shuffle deck, and the rediscovery signals
 * (impressions, flicks, snooze, never again, topic mutes).
 *
 * Snapshot writers ([ensureToday], [reDeal], [resetHistory]) are serialized by one mutex; each replaces
 * its rows and meta in a single transaction, so a failure keeps the previous snapshot.
 */
class DiscoverRepository internal constructor(
    private val database: AppDatabase,
    private val transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction { block() }
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val privateVisible: StateFlow<Boolean> = AppConfig.isPrivateContentVisible,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default
) {
    private val dao = database.discoverDao()
    private val mutex = Mutex()
    private val currentDay = MutableStateFlow<Long?>(null)

    /** Computes today's snapshot (and this week's map) when missing or computed for another Private visibility. */
    suspend fun ensureToday() = mutex.withLock { refresh(reDeal = false) }

    /** Re-deals today's daily shelves with the next seed. "Just saved", "Fresh from RSS", and the map are unchanged. */
    suspend fun reDeal() = mutex.withLock { refresh(reDeal = true) }

    private suspend fun refresh(reDeal: Boolean) {
        val now = nowMillis()
        val zone = zone()
        val day = discoverDay(now, zone)
        val week = weekStartDay(day)
        val visible = privateVisible.value
        val daily = dao.meta(SCOPE_DAILY, day)
        val weekly = dao.meta(SCOPE_WEEKLY_MAP, week)
        val needDaily = reDeal || daily == null || daily.privateVisible != visible
        val needWeekly = weekly == null || weekly.privateVisible != visible
        if (needDaily || needWeekly) {
            val input = loadInput(now, zone, day)
            if (needDaily) {
                val dealCount = (daily?.dealCount ?: 0) + if (reDeal) 1 else 0
                val rows = withContext(computeDispatcher) {
                    computeDailySnapshot(input, day, discoverSeed(day, dealCount), visible)
                }
                transactionRunner.run {
                    dao.deleteSnapshotDay(day)
                    dao.insertSnapshot(rows.map {
                        DiscoverSnapshotEntity(day, it.shelf.code, it.position, it.bookmarkId, it.reasonCode?.code, it.reasonArg, it.topicKey)
                    })
                    dao.upsertMeta(DiscoverSnapshotMetaEntity(SCOPE_DAILY, day, dealCount, visible, now))
                }
            }
            if (needWeekly) {
                val map = withContext(computeDispatcher) { computeWeeklyMap(input, visible) }
                transactionRunner.run {
                    dao.deleteMapWeek(week)
                    dao.insertMap(map.mapIndexed { index, row -> DiscoverMapEntity(week, index, row.skeletonKey, row.value, row.weight) })
                    dao.upsertMeta(DiscoverSnapshotMetaEntity(SCOPE_WEEKLY_MAP, week, 0, visible, now))
                }
            }
        }
        currentDay.value = day
    }

    private suspend fun loadInput(now: Long, zone: ZoneId, day: Long): DiscoverInput {
        val entities = dao.entityRefs().groupBy({ it.bookmarkId }, { EntityRef(it.skeletonKey, it.value) })
        val candidates = dao.candidates().map {
            CandidateRow(it.id, it.createdAt, it.lastInteractedAt, it.isPrivate, it.hasOgImage, it.domain,
                it.subcategory, it.rssFeedId, it.isProtected, entities[it.id].orEmpty())
        }
        val events = dao.events(engagementCutoff(now)).mapNotNull { row ->
            EngagementType.fromCode(row.type)?.let { EventRow(row.bookmarkId, it, row.at) }
        }
        val since = day - HISTORY_DAYS
        val history = SnapshotHistory(
            heroIds = dao.heroIds(since, day).toSet(),
            connectionPairs = dao.connectionRows(since, day).groupBy { it.day }.values.map { rows -> rows.map { it.bookmarkId }.toSet() }.toSet()
        )
        val mutes = dao.mutes().mapNotNull { mute -> TopicAxis.fromCode(mute.axis)?.let { it to mute.key } }.toSet()
        return DiscoverInput(now, zone, candidates, events, dao.activeSuppressedIds(now).toSet(), mutes, history)
    }

    /**
     * Today's daily shelves and this week's map. Subscribing computes a missing snapshot; a Private
     * visibility change recomputes it with the same seed. Trash, suppressions, and mutes apply at once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSnapshot(): Flow<DiscoverSnapshot> = privateVisible.flatMapLatest { visible ->
        flow {
            ensureToday()
            emitAll(currentDay.filterNotNull().flatMapLatest { day -> snapshotFor(day, visible) })
        }
    }

    private fun snapshotFor(day: Long, visible: Boolean): Flow<DiscoverSnapshot> {
        val picks = combine(dao.observeSnapshot(day), dao.observeSnapshotBookmarks(day), dao.observeMap(weekStartDay(day))) { rows, bookmarks, map ->
            Triple(rows, bookmarks, map)
        }
        val filters = combine(dao.observeSuppressions(), dao.observeMutes(), dao.observeLibraryCount(visible)) { suppressions, mutes, count ->
            Triple(suppressions, mutes, count)
        }
        return combine(picks, filters) { (rows, bookmarks, map), (suppressions, mutes, count) ->
            val now = nowMillis()
            val suppressed = suppressions.filter { it.permanent || (it.until ?: 0) > now }.mapTo(HashSet()) { it.bookmarkId }
            val mutedEntities = mutes.filter { it.axis == TopicAxis.ENTITY.code }.mapTo(HashSet()) { it.key }
            buildSnapshot(day, count, rows, bookmarks, map, suppressed, mutedEntities, visible)
        }
    }

    private fun buildSnapshot(
        day: Long, count: Int, rows: List<DiscoverSnapshotEntity>, bookmarks: List<BookmarkEntity>, map: List<DiscoverMapEntity>,
        suppressed: Set<String>, mutedEntities: Set<String>, visible: Boolean
    ): DiscoverSnapshot {
        val items = bookmarks.filter { bookmark ->
            (visible || !bookmark.isPrivate) && bookmark.id !in suppressed &&
                (bookmark.entities.isEmpty() || bookmark.entities.any { entitySkeletonKey(it) !in mutedEntities })
        }.associateBy { it.id }
        fun pick(row: DiscoverSnapshotEntity) = items[row.bookmarkId]?.let { DiscoverPick(it.toBookmarkItem(), decodeReason(row)) }
        val byShelf = rows.groupBy { DiscoverShelf.fromCode(it.shelf) }
        val rising = byShelf[DiscoverShelf.RISING].orEmpty()
            .filter { it.topicKey != null && it.topicKey !in mutedEntities }
            .groupBy { it.position / RISING_GROUP_STRIDE }.toSortedMap().values
            .mapNotNull { group ->
                val key = group.first().topicKey!!
                val picks = group.mapNotNull(::pick)
                if (picks.isEmpty()) return@mapNotNull null
                val display = group.firstNotNullOfOrNull { row -> items[row.bookmarkId]?.entities?.firstOrNull { entitySkeletonKey(it) == key } } ?: key
                RisingGroup(display, key, picks)
            }
        val connectionRows = byShelf[DiscoverShelf.CONNECTIONS].orEmpty().filter { it.topicKey !in mutedEntities }
        val connectionPicks = connectionRows.mapNotNull(::pick)
        val connection = if (connectionRows.size == 2 && connectionPicks.size == 2) connectionPicks[0] to connectionPicks[1] else null
        return DiscoverSnapshot(
            day = day,
            libraryCount = count,
            hero = byShelf[DiscoverShelf.HERO].orEmpty().firstOrNull()?.let(::pick),
            onThisDay = byShelf[DiscoverShelf.ON_THIS_DAY].orEmpty().mapNotNull(::pick),
            sleepingGems = byShelf[DiscoverShelf.SLEEPING_GEMS].orEmpty().mapNotNull(::pick),
            rising = rising,
            connection = connection,
            connectionEntity = connection?.let { connectionRows.first().reasonArg },
            map = map.filter { it.skeletonKey !in mutedEntities }.map { MapBubble(it.value, it.skeletonKey, it.weight.toFloat()) }
        )
    }

    /** Manual saves from the last 48 hours, newest first (at most 12). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeJustSaved(): Flow<List<me.bubblespark.tidilo.ui.screens.library.BookmarkItem>> = privateVisible.flatMapLatest { visible ->
        val now = nowMillis()
        dao.observeJustSaved(now - JUST_SAVED_WINDOW_MS, now, visible, JUST_SAVED_LIMIT).map { rows -> rows.map { it.toBookmarkItem() } }
    }

    /** Unread RSS articles, newest first (at most 10), labeled when ≤3 days remain before expiry. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFreshRss(): Flow<List<DiscoverPick>> = privateVisible.flatMapLatest { visible ->
        val now = nowMillis()
        dao.observeFreshRss(now, visible, FRESH_RSS_LIMIT).map { rows -> rows.map { freshRssPick(it, now) } }
    }

    private fun freshRssPick(row: FreshRssRow, now: Long): DiscoverPick {
        val remainingMs = row.bookmark.createdAt + RSS_UNREAD_DAYS * RETENTION_DAY_MS - now
        val days = if (remainingMs <= 0) 1 else ((remainingMs + RETENTION_DAY_MS - 1) / RETENTION_DAY_MS).toInt()
        val reason = if (!row.isProtected && days <= RSS_EXPIRY_LABEL_DAYS) DiscoverReason.RssExpiring(days) else null
        return DiscoverPick(row.bookmark.toBookmarkItem(), reason)
    }

    /** A fresh deck draw: 30 cards (fewer for small libraries), excluding cards flicked in the last 7 days. */
    suspend fun dealDeck(): List<me.bubblespark.tidilo.ui.screens.library.BookmarkItem> {
        val now = nowMillis()
        val zone = zone()
        val visible = privateVisible.value
        val input = loadInput(now, zone, discoverDay(now, zone))
        val flicked = dao.flickedSince(now - DECK_COOLDOWN_MS).toSet()
        val ids = withContext(computeDispatcher) { drawDeck(input, random.nextLong(), visible, flicked) }
        val byId = dao.bookmarksByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toBookmarkItem() }
    }

    /** Records a Discover impression at most once per item per Discover day. Does not touch `lastInteractedAt`. */
    suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface) {
        val now = nowMillis()
        val since = discoverDayStart(discoverDay(now, zone()), zone())
        transactionRunner.run {
            if (!dao.hasEventSince(bookmarkId, EngagementType.IMPRESSION.code, since)) {
                insertEvent(bookmarkId, EngagementType.IMPRESSION, surface, now)
            }
        }
    }

    /** Records a deck flick (7-day deck cooldown only; neutral for topics). */
    suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface) =
        insertEvent(bookmarkId, EngagementType.DECK_FLICK, surface, nowMillis())

    /** "Not now": hides the item from rediscovery for 30 days. */
    suspend fun snooze(bookmarkId: String, surface: EngagementSurface) {
        val now = nowMillis()
        transactionRunner.run {
            insertEvent(bookmarkId, EngagementType.SNOOZE, surface, now)
            dao.upsertSuppression(RediscoverySuppressionEntity(bookmarkId, until = now + SNOOZE_MS, permanent = false))
        }
    }

    /** "Don't show again": hides the item from rediscovery permanently (Library and Search are unaffected). */
    suspend fun neverAgain(bookmarkId: String, surface: EngagementSurface) {
        val now = nowMillis()
        transactionRunner.run {
            insertEvent(bookmarkId, EngagementType.NEVER_AGAIN, surface, now)
            dao.upsertSuppression(RediscoverySuppressionEntity(bookmarkId, until = null, permanent = true))
        }
    }

    /** Undo for [snooze] and [neverAgain]. The recorded event stays (it carries no topic weight). */
    suspend fun unsuppress(bookmarkId: String) = dao.deleteSuppression(bookmarkId)

    suspend fun muteTopic(axis: TopicAxis, key: String) = dao.insertMute(RediscoveryTopicMuteEntity(axis.code, key))

    suspend fun unmuteTopic(axis: TopicAxis, key: String) = dao.deleteMute(axis.code, key)

    /** Settings "Reset rediscovery history": clears events, suppressions, mutes, snapshots, and maps, then recomputes today. */
    suspend fun resetHistory() = mutex.withLock {
        transactionRunner.run {
            dao.clearEvents()
            dao.clearSuppressions()
            dao.clearMutes()
            dao.clearSnapshot()
            dao.clearMap()
            dao.clearMeta()
        }
        refresh(reDeal = false)
    }

    private suspend fun insertEvent(bookmarkId: String, type: EngagementType, surface: EngagementSurface, at: Long) =
        database.engagementDao().insert(EngagementEventEntity(bookmarkId = bookmarkId, type = type.code, surface = surface.code, at = at))

    companion object {
        @Volatile private var INSTANCE: DiscoverRepository? = null

        fun getInstance(context: Context): DiscoverRepository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: DiscoverRepository(AppDatabase.getInstance(context)).also { INSTANCE = it }
        }
    }
}

/** Decodes a stored reason; an unknown code or malformed argument yields no label. */
internal fun decodeReason(row: DiscoverSnapshotEntity): DiscoverReason? {
    val arg = row.reasonArg ?: return null
    return when (DiscoverReasonCode.fromCode(row.reasonCode ?: return null)) {
        DiscoverReasonCode.DORMANT_NEVER_OPENED -> arg.toIntOrNull()?.let { DiscoverReason.DormantNeverOpened(it) }
        DiscoverReasonCode.AFFINITY_ENTITY -> DiscoverReason.AffinityEntity(arg)
        DiscoverReasonCode.ON_THIS_DAY -> OnThisDayPeriod.entries.firstOrNull { it.name == arg }?.let { DiscoverReason.OnThisDay(it) }
        DiscoverReasonCode.RSS_EXPIRING -> arg.toIntOrNull()?.let { DiscoverReason.RssExpiring(it) }
        DiscoverReasonCode.CONNECTION -> DiscoverReason.Connection(arg)
        DiscoverReasonCode.RISING -> arg.toIntOrNull()?.let { DiscoverReason.Rising(it) }
        null -> null
    }
}
