package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException

/** Scheduled work only acquires XML/metadata and writes local content or AI-ready tasks. */
class RssFetchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return runRssFetchAttempt(runAttemptCount, fetch = {
            val preferences = RssPreferences.getInstance(applicationContext)
            preferences.initialize()
            val now = ZonedDateTime.now()
            val scheduled = now.toLocalDate().atTime(preferences.state.value.time).atZone(now.zone)
            val boundary = if (scheduled > now) scheduled.minusDays(1) else scheduled
            val since = inputData.getLong("requestedAt", boundary.toInstant().toEpochMilli())
            RssRepository.getInstance(applicationContext).fetchAll(since, inputData.getString("feedId"), inputData.getString("operationId"))
        }, realign = { RssScheduler.reschedule(applicationContext, force = true) })
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
