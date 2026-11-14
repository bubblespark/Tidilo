package me.bubblespark.tidilo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.bubblespark.tidilo.TidiloApplication
import java.util.concurrent.ConcurrentHashMap

data class DomainPolicy(
    val domain: String,
    val isPrivate: Boolean = false,
    val displayName: String = ""
)

/**
 * Compatibility facade for domain-level policies (Private flags and domain display names).
 * Delegates to Room-backed [DomainPolicyStore] when an Android application context is active,
 * and falls back to a lightweight in-memory map in context-free JVM unit tests.
 */
object DomainPolicyManager {

    private val fallbackDomainMap = ConcurrentHashMap<String, DomainPolicy>()
    private val _fallbackPolicies = MutableStateFlow<List<DomainPolicy>>(emptyList())

    val policies: StateFlow<List<DomainPolicy>>
        get() = activeStoreOrNull()?.policies ?: _fallbackPolicies.asStateFlow()

    private fun activeStoreOrNull(): DomainPolicyStore? {
        val app = runCatching { TidiloApplication.instance }.getOrNull() ?: return null
        return runCatching { DomainPolicyStore.getInstance(app) }.getOrNull()
    }

    private fun notifyFallbackUpdated() {
        _fallbackPolicies.value = fallbackDomainMap.values.sortedBy { it.domain }
    }

    /**
     * Registers a new domain. If it does not exist, registers with default isPrivate (false).
     * If displayName is provided and existing domain has no displayName, updates it.
     */
    @Synchronized
    fun registerDomain(domain: String?, isPrivate: Boolean = false, displayName: String = "") {
        activeStoreOrNull()?.let {
            it.registerDomain(domain, isPrivate, displayName)
            return
        }
        if (domain.isNullOrBlank()) return
        val normalized = domain.trim().lowercase()
        val existing = fallbackDomainMap[normalized]
        if (existing == null) {
            fallbackDomainMap[normalized] = DomainPolicy(normalized, isPrivate, displayName.trim())
            notifyFallbackUpdated()
        } else if (displayName.isNotBlank() && existing.displayName.isBlank()) {
            fallbackDomainMap[normalized] = existing.copy(displayName = displayName.trim())
            notifyFallbackUpdated()
        }
    }

    /**
     * Updates or sets the display name (e.g. from og:site_name) of a specific domain.
     */
    @Synchronized
    fun setDomainDisplayName(domain: String, displayName: String) {
        activeStoreOrNull()?.let {
            it.setDomainDisplayName(domain, displayName)
            return
        }
        val normalized = domain.trim().lowercase()
        val cleanName = displayName.trim()
        if (normalized.isBlank() || cleanName.isBlank()) return

        val existing = fallbackDomainMap[normalized]
        if (existing != null) {
            if (existing.displayName != cleanName) {
                fallbackDomainMap[normalized] = existing.copy(displayName = cleanName)
                notifyFallbackUpdated()
            }
        } else {
            fallbackDomainMap[normalized] = DomainPolicy(normalized, false, cleanName)
            notifyFallbackUpdated()
        }
    }

    /**
     * Returns the human-readable display name for the domain.
     * Falls back to the domain itself if no displayName was saved.
     */
    fun getDisplayName(domain: String?): String {
        activeStoreOrNull()?.let { return it.getDisplayName(domain) }
        if (domain.isNullOrBlank()) return ""
        val normalized = domain.trim().lowercase()
        val stored = fallbackDomainMap[normalized]?.displayName
        return if (!stored.isNullOrBlank()) stored else normalized
    }

    /**
     * Returns the raw display name for the domain, or blank if not set.
     */
    fun getDomainDisplayName(domain: String?): String {
        activeStoreOrNull()?.let { return it.getDomainDisplayName(domain) }
        if (domain.isNullOrBlank()) return ""
        val normalized = domain.trim().lowercase()
        return fallbackDomainMap[normalized]?.displayName ?: ""
    }

    /**
     * Synchronizes a list of domains from bookmarks or other sources.
     */
    @Synchronized
    fun syncDomains(domains: Collection<String?>) {
        activeStoreOrNull()?.let {
            it.syncDomains(domains)
            return
        }
        var hasNew = false
        domains.filterNotNull().forEach { raw ->
            val normalized = raw.trim().lowercase()
            if (normalized.isNotBlank() && !fallbackDomainMap.containsKey(normalized)) {
                fallbackDomainMap[normalized] = DomainPolicy(normalized, false, "")
                hasNew = true
            }
        }
        if (hasNew) {
            notifyFallbackUpdated()
        }
    }

    /**
     * Updates the Private flag of a specific domain and its bookmarks.
     */
    suspend fun setDomainPrivateAndAwait(domain: String, isPrivate: Boolean) {
        val store = activeStoreOrNull()
        if (store != null) {
            store.setDomainPrivateAndAwait(domain, isPrivate)
            return
        }
        setDomainPrivate(domain, isPrivate)
    }

    @Synchronized
    fun setDomainPrivate(domain: String, isPrivate: Boolean) {
        activeStoreOrNull()?.let {
            it.setDomainPrivate(domain, isPrivate)
            return
        }
        val normalized = domain.trim().lowercase()
        if (normalized.isBlank()) return
        val existing = fallbackDomainMap[normalized]
        fallbackDomainMap[normalized] = existing?.copy(isPrivate = isPrivate) ?: DomainPolicy(normalized, isPrivate, "")
        notifyFallbackUpdated()
    }

    /**
     * Checks if the given domain is marked as Private.
     */
    fun isPrivateDomain(domain: String?): Boolean {
        activeStoreOrNull()?.let { return it.isPrivateDomain(domain) }
        if (domain.isNullOrBlank()) return false
        val normalized = domain.trim().lowercase()
        return fallbackDomainMap[normalized]?.isPrivate ?: false
    }

    /**
     * Returns all registered domain policies sorted by domain name.
     */
    fun getAllPolicies(): List<DomainPolicy> {
        activeStoreOrNull()?.let { return it.getAllPolicies() }
        return _fallbackPolicies.value
    }

    /**
     * Clears in-memory fallback domain policies (useful for context-free unit testing).
     */
    @Synchronized
    fun clearPoliciesForTest() {
        fallbackDomainMap.clear()
        _fallbackPolicies.value = emptyList()
    }
}
