package me.bubblespark.tidilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.data.DomainIconManager

/** Keeps completion observations separate from the lifetime of a shared icon request. */
@Composable
internal fun rememberDomainIcon(
    domain: String?, pageUrl: String? = null, allowNetwork: Boolean = true
): State<ImageBitmap?> {
    val context = LocalContext.current
    val generation by DomainIconManager.cacheGenerationState.collectAsStateWithLifecycle()
    return key(context, domain, pageUrl, generation, allowNetwork) {
        val cached = domain?.let { DomainIconManager.getCachedIconBitmap(it) }
        produceState<ImageBitmap?>(initialValue = cached) {
            if (!domain.isNullOrBlank()) coroutineScope {
                launch {
                    DomainIconManager.observeIconUpdates(domain).collect {
                        DomainIconManager.getCachedIconBitmap(domain)?.let { value = it }
                    }
                }
                if (cached == null) {
                    value = withContext(Dispatchers.IO) {
                        if (allowNetwork) DomainIconManager.loadOrFetchIcon(context, domain, pageUrl)
                        else DomainIconManager.loadIconFromDisk(context, domain)
                    }
                }
            }
        }
    }
}
