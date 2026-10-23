package me.bubblespark.tidilo.ui.screens.settings

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings

class RssSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RssRepository.getInstance(application)
    private val preferences = RssPreferences.getInstance(application)
    val settings = preferences.state
    private val _feeds = MutableStateFlow<SummaryLoad<List<RssFeedEntity>>>(SummaryLoad.Loading)
    val feeds = _feeds.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _wifi = MutableStateFlow(false)
    val wifi = _wifi.asStateFlow()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var listening = false
    private var feedsJob: Job? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateNetwork()
    }

    init { retry() }

    fun retry() {
        action { preferences.initialize() }
        feedsJob?.cancel()
        feedsJob = viewModelScope.launch {
            _feeds.value = SummaryLoad.Loading
            repository.feeds.catch { _feeds.value = SummaryLoad.Error }
                .collect { _feeds.value = SummaryLoad.Ready(it.filter { feed -> !feed.deleted }) }
        }
    }

    fun startListening() {
        if (listening) return
        listening = true
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        updateNetwork()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        connectivity.unregisterNetworkCallback(callback)
    }

    @Suppress("DEPRECATION")
    private fun updateNetwork() {
        _wifi.value = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }

    fun saveSettings(value: RssSettings) = action {
        preferences.save(value)
        RssScheduler.reschedule(getApplication(), force = true)
    }

    fun saveFeed(original: RssFeedEntity?, url: String, mode: String, limit: Int?, convert: Boolean) = action {
        if (original == null) repository.register(url, mode, limit)
        else if (original.url == url.trim()) repository.edit(original.id, mode, limit, convert)
        else {
            // Validate/create the replacement first; an invalid new URL leaves the old feed intact.
            repository.register(url, mode, limit)
            repository.delete(original.id)
        }
    }

    fun deleteFeed(id: String) = action { repository.delete(id) }
    fun fetchNow() = action { RssScheduler.fetchNow(getApplication()) }
    fun clearError() { _error.value = null }

    private fun action(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = error.message ?: "Could not update RSS settings" }
            finally { _busy.value = false }
        }
    }

    override fun onCleared() { stopListening(); super.onCleared() }
}
