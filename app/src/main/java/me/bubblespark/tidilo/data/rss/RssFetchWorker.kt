package me.bubblespark.tidilo.data.rss

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException

/** Scheduled work only acquires XML/metadata and writes local content or AI-ready tasks. */
class RssFetchWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val preferences = RssPreferences.getInstance(applicationContext)
            preferences.initialize()
            val now = ZonedDateTime.now()
            val scheduled = now.toLocalDate().atTime(preferences.state.value.time).atZone(now.zone)
            val boundary = if (scheduled > now) scheduled.minusDays(1) else scheduled
            val since = inputData.getLong("requestedAt", boundary.toInstant().toEpochMilli())
            val success = RssRepository.getInstance(applicationContext).fetchAll(since)
            if (success) {
                RssScheduler.reschedule(applicationContext)
                Result.success()
            } else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RssWifiUnavailable) { Result.retry() }
        catch (_: java.io.IOException) { Result.retry() }
    }
}
