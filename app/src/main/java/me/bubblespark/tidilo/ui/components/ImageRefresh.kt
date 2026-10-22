package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import me.bubblespark.tidilo.data.imageRefreshSignals

/** Rechecks local cache on lifecycle restart, even if updates occurred while inactive. */
@Composable
internal fun rememberImageRefresh(domain: String?, updates: Flow<String>, clears: Flow<Long>): Any {
    val signals = remember(domain, updates, clears) { imageRefreshSignals(domain, updates, clears) }
    val signal by signals.collectAsStateWithLifecycle(initialValue = Unit)
    return signal
}
