package me.bubblespark.tidilo.data

import android.content.Context
import me.bubblespark.tidilo.TidiloApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DomainPolicy(
    val domain: String,
    val isNsfw: Boolean = false,
    val displayName: String = ""
)

/**
 * Manages domain-level policies including NSFW flags and domain display names (og:site_name).
 * Persists domain policy records in domain_policies.json.
 */
object DomainPolicyManager {

    private const val FILE_NAME = "domain_policies.json"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val domainMap = ConcurrentHashMap<String, DomainPolicy>()

    private val _policies = MutableStateFlow<List<DomainPolicy>>(emptyList())
    val policies: StateFlow<List<DomainPolicy>> = _policies.asStateFlow()

    private val initialLoad = scope.launch {
        runCatching { loadPolicies() }
    }

    private fun getStorageFile(context: Context? = null): File? {
        val targetContext = context ?: runCatching { TidiloApplication.instance }.getOrNull()
        return targetContext?.let { File(it.filesDir, FILE_NAME) }
    }

    /**
     * Loads domain policies from disk.
     */
    fun loadPolicies(context: Context? = null) {
        val file = getStorageFile(context) ?: return
        if (!file.exists() || file.length() == 0L) {
            domainMap.clear()
            _policies.value = emptyList()
            return
        }

        try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            val newMap = ConcurrentHashMap<String, DomainPolicy>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val domain = obj.optString("domain")
                val isNsfw = obj.optBoolean("isNsfw", false)
                val displayName = obj.optString("displayName", "")
                if (domain.isNotBlank()) {
                    val normalized = domain.lowercase()
                    newMap[normalized] = DomainPolicy(normalized, isNsfw, displayName)
                }
            }
            domainMap.clear()
            domainMap.putAll(newMap)
            notifyStateUpdated()
        } catch (_: Exception) {
            // In case of parsing error, preserve current memory state
        }
    }

    /**
     * Persists the current domain policies to disk.
     */
    @Synchronized
    private fun savePolicies(context: Context? = null, propagateFailure: Boolean = false) {
        try {
            val file = getStorageFile(context) ?: if (propagateFailure) throw java.io.IOException("Policy storage unavailable") else return
            val jsonArray = JSONArray()
            domainMap.values.sortedBy { it.domain }.forEach { policy ->
                val obj = JSONObject().apply {
                    put("domain", policy.domain)
                    put("isNsfw", policy.isNsfw)
                    if (policy.displayName.isNotBlank()) {
                        put("displayName", policy.displayName)
                    }
                }
                jsonArray.put(obj)
            }
            file.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            if (propagateFailure) throw e
            // Non-critical persistence failure
        }
    }

    private fun notifyStateUpdated() {
        _policies.value = domainMap.values.sortedBy { it.domain }
    }

    /**
     * Registers a new domain. If it does not exist, registers with default isNsfw (false).
     * If displayName is provided and existing domain has no displayName, updates it.
     */
    fun registerDomain(domain: String?, isNsfw: Boolean = false, displayName: String = "") {
        if (domain.isNullOrBlank()) return
        val normalized = domain.trim().lowercase()
        val existing = domainMap[normalized]
        if (existing == null) {
            domainMap[normalized] = DomainPolicy(normalized, isNsfw, displayName.trim())
            notifyStateUpdated()
            scope.launch {
                savePolicies()
            }
        } else if (displayName.isNotBlank() && existing.displayName.isBlank()) {
            domainMap[normalized] = existing.copy(displayName = displayName.trim())
            notifyStateUpdated()
            scope.launch {
                savePolicies()
            }
        }
    }

    /**
     * Updates or sets the display name (e.g. from og:site_name) of a specific domain.
     */
    fun setDomainDisplayName(domain: String, displayName: String) {
        val normalized = domain.trim().lowercase()
        val cleanName = displayName.trim()
        if (normalized.isBlank() || cleanName.isBlank()) return

        val existing = domainMap[normalized]
        if (existing != null) {
            if (existing.displayName != cleanName) {
                domainMap[normalized] = existing.copy(displayName = cleanName)
                notifyStateUpdated()
                scope.launch {
                    savePolicies()
                }
            }
        } else {
            domainMap[normalized] = DomainPolicy(normalized, false, cleanName)
            notifyStateUpdated()
            scope.launch {
                savePolicies()
            }
        }
    }

    /**
     * Returns the human-readable display name for the domain.
     * Falls back to the domain itself if no displayName was saved.
     */
    fun getDisplayName(domain: String?): String {
        if (domain.isNullOrBlank()) return ""
        val normalized = domain.trim().lowercase()
        val stored = domainMap[normalized]?.displayName
        return if (!stored.isNullOrBlank()) stored else normalized
    }

    /**
     * Returns the raw display name for the domain, or blank if not set.
     */
    fun getDomainDisplayName(domain: String?): String {
        if (domain.isNullOrBlank()) return ""
        val normalized = domain.trim().lowercase()
        return domainMap[normalized]?.displayName ?: ""
    }

    /**
     * Synchronizes a list of domains from bookmarks or other sources.
     */
    fun syncDomains(domains: Collection<String?>) {
        var hasNew = false
        domains.filterNotNull().forEach { raw ->
            val normalized = raw.trim().lowercase()
            if (normalized.isNotBlank() && !domainMap.containsKey(normalized)) {
                domainMap[normalized] = DomainPolicy(normalized, false, "")
                hasNew = true
            }
        }
        if (hasNew) {
            notifyStateUpdated()
            scope.launch {
                savePolicies()
            }
        }
    }

    /**
     * Updates the NSFW flag of a specific domain.
     */
    suspend fun setDomainNsfwAndAwait(domain: String, isNsfw: Boolean) {
        completeDomainPolicyChange(scope,
            { BookmarkRepository.getInstance().updateBookmarksNsfwForDomain(domain, isNsfw) },
            { setDomainNsfwAndPersist(domain, isNsfw) })
    }

    /** Writes the policy before publishing success and propagates storage failures. */
    internal suspend fun setDomainNsfwAndPersist(domain: String, isNsfw: Boolean, context: Context? = null) {
        initialLoad.join()
        updateDomainNsfw(domain, isNsfw)
        savePolicies(context, propagateFailure = true)
        notifyStateUpdated()
    }

    fun setDomainNsfw(domain: String, isNsfw: Boolean) {
        updateDomainNsfw(domain, isNsfw)
        notifyStateUpdated()
        scope.launch { savePolicies() }
    }

    private fun updateDomainNsfw(domain: String, isNsfw: Boolean) {
        val normalized = domain.trim().lowercase()
        val existing = domainMap[normalized]
        if (existing != null) {
            domainMap[normalized] = existing.copy(isNsfw = isNsfw)
        } else {
            domainMap[normalized] = DomainPolicy(normalized, isNsfw, "")
        }
    }

    /**
     * Checks if the given domain is marked as NSFW.
     */
    fun isNsfwDomain(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        val normalized = domain.trim().lowercase()
        return domainMap[normalized]?.isNsfw ?: false
    }

    /**
     * Returns all registered domain policies sorted by domain name.
     */
    fun getAllPolicies(): List<DomainPolicy> {
        return _policies.value
    }

    /**
     * Clears all domain policies (useful for testing).
     */
    fun clearPoliciesForTest() {
        domainMap.clear()
        _policies.value = emptyList()
        runCatching { getStorageFile()?.delete() }
    }
}

/** Once started, both steps belong to the application rather than the settings composition. */
internal suspend fun completeDomainPolicyChange(
    scope: CoroutineScope, persist: suspend () -> Unit, publish: suspend () -> Unit
) = scope.async {
    persist()
    publish()
}.await()
