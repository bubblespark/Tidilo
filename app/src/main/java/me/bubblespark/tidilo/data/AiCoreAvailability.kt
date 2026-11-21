package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface AiCoreReadiness {
    data class Status(val code: Int) : AiCoreReadiness
    data object Failed : AiCoreReadiness

    val isAvailable: Boolean
        get() = this is Status && code == FeatureStatus.AVAILABLE
}

internal enum class AiCoreDownloadResult { COMPLETED, STATUS_CHANGED, FAILED }

internal suspend fun downloadAiCoreModel(
    onProgress: (Float?) -> Unit,
    openModel: () -> GenerativeModel = { Generation.getClient() }
): AiCoreDownloadResult = withContext(Dispatchers.IO) {
    var model: GenerativeModel? = null
    try {
        val client = openModel().also { model = it }
        when (AiCoreAvailability.inspect(client)) {
            AiCoreReadiness.Failed -> return@withContext AiCoreDownloadResult.FAILED
            AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE) -> Unit
            else -> return@withContext AiCoreDownloadResult.STATUS_CHANGED
        }

        var totalBytes = 0L
        val terminal = client.download().firstOrNull { status ->
            when (status) {
                is DownloadStatus.DownloadStarted -> {
                    totalBytes = status.bytesToDownload
                    onProgress(if (totalBytes > 0) 0f else null)
                }
                is DownloadStatus.DownloadProgress -> {
                    onProgress(
                        if (totalBytes > 0) (status.totalBytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                        else null
                    )
                }
                else -> Unit
            }
            status is DownloadStatus.DownloadCompleted || status is DownloadStatus.DownloadFailed
        }
        if (terminal is DownloadStatus.DownloadCompleted) AiCoreDownloadResult.COMPLETED
        else AiCoreDownloadResult.FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AiCoreDownloadResult.FAILED
    } finally {
        model?.let { runCatching { it.close() } }
    }
}

/** Checks readiness only; never starts a download or inference. */
internal object AiCoreAvailability {
    suspend fun check(
        openModel: () -> GenerativeModel = { Generation.getClient() },
        timeoutMillis: Long = 10_000L
    ): Boolean = inspect(openModel, timeoutMillis).isAvailable

    suspend fun inspect(
        openModel: () -> GenerativeModel = { Generation.getClient() },
        timeoutMillis: Long = 10_000L
    ): AiCoreReadiness = withContext(Dispatchers.IO) {
        readStatus(timeoutMillis) {
            val model = openModel()
            try {
                model.checkStatus()
            } finally {
                runCatching { model.close() }
            }
        }
    }

    suspend fun inspect(model: GenerativeModel): AiCoreReadiness = withContext(Dispatchers.IO) {
        readStatus(10_000L, model::checkStatus)
    }

    private suspend fun readStatus(timeoutMillis: Long, check: suspend () -> Int): AiCoreReadiness =
        withTimeoutOrNull(timeoutMillis) {
            try {
                AiCoreReadiness.Status(check())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AiCoreReadiness.Failed
            }
        } ?: AiCoreReadiness.Failed
}
