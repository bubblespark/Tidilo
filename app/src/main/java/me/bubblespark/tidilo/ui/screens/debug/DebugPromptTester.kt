package me.bubblespark.tidilo.ui.screens.debug

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.ui.screens.settings.GenerateContentRequestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed class GenAiTestState {
    data object Idle : GenAiTestState()
    data class Loading(val message: String) : GenAiTestState()
    data class StatusResult(val statusText: String, val statusCode: Int) : GenAiTestState()
    data class InferenceResult(val responseText: String, val latencyMs: Long) : GenAiTestState()
    data class Error(val errorMessage: String, val cause: Throwable? = null) : GenAiTestState()
}

/**
 * ML Kit GenAI Prompt API test and connectivity helper for debugging.
 * Isolated within the debug package to avoid mixing with production logic.
 */
object DebugPromptTester {

    /**
     * Checks model availability status of Gemini Nano (AICore).
     */
    suspend fun checkStatus(): Pair<String, Int> = withContext(Dispatchers.IO) {
        var model: GenerativeModel? = null
        try {
            model = Generation.getClient()
            val status = model.checkStatus()
            val text = when (status) {
                FeatureStatus.AVAILABLE -> "AVAILABLE (Ready for inference)"
                FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE (Model requires download)"
                FeatureStatus.DOWNLOADING -> "DOWNLOADING (Download in progress)"
                FeatureStatus.UNAVAILABLE -> "UNAVAILABLE (Device or AICore unsupported)"
                else -> "UNKNOWN ($status)"
            }
            text to status
        } catch (e: Exception) {
            "ERROR: ${e.message ?: e.javaClass.simpleName}" to -1
        } finally {
            model?.close()
        }
    }

    /**
     * Initiates model download.
     */
    fun startDownload(): Flow<DownloadStatus> {
        val model = Generation.getClient()
        return model.download()
    }

    /**
     * Sends a one-off test request and receives the response.
     * Runs once with a lightweight prompt to respect AICore rate limits.
     */
    suspend fun testGenerateContent(
        prompt: String = "Reply with 'Hello from Gemini Nano!' in English.",
        config: GenerateContentRequestState? = null
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        var model: GenerativeModel? = null
        val startTime = System.currentTimeMillis()
        try {
            model = Generation.getClient()

            val request = generateContentRequest(TextPart(prompt)) {
                if (config != null) {
                    if (config.cachedContextNameEnabled && config.cachedContextName.isNotBlank()) {
                        cachedContextName = config.cachedContextName
                    }
                    if (config.candidateCountEnabled) {
                        candidateCount = config.candidateCount
                    }
                    if (config.maxOutputTokensEnabled) {
                        maxOutputTokens = config.maxOutputTokens
                    }
                    if (config.seedEnabled) {
                        seed = config.seed
                    }
                    if (config.temperatureEnabled) {
                        temperature = config.temperature
                    }
                    if (config.topKEnabled) {
                        topK = config.topK
                    }
                }
            }

            val response = model.generateContent(request)
            val latency = System.currentTimeMillis() - startTime
            val output = response.candidates.firstOrNull()?.text ?: "(Empty response)"
            output to latency
        } finally {
            model?.close()
        }
    }

    /**
     * Loads the latest configuration parameters from SharedPreferences.
     */
    fun loadCurrentConfig(context: Context): GenerateContentRequestState {
        val def = GenerateContentRequestState.default()
        val p = context.getSharedPreferences("tidilo_generate_content_request_prefs", Context.MODE_PRIVATE)
        return GenerateContentRequestState(
            cachedContextNameEnabled = p.getBoolean("cachedContextNameEnabled", def.cachedContextNameEnabled),
            cachedContextName = p.getString("cachedContextName", def.cachedContextName) ?: def.cachedContextName,
            candidateCountEnabled = p.getBoolean("candidateCountEnabled", def.candidateCountEnabled),
            candidateCount = p.getInt("candidateCount", def.candidateCount),
            enableThinkingEnabled = p.getBoolean("enableThinkingEnabled", def.enableThinkingEnabled),
            enableThinking = p.getBoolean("enableThinking", def.enableThinking),
            maxOutputTokensEnabled = p.getBoolean("maxOutputTokensEnabled", def.maxOutputTokensEnabled),
            maxOutputTokens = p.getInt("maxOutputTokens", def.maxOutputTokens),
            seedEnabled = p.getBoolean("seedEnabled", def.seedEnabled),
            seed = p.getInt("seed", def.seed),
            temperatureEnabled = p.getBoolean("temperatureEnabled", def.temperatureEnabled),
            temperature = p.getFloat("temperature", def.temperature),
            topKEnabled = p.getBoolean("topKEnabled", def.topKEnabled),
            topK = p.getInt("topK", def.topK)
        )
    }
}
