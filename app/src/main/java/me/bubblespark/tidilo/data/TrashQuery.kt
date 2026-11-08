package me.bubblespark.tidilo.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Trash consumers own their error UI and must not wait forever on a failed startup/read. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun <T> trashBookmarkQuery(ready: Flow<Boolean>, failure: Flow<Throwable?>, query: () -> Flow<T>): Flow<T> =
    combine(ready, failure) { initialized, error -> initialized to error }.flatMapLatest { (initialized, error) ->
        when {
            error != null -> flow { throw error }
            initialized -> query()
            else -> emptyFlow()
        }
    }
