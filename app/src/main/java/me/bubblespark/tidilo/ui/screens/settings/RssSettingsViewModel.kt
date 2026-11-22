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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.notifications.KEY_NOTIFICATION_PERMISSION_REQUESTED
import me.bubblespark.tidilo.data.notifications.NOTIFICATION_PREFS_NAME
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.data.notifications.hasPostNotificationsPermission
import me.bubblespark.tidilo.data.notifications.shouldOfferNotificationPermissionRationale
import me.bubblespark.tidilo.data.notifications.shouldPromptNotificationPermissionOnRssEvent
import me.bubblespark.tidilo.data.rss.RssPreferences
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssScheduler
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.data.rss.RssRegistrationSnapshot
import me.bubblespark.tidilo.data.rss.RssRegistrationConfirmationRequired

internal data class RssRegistrationRequest(
    val original: RssFeedEntity?, val url: String,
    val snapshot: RssRegistrationSnapshot
)

internal enum class ManualFetchStatus { Checking, Idle, Waiting, Running }

private data class RssConnectivity(val online: Boolean, val wifi: Boolean, val mobile: Boolean)

internal fun manualFetchStatus(states: List<WorkInfo.State>): ManualFetchStatus = when {
    states.any { it == WorkInfo.State.RUNNING } -> ManualFetchStatus.Running
    states.any { !it.isFinished } -> ManualFetchStatus.Waiting
    else -> ManualFetchStatus.Idle
}

