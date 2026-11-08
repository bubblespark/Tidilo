package me.bubblespark.tidilo.data.maintenance

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.rss.nextRssFetchAt
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object LibraryMaintenanceScheduler {
    const val DAILY = "library-daily-maintenance"
    private val lock = Mutex()

    suspend fun reschedule(context: Context, realignClock: Boolean = false, finishingWorkId: UUID? = null) =
        schedule(WorkManager.getInstance(context), Instant.now(), ZoneId.systemDefault(), realignClock, finishingWorkId)

    internal suspend fun schedule(manager: WorkManager, now: Instant, zone: ZoneId,
        realignClock: Boolean = false, finishingWorkId: UUID? = null, name: String = DAILY) = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = manager.getWorkInfosForUniqueWork(name).get().firstOrNull { !it.state.isFinished }
            if (current != null && current.id != finishingWorkId) {
                val dueAt = current.tags.firstOrNull { it.startsWith("$name:at:") }
                    ?.substringAfterLast(':')?.toLongOrNull()
                // Keep running, retrying and overdue requests, including across wall-clock changes.
                if (current.state == WorkInfo.State.RUNNING || current.runAttemptCount > 0 ||
                    dueAt != null && dueAt <= now.toEpochMilli()) return@withLock
                if (!realignClock && "$name:zone:$zone" in current.tags) return@withLock
            }
            val next = nextRssFetchAt(now, LocalTime.MIDNIGHT, zone)
            val request = PeriodicWorkRequestBuilder<LibraryMaintenanceWorker>(24, TimeUnit.HOURS)
                .addTag("$name:zone:$zone").addTag("$name:at:${next.toEpochMilli()}")
                .setInitialDelay(Duration.between(now, next))
                .setNextScheduleTimeOverride(next.toEpochMilli()).build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
        }
    }
}
