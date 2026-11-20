package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface AiCoreReadiness {
    data class Status(val code: Int) : AiCoreReadiness
    data object Failed : AiCoreReadiness

    val isAvailable: Boolean
        get() = this is Status && code == FeatureStatus.AVAILABLE
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
        withTimeoutOrNull(timeoutMillis) {
            var model: GenerativeModel? = null
            try {
                model = openModel()
                AiCoreReadiness.Status(model.checkStatus())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AiCoreReadiness.Failed
            } finally {
                model?.let { runCatching { it.close() } }
            }
        } ?: AiCoreReadiness.Failed
    }
}
