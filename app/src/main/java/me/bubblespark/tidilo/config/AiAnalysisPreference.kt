package me.bubblespark.tidilo.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** Unknown install history preserves the existing default instead of failing startup. */
internal fun detectFreshInstallation(readTimes: () -> Pair<Long, Long>): Boolean = try {
    val (installed, updated) = readTimes()
    installed == updated
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** Owns one-time installation selection; an explicit user choice always takes precedence. */
internal class AiAnalysisPreference(initialMode: AiAnalysisMode?, private val persist: (AiAnalysisMode) -> Unit) {
    private val selectionLock = Any()
    private val initializationLock = Mutex()
    private var initialized = initialMode != null
    private val currentMode = MutableStateFlow(initialMode ?: AiAnalysisMode.AICORE)
    val mode = currentMode.asStateFlow()

    fun setMode(mode: AiAnalysisMode) = synchronized(selectionLock) {
        persist(mode)
        initialized = true
        currentMode.value = mode
    }

    suspend fun initialize(isFreshInstallation: Boolean, checkAvailable: suspend () -> Boolean) = initializationLock.withLock {
        if (synchronized(selectionLock) { initialized }) return@withLock
        val selected = if (!isFreshInstallation || checkAvailable()) AiAnalysisMode.AICORE else AiAnalysisMode.DISABLE
        synchronized(selectionLock) {
            if (!initialized) setMode(selected)
        }
    }
}
