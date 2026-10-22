package me.bubblespark.tidilo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Preserves invalidations when a burst fills the shared flow's buffer. */
internal fun MutableSharedFlow<String>.emitUpdate(domain: String) {
    if (!tryEmit(domain)) {
        notificationScope.launch { emit(domain) }
    }
}

/** Each matching event has a distinct identity, including repeated updates of one domain. */
internal fun imageRefreshSignals(
    domain: String?,
    updates: Flow<String>,
    clears: Flow<Long>
): Flow<Any> = merge(
    updates.filter { domain != null && it == domain }.map { Any() },
    clears.map { Any() }
).onStart { emit(Any()) }
