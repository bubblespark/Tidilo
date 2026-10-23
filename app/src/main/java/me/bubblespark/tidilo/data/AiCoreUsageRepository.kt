package me.bubblespark.tidilo.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.io.IOException
import java.util.UUID
import android.content.Context
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal interface AiUsageStorage { fun read(): String?; fun write(value: String): Boolean }
enum class AiUsageSource { NORMAL, DEBUG }
enum class AiUsageOutcome { SUCCESS, FAILED, CANCELLED }
data class DailyAiUsage(
    val total: Long = 0, val success: Long = 0, val failed: Long = 0,
    val cancelled: Long = 0, val interrupted: Long = 0, val pending: Long = 0, val debug: Long = 0,
    val firstQuota: AiQuotaDetection? = null
)
data class AiQuotaDetection(val date: LocalDate, val detectedAt: Instant, val calls: Long)
data class AiUsageDay(val date: LocalDate, val usage: DailyAiUsage, val recorded: Boolean)
data class AiUsageSnapshot(val recordedSince: LocalDate, val days: Map<LocalDate, DailyAiUsage>, val lastQuota: AiQuotaDetection? = null) {
    fun week(today: LocalDate): List<AiUsageDay> = (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        AiUsageDay(date, days[date] ?: DailyAiUsage(), date >= recordedSince || date in days)
    }
}
sealed interface AiUsageState {
    data object Loading : AiUsageState
    data class Ready(val snapshot: AiUsageSnapshot, val persistenceFailed: Boolean = false) : AiUsageState
    data object Error : AiUsageState
}

