package me.bubblespark.tidilo.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.CacheStorageRepository
import me.bubblespark.tidilo.data.CacheStorageSnapshot
import me.bubblespark.tidilo.data.CollectionCounts
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.OgImageManager

sealed interface StorageLoad<out T> {
    data object Loading : StorageLoad<Nothing>
    data class Ready<T>(val value: T) : StorageLoad<T>
    data object Error : StorageLoad<Nothing>
}

sealed interface SummaryLoad<out T> {
    data object Loading : SummaryLoad<Nothing>
    data class Ready<T>(val value: T) : SummaryLoad<T>
    data object Error : SummaryLoad<Nothing>
}

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val bookmarks = BookmarkRepository.getInstance(application)

    private val _counts = MutableStateFlow<StorageLoad<CollectionCounts>>(StorageLoad.Loading)
    val counts = _counts.asStateFlow()

    private val _cache = MutableStateFlow<StorageLoad<CacheStorageSnapshot>>(StorageLoad.Loading)
    val cache = _cache.asStateFlow()

    private var cacheJob: Job? = null
    private var visible = false

    init {
        viewModelScope.launch {
            bookmarks.initializationFailure.collect { failure ->
                if (failure != null) _counts.value = StorageLoad.Error
            }
        }
        viewModelScope.launch {
            bookmarks.observeCollectionCounts().collect { _counts.value = StorageLoad.Ready(it) }
        }
    }

    fun retryCounts() {
        _counts.value = StorageLoad.Loading
        bookmarks.retryInitialization()
    }

    fun refreshCache() {
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _cache.value = StorageLoad.Loading
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    CacheStorageRepository.scan(getApplication<Application>().cacheDir)
                }
                ensureActive()
                _cache.value = StorageLoad.Ready(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _cache.value = StorageLoad.Error
            }
        }
    }

    fun clearCache(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                OgImageManager.clearImageCache(app)
                DomainIconManager.clearCache(app)
            }
            refreshCache()
            onDone()
        }
    }

    fun onVisible() {
        if (visible) return
        visible = true
        if (_counts.value is StorageLoad.Error) {
            retryCounts()
        }
        refreshCache()
    }

    fun onHidden() {
        if (!visible) return
        visible = false
        cacheJob?.cancel()
    }

    override fun onCleared() {
        onHidden()
        super.onCleared()
    }
}

typealias SummaryViewModel = StorageViewModel
