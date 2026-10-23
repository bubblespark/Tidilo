package me.bubblespark.tidilo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Keeps failed observers retryable and prevents reads racing database initialization. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> recoverableBookmarkQuery(
    ready: Flow<Boolean>, retry: Flow<Int>, onFailure: (Throwable) -> Unit, query: () -> Flow<T>
): Flow<T> = combine(ready, retry) { initialized, attempt -> initialized to attempt }
    .flatMapLatest { (initialized, _) ->
        if (!initialized) emptyFlow() else flow { emitAll(query()) }.catch {
            if (it is CancellationException) throw it
            onFailure(it)
        }
    }
