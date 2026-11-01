package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import androidx.work.Data
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssNotificationRunEntity
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import java.util.UUID
import me.bubblespark.tidilo.data.notifications.selectRssNotificationRun

/** Scheduled work only acquires XML/metadata and writes local content or AI-ready tasks. */
class RssFetchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val notifications = db.rssNotificationDao()
        val preferences = RssPreferences.getInstance(applicationContext)
        preferences.initialize()
        val run = RssScheduler.withRunStartLock {
            val settings = preferences.state.value
            startScheduledRssFetchRun(db, WorkManager.getInstance(applicationContext), RssScheduler.DAILY,
                id, inputData, tags, runAttemptCount, settings, Instant.now())
        }
        if (run == null) {
            RssScheduler.reschedule(applicationContext, finishingWorkId = id)
            return Result.success()
        }
        return runRssFetchAttempt(runAttemptCount, fetch = {
            run.completed || RssRepository.getInstance(applicationContext).fetchAll(run.requestedAt, inputData.getString("feedId"),
                inputData.getString("operationId"), run.id)
        }, realign = {
            notifications.finish(run.id)
            NotificationCoordinator.getInstance(applicationContext).publishCompletedRssRuns()
            RssScheduler.reschedule(applicationContext, finishingWorkId = id)
        })
    }
}

/** Call under the scheduler lock to serialize selection and logical-run creation. */
internal suspend fun startScheduledRssFetchRun(db: AppDatabase, manager: WorkManager, family: String,
    id: UUID, input: Data, tags: Set<String>, attempt: Int, settings: RssSettings,
    now: Instant): RssNotificationRunEntity? {
    val works = manager.getWorkInfosByTag("$family:triggers").get()
        .filter { !it.state.isFinished && it.tags.any { tag ->
            settings.triggerTimes.any { "$family:minute:${it.hour * 60 + it.minute}" == tag }
        } }
    val zone = ZoneId.systemDefault()
    val pending = works.mapNotNull { work ->
        val minute = work.tags.firstOrNull { it.startsWith("$family:minute:") }
            ?.substringAfterLast(':')?.toIntOrNull() ?: return@mapNotNull null
        val at = work.tags.firstOrNull {
            it.startsWith("$family:at:")
        }?.substringAfterLast(':')?.toLongOrNull() ?: return@mapNotNull null
        effectiveRssBoundary(at, LocalTime.of(minute / 60, minute % 60), now, zone)
    }
    val claimed = works.mapNotNull { db.rssNotificationDao().latestRun(it.id.toString())?.requestedAt }
    val latest = latestDueRssBoundary(now.toEpochMilli(), pending, claimed)
    return startRssFetchRun(db, id, input, tags, attempt, settings, now, latest)
}

/** Called under the scheduler lock; stale periodic work may only resume an existing logical run. */
internal suspend fun startRssFetchRun(db: AppDatabase, workId: UUID, input: Data, tags: Set<String>,
    attempt: Int, settings: RssSettings, now: Instant = Instant.now(), latestDue: Long? = null): RssNotificationRunEntity? = db.withTransaction {
    val notifications = db.rssNotificationDao()
    notifications.unfinishedRun(workId.toString())?.let { return@withTransaction it }
    val minute = input.getInt("triggerMinuteOfDay", -1)
    val scheduled = input.keyValueMap.containsKey("triggerMinuteOfDay") || tags.any { it.startsWith("rss-notifications:") }
    if (scheduled && (!settings.dailyEnabled || minute !in 0..1439 ||
            settings.triggerTimes.none { it.hour * 60 + it.minute == minute } || db.rssDao().enabledFeeds().isEmpty()))
        return@withTransaction null
    val boundary = if (minute in 0..1439) rssTriggerBoundary(now, LocalTime.of(minute / 60, minute % 60), ZoneId.systemDefault()) else now
    val originalAt = input.getLong("requestedAt", boundary.toEpochMilli())
    val requestedAt = if (scheduled && latestDue != null) effectiveRssBoundary(originalAt,
        LocalTime.of(minute / 60, minute % 60), now, ZoneId.systemDefault()) else originalAt
    if (scheduled && latestDue != null && requestedAt < latestDue) return@withTransaction null
    // Periodic IDs repeat; retries keep the original logical run and its item ownership.
    selectRssNotificationRun(null, notifications.runFor(workId.toString(), requestedAt),
        notifications.latestRun(workId.toString()), attempt, input.keyValueMap.containsKey("requestedAt"))
        ?: RssNotificationRunEntity(UUID.randomUUID().toString(), workId.toString(), requestedAt,
            input.getString("feedId") == null).also { notifications.putRun(it) }
}

/** Article retries are bounded durably per item; infrastructure errors have five worker attempts. */
internal suspend fun runRssFetchAttempt(
    attempt: Int, fetch: suspend () -> Boolean, realign: suspend () -> Unit
): androidx.work.ListenableWorker.Result {
    var infrastructureFailure = false
    val success = try { fetch() }
    catch (_: RssAcquisitionYield) { return androidx.work.ListenableWorker.Result.retry() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: RssWifiUnavailable) { return androidx.work.ListenableWorker.Result.retry() }
    catch (_: java.io.IOException) { infrastructureFailure = true; false }
    if (!success && (!infrastructureFailure || attempt < 4)) return androidx.work.ListenableWorker.Result.retry()
    realign()
    return androidx.work.ListenableWorker.Result.success()
}
