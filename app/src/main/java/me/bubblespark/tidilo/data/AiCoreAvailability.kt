package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Checks readiness only; never starts a download or inference. */
internal object AiCoreAvailability {
    suspend fun check(
        openModel: () -> GenerativeModel = { Generation.getClient() },
        timeoutMillis: Long = 10_000L
    ): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMillis) {
            var model: GenerativeModel? = null
            try {
                model = openModel()
                model.checkStatus() == FeatureStatus.AVAILABLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                model?.let { runCatching { it.close() } }
            }
        } ?: false
    }
}
