package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.ZonedDateTime
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
        val now = ZonedDateTime.now()
        val scheduled = now.toLocalDate().atTime(preferences.state.value.time).atZone(now.zone)
        val boundary = if (scheduled > now) scheduled.minusDays(1) else scheduled
        val requestedAt = inputData.getLong("requestedAt", boundary.toInstant().toEpochMilli())
        // Periodic WorkRequest IDs repeat; an unfinished logical run survives retries and midnight.
        val run = RssScheduler.withRunStartLock { db.withTransaction {
            selectRssNotificationRun(notifications.unfinishedRun(id.toString()),
                notifications.runFor(id.toString(), requestedAt), notifications.latestRun(id.toString()),
                runAttemptCount, inputData.keyValueMap.containsKey("requestedAt")) ?: RssNotificationRunEntity(
                UUID.randomUUID().toString(), id.toString(),
                requestedAt,
                inputData.getString("feedId") == null
            ).also { notifications.putRun(it) }
        } }
        return runRssFetchAttempt(runAttemptCount, fetch = {
            run.completed || RssRepository.getInstance(applicationContext).fetchAll(run.requestedAt, inputData.getString("feedId"),
                inputData.getString("operationId"), run.id)
        }, realign = {
            notifications.finish(run.id)
            NotificationCoordinator.getInstance(applicationContext).publishCompletedRssRuns()
            RssScheduler.reschedule(applicationContext, force = true, finishingWorkId = id)
        })
    }
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