class RssSettingsViewModel internal constructor(
    application: Application,
    private val savedState: androidx.lifecycle.SavedStateHandle,
    private val repository: RssRepository,
    private val networkStateOverride: (() -> Pair<Boolean, Boolean>)? = null,
    private val permissionGrantedProvider: () -> Boolean = { hasPostNotificationsPermission(application) },
    private val persistPermissionRequested: suspend () -> Boolean = {
        application.getSharedPreferences(NOTIFICATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            .commit()
    },
    private val enqueueManualFetch: suspend (Boolean) -> Unit = { mobileAllowed ->
        RssScheduler.fetchNow(application, mobileAllowed)
    }
) : AndroidViewModel(application) {
    constructor(
        application: Application,
        savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()
    ) : this(application, savedState, RssRepository.getInstance(application), null)

    private val preferences = RssPreferences.getInstance(application)
    val settings = preferences.state
    private val _settingsReady = MutableStateFlow(false)
    internal val settingsReady = _settingsReady.asStateFlow()
    internal val manualMobilePrompt = savedState.getStateFlow("manualMobilePrompt", false)
    internal val manualFetch = WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(RssScheduler.MANUAL)
        .map { infos -> manualFetchStatus(infos.map { it.state }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ManualFetchStatus.Checking)
    private val _feeds = MutableStateFlow<StorageLoad<List<RssFeedEntity>>>(StorageLoad.Loading)
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
    private val _notificationRationale = MutableStateFlow(false)
    val notificationRationale = _notificationRationale.asStateFlow()
    private val _permissionLaunchPending = MutableStateFlow(false)
    val permissionLaunchPending = _permissionLaunchPending.asStateFlow()
    private val rationaleMutex = Mutex()
    private val _consumingNotificationRationale = MutableStateFlow(false)
    private val _registration = MutableStateFlow<RssRegistrationRequest?>(null)
    internal val registration = _registration.asStateFlow()
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var listening = false
    private var feedsJob: Job? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateNetwork() }
        override fun onLost(network: Network) { updateNetwork() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updateNetwork() }
    }

    init {
        retry()
        restoreNotificationRationaleIfEligible()
    }

    fun retry() {
        action {
            preferences.initialize()
            _settingsReady.value = true
        }
        feedsJob?.cancel()
        feedsJob = viewModelScope.launch {
            _feeds.value = StorageLoad.Loading
            repository.feeds.catch { _feeds.value = StorageLoad.Error }
                .collect { _feeds.value = StorageLoad.Ready(it.filter { feed -> !feed.deleted }) }
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
    private fun updateNetwork(): RssConnectivity {
        val connection = networkStateOverride?.invoke()?.let { (online, wifi) ->
            RssConnectivity(online, wifi, mobile = online && !wifi)
        } ?: run {
            val validated = connectivity.allNetworks.mapNotNull(connectivity::getNetworkCapabilities).filter {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            RssConnectivity(
                online = validated.isNotEmpty(),
                wifi = validated.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                mobile = validated.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }
            )
        }
        _online.value = connection.online
        _wifi.value = connection.wifi
        return connection
    }

    fun saveSettings(
        value: RssSettings,
        permissionGranted: Boolean = hasPostNotificationsPermission(getApplication())
    ) = action {
        val previousDaily = settings.value.dailyEnabled
        preferences.save(value)
        RssScheduler.reschedule(getApplication())
        evaluateNotificationRationale(
            permissionGranted = permissionGranted,
            previousDailyEnabled = previousDaily,
            newDailyEnabled = value.dailyEnabled
        )
    }

    fun saveFeed(original: RssFeedEntity?, url: String) =
        saveFeed(original, url, null)

    private fun saveFeed(original: RssFeedEntity?, url: String,
        confirmed: RssRegistrationSnapshot?) = action {
        try {
            if (original == null) offerInitialFetch(repository.register(url, confirmed))
            else if (original.url == url.trim()) Unit
            else {
                // Validate/create the replacement first; an invalid new URL leaves the old feed intact.
                val id = repository.register(url, confirmed)
                repository.delete(original.id)
                offerInitialFetch(id)
            }
        } catch (required: RssRegistrationConfirmationRequired) {
            _registration.value = RssRegistrationRequest(original, url, required.snapshot)
        }
    }

    fun dismissRegistration() { _registration.value = null }
    fun confirmRegistration() {
        val request = _registration.value ?: return
        if (_busy.value) return
        _registration.value = null
        saveFeed(request.original, request.url, request.snapshot)
    }

    private suspend fun offerInitialFetch(id: String) = withContext(Dispatchers.Main.immediate) {
        savedState["initialFeed"] = id
        _initialFetch.value = id
    }
    private suspend fun clearInitialFetchState() = withContext(Dispatchers.Main.immediate) {
        savedState["initialFeed"] = null
        _initialFetch.value = null
    }
    fun dismissInitialFetch(
        permissionGranted: Boolean = hasPostNotificationsPermission(getApplication())
    ) {
        val hadInitial = _initialFetch.value != null
        savedState["initialFeed"] = null
        _initialFetch.value = null
        if (hadInitial) {
            viewModelScope.launch {
                evaluateNotificationRationale(
                    permissionGranted = permissionGranted,
                    initialRegistrationCompleted = true
                )
            }
        }
    }
    fun fetchInitial(
        mobileConsent: Boolean,
        permissionGranted: Boolean = hasPostNotificationsPermission(getApplication())
    ) = action {
        val id = _initialFetch.value ?: return@action
        val connection = updateNetwork()
        check(connection.online) { "No validated Internet connection is available" }
        val requiresOverride = settings.value.wifiOnly && !connection.wifi
        // A connection change must first make the warning visible before cellular is authorized.
        if (requiresOverride && !mobileConsent) return@action
        repository.requestInitialFetch(id, requiresOverride && mobileConsent)
        clearInitialFetchState()
        evaluateNotificationRationale(
            permissionGranted = permissionGranted,
            initialRegistrationCompleted = true
        )
    }

    fun consumeNotificationRationale(requestPermission: Boolean = false) {
        if (!_notificationRationale.value || _consumingNotificationRationale.value) return
        _consumingNotificationRationale.value = true
        viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                rationaleMutex.withLock {
                    val committed = withContext(Dispatchers.IO + NonCancellable) {
                        persistPermissionRequested()
                    }
                    if (committed) {
                        withContext(Dispatchers.Main.immediate + NonCancellable) {
                            savedState["notificationRationale"] = false
                            _notificationRationale.value = false
                            if (requestPermission) {
                                _permissionLaunchPending.value = true
                            }
                        }
                    }
                }
            } finally {
                _consumingNotificationRationale.value = false
            }
        }
    }

    fun consumePermissionLaunchRequest(): Boolean {
        if (!_permissionLaunchPending.value) return false
        _permissionLaunchPending.value = false
        return true
    }

    private fun restoreNotificationRationaleIfEligible() {
        viewModelScope.launch {
            rationaleMutex.withLock {
                if (savedState.get<Boolean>("notificationRationale") != true) return@withLock
                val permissionGranted = permissionGrantedProvider()
                val shouldRestore = withContext(Dispatchers.IO) {
                    val prefs = getApplication<Application>()
                        .getSharedPreferences(NOTIFICATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                    shouldOfferNotificationPermissionRationale(
                        permissionGranted = permissionGranted,
                        alreadyRequested = alreadyRequested
                    )
                }
                withContext(Dispatchers.Main.immediate) {
                    if (shouldRestore && savedState.get<Boolean>("notificationRationale") == true) {
                        _notificationRationale.value = true
                    } else {
                        savedState["notificationRationale"] = false
                        _notificationRationale.value = false
                    }
                }
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            viewModelScope.launch(Dispatchers.IO + NonCancellable) {
                NotificationCoordinator.getInstance(getApplication()).refresh()
            }
        }
    }

    private suspend fun evaluateNotificationRationale(
        permissionGranted: Boolean,
        previousDailyEnabled: Boolean = false,
        newDailyEnabled: Boolean = false,
        initialRegistrationCompleted: Boolean = false
    ) {
        rationaleMutex.withLock {
            val shouldPrompt = withContext(Dispatchers.IO) {
                val prefs = getApplication<Application>()
                    .getSharedPreferences(NOTIFICATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
                shouldPromptNotificationPermissionOnRssEvent(
                    permissionGranted = permissionGranted,
                    alreadyRequested = alreadyRequested,
                    previousDailyEnabled = previousDailyEnabled,
                    newDailyEnabled = newDailyEnabled,
                    initialRegistrationCompleted = initialRegistrationCompleted
                )
            }
            if (shouldPrompt) {
                withContext(Dispatchers.Main.immediate) {
                    savedState["notificationRationale"] = true
                    _notificationRationale.value = true
                }
            }
        }
    }

    fun deleteFeed(id: String) = action { repository.delete(id) }
    fun setEnabled(id: String, enabled: Boolean) = action { repository.setEnabled(id, enabled) }
    fun fetchNow() {
        if (manualMobilePrompt.value || !_settingsReady.value || _busy.value ||
            manualFetch.value != ManualFetchStatus.Idle) return
        action {
            val prompt = withContext(Dispatchers.Main.immediate) {
                val connection = updateNetwork()
                val needsConsent = settings.value.wifiOnly && !connection.wifi && connection.mobile
                if (needsConsent) savedState["manualMobilePrompt"] = true
                needsConsent
            }
            if (!prompt) enqueueManualFetch(false)
        }
    }

    internal fun confirmManualFetch(mobileAllowed: Boolean) {
        if (!manualMobilePrompt.value) return
        action {
            enqueueManualFetch(mobileAllowed)
            withContext(Dispatchers.Main.immediate) { dismissManualFetchPrompt() }
        }
    }

    internal fun dismissManualFetchPrompt() {
        savedState["manualMobilePrompt"] = false
    }
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
