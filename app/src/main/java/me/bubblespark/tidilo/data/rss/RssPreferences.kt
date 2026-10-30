package me.bubblespark.tidilo.data.rss

import android.content.Context
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RssSettings(val triggerTimes: List<LocalTime> = emptyList(), val wifiOnly: Boolean = true, val dailyEnabled: Boolean = false) {
    init { encodeRssTriggerTimes(triggerTimes) }
}

internal class RssPreferences internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences("tidilo_rss", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loaded = false
    private var migrationPending = false
    private val _state = MutableStateFlow(RssSettings())
    val state = _state.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                migrationPending = migrationPending || preferences.getInt("scheduleVersion", 0) < 1
                if (migrationPending) {
                    if (!preferences.edit().putInt("scheduleVersion", 1).putString("triggerMinutes", "")
                            .putBoolean("dailyEnabled", false).remove("hour").remove("minute").commit())
                        throw java.io.IOException("Could not initialize RSS settings")
                    migrationPending = false
                }
                _state.value = RssSettings(decodeRssTriggerTimes(preferences.getString("triggerMinutes", "")),
                    preferences.getBoolean("wifi", true), preferences.getBoolean("dailyEnabled", false))
                loaded = true
            }
        }
    }

    suspend fun save(settings: RssSettings) = withContext(Dispatchers.IO) {
        val saved = settings.copy(triggerTimes = settings.triggerTimes.sorted())
        val encoded = encodeRssTriggerTimes(saved.triggerTimes)
        mutex.withLock {
            if (!preferences.edit().putInt("scheduleVersion", 1).putString("triggerMinutes", encoded)
                    .remove("hour").remove("minute")
                    .putBoolean("wifi", saved.wifiOnly).putBoolean("dailyEnabled", saved.dailyEnabled)
                    .commit()) throw java.io.IOException("Could not save RSS settings")
            _state.value = saved
            migrationPending = false
            loaded = true
        }
    }

    suspend fun manualRequestedAt(): Long = withContext(Dispatchers.IO) { preferences.getLong("manualAt", 0) }
    suspend fun recordManualRequest(at: Long) = withContext(Dispatchers.IO) {
        if (!preferences.edit().putLong("manualAt", at).commit()) throw java.io.IOException("Could not schedule RSS fetch")
    }

    companion object {
        @Volatile private var instance: RssPreferences? = null
        fun getInstance(context: Context): RssPreferences = instance ?: synchronized(this) {
            instance ?: RssPreferences(context.applicationContext).also { instance = it }
        }
    }
}
