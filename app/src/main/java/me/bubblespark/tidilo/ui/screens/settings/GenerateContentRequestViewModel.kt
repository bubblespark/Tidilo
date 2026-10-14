package me.bubblespark.tidilo.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.bubblespark.tidilo.TidiloApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GenerateContentRequestState(
    // 1. cachedContextName: String, null, Text fields
    val cachedContextNameEnabled: Boolean = false,
    val cachedContextName: String = "",

    // 2. candidateCount: Int, 1, Text fields (numeric digits only)
    val candidateCountEnabled: Boolean = true,
    val candidateCount: Int = 1,

    // 3. enableThinking: Boolean, false, Switch(on/off)
    val enableThinkingEnabled: Boolean = false,
    val enableThinking: Boolean = false,

    // 4. maxOutputTokens: Int, 4096, Text fields (numeric digits only)
    val maxOutputTokensEnabled: Boolean = false,
    val maxOutputTokens: Int = 4096,

    // 5. seed: Int, 0, Text fields (numeric digits only)
    val seedEnabled: Boolean = false,
    val seed: Int = 0,

    // 6. temperature: Float, 0.0f, Horizontal Slider (Standard) 0.1 step, 0.0-1.0
    val temperatureEnabled: Boolean = true,
    val temperature: Float = 0.0f,

    // 7. topK: Int, 3, Text fields (numeric digits only)
    val topKEnabled: Boolean = false,
    val topK: Int = 3
) {
    companion object {
        fun default(): GenerateContentRequestState = GenerateContentRequestState()
    }
}

class GenerateContentRequestViewModel : ViewModel() {

    private val prefs by lazy {
        TidiloApplication.instance.getSharedPreferences("tidilo_generate_content_request_prefs", Context.MODE_PRIVATE)
    }

    private val _uiState = MutableStateFlow(loadFromPrefs())
    val uiState: StateFlow<GenerateContentRequestState> = _uiState.asStateFlow()

    private fun loadFromPrefs(): GenerateContentRequestState {
        val def = GenerateContentRequestState.default()
        val p = TidiloApplication.instance.getSharedPreferences("tidilo_generate_content_request_prefs", Context.MODE_PRIVATE)
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

    private fun saveToPrefs(state: GenerateContentRequestState) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit()
                .putBoolean("cachedContextNameEnabled", state.cachedContextNameEnabled)
                .putString("cachedContextName", state.cachedContextName)
                .putBoolean("candidateCountEnabled", state.candidateCountEnabled)
                .putInt("candidateCount", state.candidateCount)
                .putBoolean("enableThinkingEnabled", state.enableThinkingEnabled)
                .putBoolean("enableThinking", state.enableThinking)
                .putBoolean("maxOutputTokensEnabled", state.maxOutputTokensEnabled)
                .putInt("maxOutputTokens", state.maxOutputTokens)
                .putBoolean("seedEnabled", state.seedEnabled)
                .putInt("seed", state.seed)
                .putBoolean("temperatureEnabled", state.temperatureEnabled)
                .putFloat("temperature", state.temperature)
                .putBoolean("topKEnabled", state.topKEnabled)
                .putInt("topK", state.topK)
                .apply()
        }
    }

    private fun updateState(transform: (GenerateContentRequestState) -> GenerateContentRequestState) {
        val newState = transform(_uiState.value)
        _uiState.value = newState
        saveToPrefs(newState)
    }

    // Toggle enabled
    fun setCachedContextNameEnabled(enabled: Boolean) = updateState { it.copy(cachedContextNameEnabled = enabled) }
    fun setCandidateCountEnabled(enabled: Boolean) = updateState { it.copy(candidateCountEnabled = enabled) }
    fun setEnableThinkingEnabled(enabled: Boolean) = updateState { it.copy(enableThinkingEnabled = enabled) }
    fun setMaxOutputTokensEnabled(enabled: Boolean) = updateState { it.copy(maxOutputTokensEnabled = enabled) }
    fun setSeedEnabled(enabled: Boolean) = updateState { it.copy(seedEnabled = enabled) }
    fun setTemperatureEnabled(enabled: Boolean) = updateState { it.copy(temperatureEnabled = enabled) }
    fun setTopKEnabled(enabled: Boolean) = updateState { it.copy(topKEnabled = enabled) }

    // Value setters
    fun setCachedContextName(value: String) = updateState { it.copy(cachedContextName = value) }
    fun setCandidateCount(value: Int) = updateState { it.copy(candidateCount = value) }
    fun setEnableThinking(value: Boolean) = updateState { it.copy(enableThinking = value) }
    fun setMaxOutputTokens(value: Int) = updateState { it.copy(maxOutputTokens = value) }
    fun setSeed(value: Int) = updateState { it.copy(seed = value) }
    fun setTemperature(value: Float) = updateState { it.copy(temperature = value) }
    fun setTopK(value: Int) = updateState { it.copy(topK = value) }

    /**
     * Resets all parameters to default values
     * (turns ON checkboxes for candidateCount and temperature).
     */
    fun resetToDefault() {
        val def = GenerateContentRequestState.default()
        _uiState.value = def
        saveToPrefs(def)
    }
}
