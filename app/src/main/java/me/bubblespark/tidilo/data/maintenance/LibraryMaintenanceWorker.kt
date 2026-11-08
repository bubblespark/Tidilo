package me.bubblespark.tidilo.data.maintenance

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** WorkManager persists this name and constructs it through its public constructor. */
@Keep
class LibraryMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runLibraryMaintenanceAttempt(
        clean = { LibraryMaintenanceRepository(applicationContext).run() },
        realign = { LibraryMaintenanceScheduler.reschedule(applicationContext, finishingWorkId = id) })
}

internal suspend fun runLibraryMaintenanceAttempt(clean: suspend () -> Unit,
    realign: suspend () -> Unit): ListenableWorker.Result = try {
    clean()
    realign()
    ListenableWorker.Result.success()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Log.w("LibraryMaintenance", "Daily maintenance failed; retrying", error)
    ListenableWorker.Result.retry()
}
