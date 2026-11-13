package me.bubblespark.tidilo.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.DomainPolicyEntity
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Room-backed store for per-domain NSFW policies and display names.
 * Owns one-time idempotent migration from legacy domain_policies.json and updates
 * domain policies and bookmark NSFW flags in a single atomic transaction.
 */
class DomainPolicyStore internal constructor(
    private val database: AppDatabase,
    private val legacyJsonFile: File? = null,
    private val transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
    },
    private val storeScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val domainPolicyDao = database.domainPolicyDao()
    private val bookmarkDao = database.bookmarkDao()

    private val domainMap = ConcurrentHashMap<String, DomainPolicy>()
    private val _policies = MutableStateFlow<List<DomainPolicy>>(emptyList())
    val policies: StateFlow<List<DomainPolicy>> = _policies.asStateFlow()

    private val readyMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    private var isReady = false

    init {
        storeScope.launch {
            runCatching { ensureReady() }
        }
    }

    internal suspend fun awaitReady() {
        ensureReady()
    }

    private suspend fun ensureReady() {
        if (isReady) return
        readyMutex.withLock {
            if (isReady) return
            val (legacyPolicies, parsedCleanly) = readLegacyJsonPolicies(legacyJsonFile)
            val loaded = transactionRunner.run {
                val existingList = domainPolicyDao.getAll()
                if (legacyPolicies.isNotEmpty()) {
                    val existingByDomain = existingList.associateBy { it.domain }
                    val merged = legacyPolicies.map { legacy ->
                        val existing = existingByDomain[legacy.domain]
                        if (existing == null) {
                            DomainPolicyEntity.fromDomainPolicy(legacy)
                        } else {
                            DomainPolicyEntity(
                                domain = existing.domain,
                                isNsfw = existing.isNsfw || legacy.isNsfw,
                                displayName = existing.displayName.ifBlank { legacy.displayName }
                            )
                        }
                    }
                    merged.chunked(BULK_BATCH_SIZE).forEach { chunk ->
                        domainPolicyDao.upsertAll(chunk)
                    }
                    domainPolicyDao.getAll()
                } else {
                    existingList
                }
            }
            if (parsedCleanly) {
                deleteLegacyFiles(legacyJsonFile)
            }
            mergeLoadedEntitiesIntoCache(loaded)
            isReady = true
        }
    }

    private fun readLegacyJsonPolicies(file: File?): Pair<List<DomainPolicy>, Boolean> {
        if (file == null || !file.exists()) return emptyList<DomainPolicy>() to true
        if (file.length() == 0L) return emptyList<DomainPolicy>() to true
        return try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            val mergedByDomain = LinkedHashMap<String, DomainPolicy>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val normalized = normalizeDomain(obj.optString("domain")) ?: continue
                val isNsfw = obj.optBoolean("isNsfw", false)
                val displayName = obj.optString("displayName", "").trim()
                val existing = mergedByDomain[normalized]
                mergedByDomain[normalized] = if (existing == null) {
                    DomainPolicy(normalized, isNsfw, displayName)
                } else {
                    DomainPolicy(
                        domain = normalized,
                        isNsfw = existing.isNsfw || isNsfw,
                        displayName = existing.displayName.ifBlank { displayName }
                    )
                }
            }
            mergedByDomain.values.toList() to true
        } catch (_: Exception) {
            emptyList<DomainPolicy>() to false
        }
    }

    private fun deleteLegacyFiles(file: File?) {
        if (file == null) return
        runCatching {
            val pendingFile = file.parentFile?.let { File(it, file.name + ".pending") }
            if (pendingFile?.exists() == true) {
                pendingFile.delete()
            }
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun mergeLoadedEntitiesIntoCache(entities: List<DomainPolicyEntity>) {
        entities.forEach { entity ->
            val fromDb = entity.toDomainPolicy()
            domainMap.merge(fromDb.domain, fromDb) { existing, dbPolicy ->
                DomainPolicy(
                    domain = dbPolicy.domain,
                    isNsfw = existing.isNsfw || dbPolicy.isNsfw,
                    displayName = existing.displayName.ifBlank { dbPolicy.displayName }
                )
            }
        }
        notifyStateUpdated()
    }

    private fun replaceCacheFromEntities(entities: List<DomainPolicyEntity>) {
        val next = entities.associate { it.domain to it.toDomainPolicy() }
        domainMap.clear()
        domainMap.putAll(next)
        notifyStateUpdated()
    }

    private fun notifyStateUpdated() {
        _policies.value = domainMap.values.sortedBy { it.domain }
    }

    fun isNsfwDomain(domain: String?): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        return domainMap[normalized]?.isNsfw ?: false
    }

    fun getDisplayName(domain: String?): String {
        val normalized = normalizeDomain(domain) ?: return ""
        val stored = domainMap[normalized]?.displayName
        return if (!stored.isNullOrBlank()) stored else normalized
    }

    fun getDomainDisplayName(domain: String?): String {
        val normalized = normalizeDomain(domain) ?: return ""
        return domainMap[normalized]?.displayName ?: ""
    }

    fun getAllPolicies(): List<DomainPolicy> = _policies.value

    fun registerDomain(domain: String?, isNsfw: Boolean = false, displayName: String = "") {
        val normalized = normalizeDomain(domain) ?: return
        val cleanName = displayName.trim()
        val existing = domainMap[normalized]
        var cacheChanged = false
        if (existing == null) {
            domainMap[normalized] = DomainPolicy(normalized, isNsfw, cleanName)
            cacheChanged = true
        } else if (cleanName.isNotBlank() && existing.displayName.isBlank()) {
            domainMap[normalized] = existing.copy(displayName = cleanName)
            cacheChanged = true
        }
        if (cacheChanged) {
            notifyStateUpdated()
        }
        storeScope.launch {
            try {
                registerDomainAndAwait(normalized, isNsfw, cleanName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical background persistence failure
            }
        }
    }

    suspend fun registerDomainAndAwait(domain: String?, isNsfw: Boolean = false, displayName: String = "") {
        val normalized = normalizeDomain(domain) ?: return
        val cleanName = displayName.trim()
        ensureReady()
        writeMutex.withLock {
            val saved = transactionRunner.run {
                val existing = domainPolicyDao.getByDomain(normalized)
                if (existing == null) {
                    val created = DomainPolicyEntity(normalized, isNsfw, cleanName)
                    domainPolicyDao.upsert(created)
                    created
                } else if (cleanName.isNotBlank() && existing.displayName.isBlank()) {
                    val updated = existing.copy(displayName = cleanName)
                    domainPolicyDao.upsert(updated)
                    updated
                } else {
                    existing
                }
            }
            val policy = saved.toDomainPolicy()
            if (domainMap[normalized] != policy) {
                domainMap[normalized] = policy
                notifyStateUpdated()
            }
        }
    }

    fun setDomainDisplayName(domain: String, displayName: String) {
        val normalized = normalizeDomain(domain) ?: return
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) return
        val existing = domainMap[normalized]
        if (existing == null || existing.displayName != cleanName) {
            domainMap[normalized] = existing?.copy(displayName = cleanName)
                ?: DomainPolicy(normalized, false, cleanName)
            notifyStateUpdated()
        }
        storeScope.launch {
            try {
                setDomainDisplayNameAndAwait(normalized, cleanName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical background persistence failure
            }
        }
    }

    suspend fun setDomainDisplayNameAndAwait(domain: String, displayName: String) {
        val normalized = normalizeDomain(domain) ?: return
        val cleanName = displayName.trim()
        if (cleanName.isBlank()) return
        ensureReady()
        writeMutex.withLock {
            val saved = transactionRunner.run {
                val existing = domainPolicyDao.getByDomain(normalized)
                if (existing != null) {
                    if (existing.displayName != cleanName) {
                        val updated = existing.copy(displayName = cleanName)
                        domainPolicyDao.upsert(updated)
                        updated
                    } else {
                        existing
                    }
                } else {
                    val created = DomainPolicyEntity(normalized, false, cleanName)
                    domainPolicyDao.upsert(created)
                    created
                }
            }
            val policy = saved.toDomainPolicy()
            if (domainMap[normalized] != policy) {
                domainMap[normalized] = policy
                notifyStateUpdated()
            }
        }
    }

    fun syncDomains(domains: Collection<String?>) {
        val normalizedDomains = domains.mapNotNull { normalizeDomain(it) }.distinct()
        if (normalizedDomains.isEmpty()) return
        var hasNew = false
        normalizedDomains.forEach { normalized ->
            if (!domainMap.containsKey(normalized)) {
                domainMap[normalized] = DomainPolicy(normalized, false, "")
                hasNew = true
            }
        }
        if (hasNew) {
            notifyStateUpdated()
        }
        storeScope.launch {
            try {
                syncDomainsAndAwait(normalizedDomains)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical background persistence failure
            }
        }
    }

    suspend fun syncDomainsAndAwait(domains: Collection<String?>) {
        val normalizedDomains = domains.mapNotNull { normalizeDomain(it) }.distinct()
        if (normalizedDomains.isEmpty()) return
        ensureReady()
        writeMutex.withLock {
            val loaded = transactionRunner.run {
                val existing = domainPolicyDao.getAll().mapTo(HashSet()) { it.domain }
                val missing = normalizedDomains.filter { it !in existing }
                if (missing.isEmpty()) return@run null
                missing.map { DomainPolicyEntity(domain = it, isNsfw = false, displayName = "") }
                    .chunked(BULK_BATCH_SIZE)
                    .forEach { chunk ->
                        domainPolicyDao.insertAllIgnore(chunk)
                    }
                domainPolicyDao.getAll()
            }
            if (loaded != null) {
                loaded.forEach { domainMap[it.domain] = it.toDomainPolicy() }
                notifyStateUpdated()
            }
        }
    }

    fun setDomainNsfw(domain: String, isNsfw: Boolean) {
        val normalized = normalizeDomain(domain) ?: return
        val existing = domainMap[normalized]
        domainMap[normalized] = existing?.copy(isNsfw = isNsfw) ?: DomainPolicy(normalized, isNsfw, "")
        notifyStateUpdated()
        storeScope.launch {
            try {
                setDomainNsfwAndAwait(normalized, isNsfw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-critical background persistence failure
            }
        }
    }

    /**
     * Atomically persists the domain NSFW flag and updates all matching bookmarks
     * in a single transaction on [storeScope], surviving caller cancellation once started.
     */
    suspend fun setDomainNsfwAndAwait(domain: String, isNsfw: Boolean) {
        val normalized = normalizeDomain(domain) ?: return
        storeScope.async {
            ensureReady()
            writeMutex.withLock {
                val updated = transactionRunner.run {
                    val existing = domainPolicyDao.getByDomain(normalized)
                    val entity = existing?.copy(isNsfw = isNsfw)
                        ?: DomainPolicyEntity(domain = normalized, isNsfw = isNsfw, displayName = "")
                    domainPolicyDao.upsert(entity)
                    bookmarkDao.updateDomainNsfw(normalized, isNsfw)
                    entity
                }
                domainMap[normalized] = updated.toDomainPolicy()
                notifyStateUpdated()
            }
        }.await()
    }

    companion object {
        private const val LEGACY_FILE_NAME = "domain_policies.json"
        private const val BULK_BATCH_SIZE = 500

        @Volatile
        private var INSTANCE: DomainPolicyStore? = null

        fun getInstance(context: Context = TidiloApplication.instance): DomainPolicyStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createProductionInstance(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun createProductionInstance(appContext: Context): DomainPolicyStore {
            return DomainPolicyStore(
                database = AppDatabase.getInstance(appContext),
                legacyJsonFile = File(appContext.filesDir, LEGACY_FILE_NAME)
            )
        }

        private fun normalizeDomain(domain: String?): String? {
            return domain?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        }
    }
}
