package me.bubblespark.tidilo.data.rss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.RssNotificationDao

internal object RssScheduler {
    const val DAILY = "rss-daily-fetch"
    const val MANUAL = "rss-manual-fetch"
    private val lock = Mutex()

    fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder().apply {
        if (wifiOnly) setRequiredNetworkRequest(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).build(), NetworkType.CONNECTED)
        else setRequiredNetworkType(NetworkType.CONNECTED)
    }.build()

    suspend fun reschedule(context: Context, force: Boolean = false, finishingWorkId: UUID? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            val preferences = RssPreferences.getInstance(context)
            preferences.initialize()
            val manager = WorkManager.getInstance(context)
            val db = AppDatabase.getInstance(context)
            val settings = preferences.state.value
            if (db.rssDao().enabledFeeds().isEmpty()) {
                scheduleDaily(manager, settings.copy(dailyEnabled = false), force, DAILY, db.rssNotificationDao(), finishingWorkId)
                manager.cancelUniqueWork(MANUAL).result.get()
                return@withLock
            }
            val manual = manager.getWorkInfosForUniqueWork(MANUAL).get().firstOrNull { !it.state.isFinished }
            if (manual != null) manager.updateWork(OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setId(manual.id).setInputData(workDataOf("requestedAt" to preferences.manualRequestedAt()))
                .setConstraints(constraints(settings.wifiOnly)).build()).get()
            scheduleDaily(manager, settings, force, DAILY, db.rssNotificationDao(), finishingWorkId)
        }
    }

    internal suspend fun scheduleDaily(manager: WorkManager, settings: RssSettings, force: Boolean, name: String,
        notifications: RssNotificationDao? = null, finishingWorkId: UUID? = null) {
        val desired = if (settings.dailyEnabled) settings.triggerTimes.associateBy { it.hour * 60 + it.minute } else emptyMap()
        val existing = (manager.getWorkInfosByTag("$name:triggers").get() +
            manager.getWorkInfosForUniqueWork(name).get()).filter { !it.state.isFinished }.distinctBy { it.id }
        for (work in existing) {
            val minute = work.tags.firstOrNull { it.startsWith("$name:minute:") }?.substringAfterLast(':')?.toIntOrNull()
            if (minute in desired.keys) continue
            val unfinished = notifications?.unfinishedRun(work.id.toString())
            if (work.id != finishingWorkId && (work.state == WorkInfo.State.RUNNING || unfinished != null)) continue
            manager.cancelWorkById(work.id).result.get()
        }
        val zone = ZoneId.systemDefault()
        for ((minute, time) in desired) {
            val workName = "$name-$minute"
            val current = manager.getWorkInfosForUniqueWork(workName).get().firstOrNull { !it.state.isFinished }
            val tag = "rss-notifications:$minute:${settings.wifiOnly}:$zone"
            val unchanged = current?.tags?.contains(tag) == true
            val unfinished = current?.let { notifications?.unfinishedRun(it.id.toString()) }
            val builder = PeriodicWorkRequestBuilder<RssFetchWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints(settings.wifiOnly)).addTag("$name:triggers")
                .addTag("$name:minute:$minute").addTag(tag)
            if (current != null && current.id != finishingWorkId &&
                (current.state == WorkInfo.State.RUNNING || unfinished != null)) {
                // Updating constraints must not override an unfinished run's retry/backoff or boundary.
                if (!unchanged && unfinished != null) manager.updateWork(builder.setId(current.id)
                    .setInputData(workDataOf("triggerMinuteOfDay" to minute, "requestedAt" to unfinished.requestedAt)).build()).get()
                continue
            }
            if (unchanged && current.id != finishingWorkId && (!force || finishingWorkId != null)) continue
            val now = Instant.now()
            val next = nextRssFetchAt(now, time, zone)
            val request = builder.setInputData(workDataOf("triggerMinuteOfDay" to minute, "requestedAt" to next.toEpochMilli()))
                .setInitialDelay(Duration.between(now, next)).setNextScheduleTimeOverride(next.toEpochMilli()).build()
            manager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
        }
    }

    internal suspend fun <T> withRunStartLock(block: suspend () -> T): T = lock.withLock { block() }

    fun initialWorkPolicy(mobileAllowed: Boolean): ExistingWorkPolicy =
        if (mobileAllowed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

    suspend fun fetchInitial(context: Context, feedId: String, operationId: String, mobileAllowed: Boolean) = withContext(Dispatchers.IO) {
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        WorkManager.getInstance(context).enqueueUniqueWork("rss-initial-$feedId-$operationId", initialWorkPolicy(mobileAllowed),
            OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInputData(workDataOf("requestedAt" to System.currentTimeMillis(), "feedId" to feedId, "operationId" to operationId))
                .setConstraints(constraints(!mobileAllowed && preferences.state.value.wifiOnly)).build()).result.get()
    }

    suspend fun fetchNow(context: Context) = withContext(Dispatchers.IO) {
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        lock.withLock {
            val manager = WorkManager.getInstance(context)
            if (manager.getWorkInfosForUniqueWork(MANUAL).get().any { !it.state.isFinished }) return@withLock
            val requestedAt = System.currentTimeMillis()
            preferences.recordManualRequest(requestedAt)
            manager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RssFetchWorker>()
                    .setInputData(workDataOf("requestedAt" to requestedAt))
                    .setConstraints(constraints(preferences.state.value.wifiOnly)).build()).result.get()
        }
    }
}

/** Manifest receiver corrects wall-clock schedules; it does not fetch or perform inference. */
class RssScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { RssScheduler.reschedule(context, force = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("RssScheduleReceiver", "RSS reschedule failed", error) }
            finally { pending.finish() }
        }
    }
}
