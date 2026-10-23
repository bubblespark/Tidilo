package me.bubblespark.tidilo.data.rss

import android.content.Context
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class RssSettings(val hour: Int = 0, val minute: Int = 0, val wifiOnly: Boolean = true) {
    val time: LocalTime get() = LocalTime.of(hour, minute)
}

internal class RssPreferences private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("tidilo_rss", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var loaded = false
    private val _state = MutableStateFlow(RssSettings())
    val state = _state.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                _state.value = RssSettings(preferences.getInt("hour", 0).coerceIn(0, 23),
                    preferences.getInt("minute", 0).coerceIn(0, 59), preferences.getBoolean("wifi", true))
                loaded = true
            }
        }
    }

    suspend fun save(settings: RssSettings) = withContext(Dispatchers.IO) {
        require(settings.hour in 0..23 && settings.minute in 0..59)
        mutex.withLock {
            if (!preferences.edit().putInt("hour", settings.hour).putInt("minute", settings.minute)
                    .putBoolean("wifi", settings.wifiOnly).commit()) throw java.io.IOException("Could not save RSS settings")
            _state.value = settings
            loaded = true
        }
    }

    companion object {
        @Volatile private var instance: RssPreferences? = null
        fun getInstance(context: Context): RssPreferences = instance ?: synchronized(this) {
            instance ?: RssPreferences(context.applicationContext).also { instance = it }
        }
    }
}
