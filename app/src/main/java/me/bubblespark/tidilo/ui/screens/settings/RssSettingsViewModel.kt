package me.bubblespark.tidilo.ui.screens.settings

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.data.rss.RssRegistrationSnapshot
import me.bubblespark.tidilo.data.rss.RssRegistrationConfirmationRequired

internal data class RssRegistrationRequest(
    val original: RssFeedEntity?, val url: String, val mode: String, val limit: Int?, val convert: Boolean,
    val snapshot: RssRegistrationSnapshot
)

internal enum class ManualFetchStatus { Checking, Idle, Waiting, Running }

internal fun manualFetchStatus(states: List<WorkInfo.State>): ManualFetchStatus = when {
    states.any { it == WorkInfo.State.RUNNING } -> ManualFetchStatus.Running
    states.any { !it.isFinished } -> ManualFetchStatus.Waiting
    else -> ManualFetchStatus.Idle
}

class RssSettingsViewModel(application: Application, private val savedState: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(application) {
    private val repository = RssRepository.getInstance(application)
    private val preferences = RssPreferences.getInstance(application)
    val settings = preferences.state
    internal val manualFetch = WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(RssScheduler.MANUAL)
        .map { infos -> manualFetchStatus(infos.map { it.state }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ManualFetchStatus.Checking)
    private val _feeds = MutableStateFlow<SummaryLoad<List<RssFeedEntity>>>(SummaryLoad.Loading)
    val feeds = _feeds.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _wifi = MutableStateFlow<Boolean?>(null)
    val wifi = _wifi.asStateFlow()
    private val _online = MutableStateFlow<Boolean?>(null)
    val online = _online.asStateFlow()
    private val _initialFetch = MutableStateFlow<String?>(savedState["initialFeed"])
    val initialFetch = _initialFetch.asStateFlow()
    private val _registration = MutableStateFlow<RssRegistrationRequest?>(null)
    internal val registration = _registration.asStateFlow()
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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        updateNetwork()
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        connectivity.unregisterNetworkCallback(callback)
        _wifi.value = null
        _online.value = null
    }

    @Suppress("DEPRECATION")
    private fun updateNetwork() {
        _online.value = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
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

    fun saveFeed(original: RssFeedEntity?, url: String, mode: String, limit: Int?, convert: Boolean) =
        saveFeed(original, url, mode, limit, convert, null)

    private fun saveFeed(original: RssFeedEntity?, url: String, mode: String, limit: Int?, convert: Boolean,
        confirmed: RssRegistrationSnapshot?) = action {
        try {
            if (original == null) offerInitialFetch(repository.register(url, mode, limit, confirmed))
            else if (original.url == url.trim()) repository.edit(original.id, mode, limit, convert)
            else {
                // Validate/create the replacement first; an invalid new URL leaves the old feed intact.
                val id = repository.register(url, mode, limit, confirmed)
                repository.delete(original.id)
                offerInitialFetch(id)
            }
        } catch (required: RssRegistrationConfirmationRequired) {
            _registration.value = RssRegistrationRequest(original, url, mode, limit, convert, required.snapshot)
        }
    }

    fun dismissRegistration() { _registration.value = null }
    fun confirmRegistration() {
        val request = _registration.value ?: return
        if (_busy.value) return
        _registration.value = null
        saveFeed(request.original, request.url, request.mode, request.limit, request.convert, request.snapshot)
    }

    private fun offerInitialFetch(id: String) {
        savedState["initialFeed"] = id
        _initialFetch.value = id
    }
    fun dismissInitialFetch() {
        savedState["initialFeed"] = null
        _initialFetch.value = null
    }
    fun fetchInitial(mobileConsent: Boolean) = action {
        val id = _initialFetch.value ?: return@action
        updateNetwork()
        check(_online.value == true) { "No validated Internet connection is available" }
        val requiresOverride = settings.value.wifiOnly && _wifi.value != true
        // A connection change must first make the warning visible before cellular is authorized.
        if (requiresOverride && !mobileConsent) return@action
        repository.requestInitialFetch(id, requiresOverride && mobileConsent)
        dismissInitialFetch()
    }

    fun deleteFeed(id: String) = action { repository.delete(id) }
    fun setEnabled(id: String, enabled: Boolean) = action { repository.setEnabled(id, enabled) }
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
