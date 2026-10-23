package me.bubblespark.tidilo.data.rss

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.AppDatabase

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

    suspend fun reschedule(context: Context, force: Boolean = true) = withContext(Dispatchers.IO) {
        lock.withLock {
            val preferences = RssPreferences.getInstance(context)
            preferences.initialize()
            val manager = WorkManager.getInstance(context)
            if (AppDatabase.getInstance(context).rssDao().activeFeeds().isEmpty()) {
                manager.cancelUniqueWork(DAILY).result.get()
                manager.cancelUniqueWork(MANUAL).result.get()
                return@withLock
            }
            val settings = preferences.state.value
            val zone = ZoneId.systemDefault()
            val tag = "rss:${settings.hour}:${settings.minute}:${settings.wifiOnly}:$zone"
            val current = manager.getWorkInfosForUniqueWork(DAILY).get().firstOrNull { !it.state.isFinished }
            if (!force && current?.tags?.contains(tag) == true) return@withLock
            val now = Instant.now()
            val next = nextRssFetchAt(now, settings.time, zone)
            val request = PeriodicWorkRequestBuilder<RssFetchWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints(settings.wifiOnly))
                .setInitialDelay(Duration.between(now, next))
                .setNextScheduleTimeOverride(next.toEpochMilli())
                .addTag(tag).build()
            manager.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
        }
    }

    suspend fun fetchNow(context: Context) = withContext(Dispatchers.IO) {
        val preferences = RssPreferences.getInstance(context)
        preferences.initialize()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RssFetchWorker>()
                .setInputData(workDataOf("requestedAt" to System.currentTimeMillis()))
                .setConstraints(constraints(preferences.state.value.wifiOnly)).build()).result.get()
    }
}

/** Manifest receiver corrects wall-clock schedules; it does not fetch or perform inference. */
class RssScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { RssScheduler.reschedule(context) } finally { pending.finish() }
        }
    }
}
