package me.bubblespark.tidilo.ui.screens.settings

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.AiCoreUsageRepository
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.CacheStorageRepository
import me.bubblespark.tidilo.data.CacheStorageSnapshot
import me.bubblespark.tidilo.data.CollectionCounts

sealed interface SummaryLoad<out T> {
    data object Loading : SummaryLoad<Nothing>
    data class Ready<T>(val value: T) : SummaryLoad<T>
    data object Error : SummaryLoad<Nothing>
}

class SummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val usageRepository = AiCoreUsageRepository.getInstance(application)
    private val bookmarks = BookmarkRepository.getInstance(application)
    val usage = usageRepository.state
    private val _counts = MutableStateFlow<SummaryLoad<CollectionCounts>>(SummaryLoad.Loading)
    val counts = _counts.asStateFlow()
    private val _cache = MutableStateFlow<SummaryLoad<CacheStorageSnapshot>>(SummaryLoad.Loading)
    val cache = _cache.asStateFlow()
    private val _today = MutableStateFlow(LocalDate.now())
    val today = _today.asStateFlow()
    private val _zone = MutableStateFlow(ZoneId.systemDefault())
    val zone = _zone.asStateFlow()
    private var cacheJob: Job? = null
    private var midnightJob: Job? = null
    private var visible = false
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = scheduleDateUpdate()
    }

    init {
        retryUsage()
        viewModelScope.launch {
            bookmarks.initializationFailure.collect { failure ->
                if (failure != null) _counts.value = SummaryLoad.Error
            }
        }
        viewModelScope.launch {
            bookmarks.observeCollectionCounts().collect { _counts.value = SummaryLoad.Ready(it) }
        }
    }

    fun retryUsage() { viewModelScope.launch { usageRepository.initialize() } }

    fun retryCounts() {
        _counts.value = SummaryLoad.Loading
        bookmarks.retryInitialization()
    }

    fun refreshCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _cache.value = SummaryLoad.Loading
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    CacheStorageRepository.scan(getApplication<Application>().cacheDir)
                }
                ensureActive()
                _cache.value = SummaryLoad.Ready(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _cache.value = SummaryLoad.Error
            }
        }
    }

    fun onVisible() {
        if (visible) return
        visible = true
        ContextCompat.registerReceiver(getApplication(), dateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scheduleDateUpdate()
        retryUsage()
        refreshCache()
    }

    fun onHidden() {
        if (!visible) return
        visible = false
        getApplication<Application>().unregisterReceiver(dateReceiver)
        midnightJob?.cancel()
        cacheJob?.cancel()
    }

    private fun scheduleDateUpdate() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (visible) {
                _today.value = LocalDate.now()
                _zone.value = ZoneId.systemDefault()
                delay(millisUntilSummaryMidnight(Instant.now(), ZoneId.systemDefault()))
            }
        }
    }

    override fun onCleared() { onHidden(); super.onCleared() }
}