internal class AiCoreUsageRepository(
    private val storage: AiUsageStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<AiUsageState>(AiUsageState.Loading)
    val state = _state.asStateFlow()
    private var snapshot: AiUsageSnapshot? = null
    private val pending = mutableMapOf<String, LocalDate>()
    private val quotaPending = mutableMapOf<LocalDate, MutableSet<String>>()

    /** Loads aggregates once per process and resolves abandoned attempts without guessing success. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (snapshot == null) loadLocked()
                else if ((_state.value as? AiUsageState.Ready)?.persistenceFailed == true) publishLocked()
            } catch (_: Exception) { _state.value = AiUsageState.Error }
        }
    }

    suspend fun begin(source: AiUsageSource): String = withContext(Dispatchers.IO) {
        lock.withLock {
            if (snapshot == null) try { loadLocked() } catch (error: Exception) {
                _state.value = AiUsageState.Error
                throw error
            }
            val current = requireNotNull(snapshot)
            val date = clock.instant().atZone(zone()).toLocalDate()
            val day = current.days[date] ?: DailyAiUsage()
            val id = UUID.randomUUID().toString()
            pending[id] = date
            snapshot = current.copy(days = current.days + (date to day.copy(
                total = day.total + 1, pending = day.pending + 1,
                debug = day.debug + if (source == AiUsageSource.DEBUG) 1 else 0)))
            publishLocked()
            id
        }
    }

    /** A token can transition to a terminal outcome only once, even across concurrent callers. */
    suspend fun complete(id: String, outcome: AiUsageOutcome, quota: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val date = pending.remove(id) ?: return@withLock
            val current = requireNotNull(snapshot)
            val day = current.days.getValue(date)
            val detection = if (quota && day.firstQuota == null) AiQuotaDetection(date, clock.instant(), day.total) else null
            if (detection != null) quotaPending[date] = pending.filterValues { it == date }.keys.toMutableSet()
            quotaPending[date]?.remove(id)
            val updated = day.copy(pending = day.pending - 1,
                success = day.success + if (outcome == AiUsageOutcome.SUCCESS) 1 else 0,
                failed = day.failed + if (outcome == AiUsageOutcome.FAILED) 1 else 0,
                cancelled = day.cancelled + if (outcome == AiUsageOutcome.CANCELLED) 1 else 0,
                firstQuota = detection ?: day.firstQuota)
            snapshot = current.copy(days = current.days + (date to updated), lastQuota = detection ?: current.lastQuota)
            publishLocked()
        }
    }

    /** Roll back a reservation if cancellation prevented entry into the SDK call. */
    suspend fun discardUnstarted(id: String, source: AiUsageSource) = withContext(Dispatchers.IO) {
        lock.withLock {
            val date = pending.remove(id) ?: return@withLock
            val current = requireNotNull(snapshot)
            val day = current.days.getValue(date)
            val quota = if (quotaPending[date]?.remove(id) == true)
                day.firstQuota?.copy(calls = day.firstQuota.calls - 1) else day.firstQuota
            snapshot = current.copy(days = current.days + (date to day.copy(
                total = day.total - 1, pending = day.pending - 1,
                debug = day.debug - if (source == AiUsageSource.DEBUG) 1 else 0, firstQuota = quota)),
                lastQuota = if (current.lastQuota == day.firstQuota) quota else current.lastQuota)
            publishLocked()
        }
    }

    suspend fun <T> track(source: AiUsageSource, block: suspend () -> T): T =
        AiUsageTracker(this) { it is GenAiException && it.errorCode == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED }
            .track(source, block)

    private fun loadLocked() {
        try {
            val raw = storage.read()
            if (raw == null) snapshot = AiUsageSnapshot(clock.instant().atZone(zone()).toLocalDate(), emptyMap())
            else {
                val json = JSONObject(raw)
                require(json.getInt("version") == 1)
                val daysJson = json.getJSONObject("days")
                val days = daysJson.keys().asSequence().associate { date ->
                    val value = daysJson.getJSONObject(date)
                    val day = DailyAiUsage(value.getLong("total"), value.getLong("success"), value.getLong("failed"),
                        value.getLong("cancelled"), value.getLong("interrupted"), value.getLong("pending"), value.getLong("debug"),
                        if (value.isNull("quota")) null else quotaFromJson(value.getJSONObject("quota")))
                    require(listOf(day.total, day.success, day.failed, day.cancelled, day.interrupted, day.pending, day.debug).all { it >= 0 })
                    require(day.total == day.success + day.failed + day.cancelled + day.interrupted + day.pending && day.debug <= day.total)
                    LocalDate.parse(date) to day
                }
                val pendingJson = json.getJSONObject("pending")
                val restored = pendingJson.keys().asSequence().associateWith { LocalDate.parse(pendingJson.getString(it)) }
                require(restored.values.all { it in days })
                val interrupted = days.mapValues { (date, day) ->
                    require(day.pending == restored.values.count { it == date }.toLong())
                    day.copy(interrupted = day.interrupted + day.pending, pending = 0)
                }
                snapshot = AiUsageSnapshot(LocalDate.parse(json.getString("since")), interrupted,
                    if (json.isNull("lastQuota")) null else quotaFromJson(json.getJSONObject("lastQuota")))
            }
            pending.clear()
            publishLocked()
        } catch (error: Exception) {
            snapshot = null
            throw IOException("Usage history could not be loaded", error)
        }
    }

    private fun quotaFromJson(json: JSONObject) = AiQuotaDetection(LocalDate.parse(json.getString("date")),
        Instant.parse(json.getString("at")), json.getLong("calls"))
    private fun quotaJson(value: AiQuotaDetection?) = value?.let {
        JSONObject().put("date", it.date.toString()).put("at", it.detectedAt.toString()).put("calls", it.calls)
    } ?: JSONObject.NULL

    private fun publishLocked() {
        val current = requireNotNull(snapshot)
        val saved = try {
            val days = JSONObject()
            current.days.forEach { (date, day) -> days.put(date.toString(), JSONObject()
                .put("total", day.total).put("success", day.success).put("failed", day.failed)
                .put("cancelled", day.cancelled).put("interrupted", day.interrupted).put("pending", day.pending)
                .put("debug", day.debug).put("quota", quotaJson(day.firstQuota))) }
            val active = JSONObject()
            pending.forEach { (id, date) -> active.put(id, date.toString()) }
            storage.write(JSONObject().put("version", 1).put("since", current.recordedSince.toString())
                .put("days", days).put("pending", active).put("lastQuota", quotaJson(current.lastQuota)).toString())
        } catch (_: Exception) { false }
        _state.value = AiUsageState.Ready(current, !saved)
    }

    companion object {
        @Volatile private var instance: AiCoreUsageRepository? = null
        fun getInstance(context: Context): AiCoreUsageRepository = instance ?: synchronized(this) {
            instance ?: run {
                val preferences = context.applicationContext.getSharedPreferences("tidilo_ai_usage", Context.MODE_PRIVATE)
                AiCoreUsageRepository(object : AiUsageStorage {
                    override fun read() = preferences.getString("snapshot", null)
                    override fun write(value: String) = preferences.edit().putString("snapshot", value).commit()
                }).also { instance = it }
            }
        }
    }
}

internal class AiUsageTracker(private val recorder: AiCoreUsageRepository, private val isQuota: (Throwable) -> Boolean) {
    /** Bookkeeping failures never replace the SDK result; caller cancellation still propagates. */
    suspend fun <T> track(source: AiUsageSource, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        var token: String? = null
        var enteredSdk = false
        suspend fun finish(outcome: AiUsageOutcome, quota: Boolean = false) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { token?.let { recorder.complete(it, outcome, quota) } } catch (_: Exception) { }
            }
        }
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                try { token = recorder.begin(source) } catch (_: Exception) { }
            }
            currentCoroutineContext().ensureActive()
            enteredSdk = true
            val result = block()
            finish(AiUsageOutcome.SUCCESS)
            return result
        } catch (cancelled: CancellationException) {
            if (enteredSdk) finish(AiUsageOutcome.CANCELLED)
            else withContext(NonCancellable + Dispatchers.IO) {
                try { token?.let { recorder.discardUnstarted(it, source) } } catch (_: Exception) { }
            }
            throw cancelled
        } catch (error: Exception) {
            finish(AiUsageOutcome.FAILED, isQuota(error))
            throw error
        }
    }
}
